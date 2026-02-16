package com.mcsfeb.tvalarmclock.service

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * AdbShell - Sends key events by connecting to ADB over TCP on localhost.
 *
 * WHY: Android apps cannot inject key events into other apps (needs INJECT_EVENTS
 * signature-level permission). ADB shell CAN. By connecting to ADB on localhost:5555,
 * we run commands with shell-user permissions.
 *
 * PERSISTENT CONNECTION: Maintains a single TCP connection to ADB, authenticated
 * once with RSA keys. All shell commands reuse this connection (no repeated auth
 * dialogs).
 *
 * KEY FORMAT: ADB requires a custom 524-byte RSAPublicKey struct (NOT standard
 * X.509/PKCS8). The struct includes Montgomery multiplication parameters (n0inv,
 * R²) with the modulus in little-endian format.
 *
 * SETUP (one-time per device):
 *   1. Connect computer to TV via USB/WiFi ADB
 *   2. Run: adb tcpip 5555
 *   3. On first use, the TV will show "Allow USB debugging?" - tap "Always allow"
 */
object AdbShell {
    private const val TAG = "AdbShell"
    private const val ADB_PORT = 5555
    private const val TIMEOUT_MS = 5000
    private const val CONNECT_TIMEOUT_MS = 3000

    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_AUTH = 0x48545541
    private const val A_WRTE = 0x45545257
    private const val ADB_VERSION = 0x01000000
    private const val MAX_PAYLOAD = 4096

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    // Modulus size for 2048-bit RSA key: 2048/32 = 64 words
    private const val MODULUS_SIZE_WORDS = 64
    // Total struct size: 4 + 4 + 256 + 256 + 4 = 524 bytes
    private const val ANDROID_PUBKEY_STRUCT_SIZE = 524

    private var cachedPrivateKey: java.security.PrivateKey? = null
    private var cachedPublicKeyBytes: ByteArray? = null

    // Persistent connection state
    private var persistentSocket: Socket? = null
    private var persistentOutput: OutputStream? = null
    private var persistentInput: InputStream? = null
    private var nextLocalId = 1
    private val connectionLock = Object()

    /**
     * Initialize the ADB key pair. Call this once from Application or Service.
     */
    fun init(context: Context) {
        try {
            val keyDir = File(context.filesDir, "adb_keys")
            keyDir.mkdirs()
            val privFile = File(keyDir, "adb_key")
            val pubFile = File(keyDir, "adb_key.pub")

            if (privFile.exists() && pubFile.exists()) {
                val privBytes = privFile.readBytes()
                val spec = PKCS8EncodedKeySpec(privBytes)
                cachedPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
                cachedPublicKeyBytes = pubFile.readBytes()
                Log.d(TAG, "Loaded existing ADB keys")
            } else {
                val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                    initialize(2048)
                }.generateKeyPair()

                cachedPrivateKey = keyPair.private
                privFile.writeBytes(keyPair.private.encoded)

                // Encode public key in Android's custom ADB format (NOT X.509!)
                cachedPublicKeyBytes = encodeAdbPublicKey(keyPair.public as RSAPublicKey)
                pubFile.writeBytes(cachedPublicKeyBytes!!)

                Log.i(TAG, "Generated new ADB key pair (Android RSA format)")
            }

            // Pre-connect in background so first command is fast
            Thread {
                try {
                    ensureConnected()
                    Log.i(TAG, "ADB pre-connection established")
                } catch (e: Exception) {
                    Log.d(TAG, "ADB pre-connection failed (will retry on first command): ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ADB keys: ${e.message}")
        }
    }

    /**
     * Encode an RSA public key in Android's custom ADB format.
     *
     * The format is a 524-byte struct:
     *   uint32_t modulus_size_words  (64 for 2048-bit)
     *   uint32_t n0inv              (-1 / n[0] mod 2^32)
     *   uint8_t  modulus[256]       (little-endian)
     *   uint8_t  rr[256]            (R² mod N, little-endian)
     *   uint32_t exponent           (65537)
     *
     * This is then base64-encoded and appended with " user@host\0".
     */
    private fun encodeAdbPublicKey(pubKey: RSAPublicKey): ByteArray {
        val n = pubKey.modulus           // BigInteger
        val e = pubKey.publicExponent    // BigInteger (65537)

        // Compute n0inv = -(n^(-1)) mod 2^32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()

        // Compute rr = (2^2048)^2 mod n = 2^4096 mod n
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)

        // Pack the struct (little-endian)
        val buf = ByteBuffer.allocate(ANDROID_PUBKEY_STRUCT_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_SIZE_WORDS)
        buf.putInt(n0inv)
        buf.put(toLittleEndianPadded(n, 256))
        buf.put(toLittleEndianPadded(rr, 256))
        buf.putInt(e.toInt())

        // Base64-encode the struct and append user info + null terminator
        val structBase64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        val fullKey = "$structBase64 tv-alarm-clock@android\u0000"
        return fullKey.toByteArray(Charsets.UTF_8)
    }

    /**
     * Convert a BigInteger to a little-endian byte array, padded to the given size.
     */
    private fun toLittleEndianPadded(value: BigInteger, size: Int): ByteArray {
        val result = ByteArray(size)
        val bigEndian = value.toByteArray() // big-endian, may have leading 0x00

        // Skip any leading sign byte (0x00 for positive numbers)
        val start = if (bigEndian.isNotEmpty() && bigEndian[0].toInt() == 0) 1 else 0
        val len = bigEndian.size - start

        // Copy bytes in reverse order (big-endian → little-endian)
        for (i in 0 until minOf(len, size)) {
            result[i] = bigEndian[bigEndian.size - 1 - i]
        }
        return result
    }

    fun sendKeyEvent(keyCode: Int): Boolean {
        return sendShellCommand("input keyevent $keyCode")
    }

    /**
     * Send a shell command over the persistent ADB connection.
     * If the connection is not established, connect first.
     * If the connection drops, reconnect and retry once.
     */
    fun sendShellCommand(command: String): Boolean {
        if (cachedPrivateKey == null) {
            Log.w(TAG, "ADB keys not initialized. Call AdbShell.init() first.")
            return false
        }

        synchronized(connectionLock) {
            // Try with existing connection
            if (isConnected()) {
                val result = trySendCommand(command)
                if (result) return true
                // Connection might be stale — close and retry
                Log.d(TAG, "Command failed on existing connection, reconnecting...")
                closeConnection()
            }

            // Establish new connection and send
            return try {
                ensureConnected()
                trySendCommand(command)
            } catch (e: Exception) {
                Log.w(TAG, "ADB command failed after reconnect: ${e.message}")
                closeConnection()
                false
            }
        }
    }

    /**
     * Check if we have an active, connected socket.
     */
    private fun isConnected(): Boolean {
        val sock = persistentSocket ?: return false
        return !sock.isClosed && sock.isConnected
    }

    /**
     * Establish a persistent ADB connection with authentication.
     */
    private fun ensureConnected() {
        if (isConnected()) return

        closeConnection() // Clean up any stale state

        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), CONNECT_TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // Step 1: Send CNXN
        val identity = "host::\u0000"
        sendMessage(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, identity.toByteArray())

        // Step 2: Read response
        var response = readMessage(input)
            ?: throw Exception("No response from ADB daemon")

        // Step 3: Handle AUTH if required
        if (response.command == A_AUTH && response.arg0 == ADB_AUTH_TOKEN) {
            val token = response.data ?: ByteArray(0)
            Log.d(TAG, "AUTH challenge received (token size=${token.size})")

            // ADB treats the 20-byte token as an ALREADY-HASHED SHA-1 value.
            // We must NOT hash it again. We use NONEwithRSA and manually prepend
            // the SHA-1 DigestInfo ASN.1 header to create proper PKCS#1 v1.5 padding.
            //
            // DigestInfo for SHA-1: 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14
            // followed by the 20-byte hash value.
            val sha1DigestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
            )
            val digestInfoWithToken = sha1DigestInfo + token

            val signature = java.security.Signature.getInstance("NONEwithRSA").apply {
                initSign(cachedPrivateKey)
                update(digestInfoWithToken)
            }.sign()
            Log.d(TAG, "Sending AUTH_SIGNATURE (sig size=${signature.size}, prehashed)")

            // Send signature
            sendMessage(output, A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)

            // Read response — might be CNXN (key trusted) or AUTH (needs public key)
            response = readMessage(input)
                ?: throw Exception("No response after AUTH_SIGNATURE")

            Log.d(TAG, "After AUTH_SIGNATURE: response=0x${Integer.toHexString(response.command)} arg0=${response.arg0}")

            if (response.command == A_AUTH) {
                // Key not trusted yet — send our public key
                Log.i(TAG, "Signature not recognized — sending public key (check TV for 'Allow USB debugging' dialog)")
                Log.d(TAG, "Public key size=${cachedPublicKeyBytes!!.size}")
                sendMessage(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, cachedPublicKeyBytes!!)

                // Wait longer for user to accept the dialog on TV
                socket.soTimeout = 30000
                response = readMessage(input)
                    ?: throw Exception("Timeout waiting for auth acceptance")
                socket.soTimeout = TIMEOUT_MS // Reset timeout
            }
        }

        if (response.command != A_CNXN) {
            socket.close()
            throw Exception("ADB connection not established. Response: 0x${Integer.toHexString(response.command)}")
        }

        Log.i(TAG, "ADB persistent connection established!")
        persistentSocket = socket
        persistentOutput = output
        persistentInput = input
        nextLocalId = 1
    }

    /**
     * Send a single shell command over the existing persistent connection.
     * Opens a shell stream, waits for OKAY, then closes the stream.
     *
     * Handles stream multiplexing: if we receive messages from previous streams
     * (CLSE/WRTE with different arg1), we acknowledge them and keep reading
     * until we get the response for OUR stream.
     */
    private fun trySendCommand(command: String): Boolean {
        val output = persistentOutput ?: return false
        val input = persistentInput ?: return false
        val socket = persistentSocket ?: return false

        return try {
            val localId = nextLocalId++

            // Open shell
            val shellCmd = "shell:$command\u0000"
            sendMessage(output, A_OPEN, localId, 0, shellCmd.toByteArray())

            // Read messages until we get OKAY/CLSE for OUR stream
            socket.soTimeout = TIMEOUT_MS
            var gotOkay = false
            var remoteId = 0
            var attempts = 0

            while (attempts < 10) {
                attempts++
                val msg = readMessage(input) ?: return false

                // Check if this message is for our stream (arg1 == localId for OKAY/CLSE)
                if (msg.command == A_OKAY && msg.arg1 == localId) {
                    gotOkay = true
                    remoteId = msg.arg0
                    break
                }

                // Handle stale messages from previous streams
                if (msg.command == A_CLSE) {
                    // Send CLSE back to acknowledge (use msg.arg1 as localId, msg.arg0 as remoteId)
                    try {
                        sendMessage(output, A_CLSE, msg.arg1, msg.arg0, ByteArray(0))
                    } catch (_: Exception) {}
                    Log.d(TAG, "Drained stale CLSE for stream ${msg.arg1}")
                    continue
                }
                if (msg.command == A_WRTE) {
                    // Acknowledge the data from a previous stream
                    try {
                        sendMessage(output, A_OKAY, msg.arg1, msg.arg0, ByteArray(0))
                    } catch (_: Exception) {}
                    Log.d(TAG, "Drained stale WRTE for stream ${msg.arg1}")
                    continue
                }

                // Unexpected message
                Log.w(TAG, "ADB unexpected msg while waiting for OKAY: 0x${Integer.toHexString(msg.command)} arg0=${msg.arg0} arg1=${msg.arg1}")
                return false
            }

            if (gotOkay) {
                Log.d(TAG, "ADB shell command succeeded: $command")
                // Drain output from THIS stream (WRTE/CLSE with arg0 == remoteId)
                try {
                    socket.soTimeout = 500
                    while (true) {
                        val msg = readMessage(input) ?: break
                        if (msg.command == A_WRTE && msg.arg0 == remoteId) {
                            sendMessage(output, A_OKAY, localId, remoteId, ByteArray(0))
                        } else if (msg.command == A_CLSE && msg.arg0 == remoteId) {
                            sendMessage(output, A_CLSE, localId, remoteId, ByteArray(0))
                            break
                        } else if (msg.command == A_CLSE || msg.command == A_WRTE) {
                            // Stale message from another stream — drain it
                            if (msg.command == A_WRTE) {
                                sendMessage(output, A_OKAY, msg.arg1, msg.arg0, ByteArray(0))
                            } else {
                                sendMessage(output, A_CLSE, msg.arg1, msg.arg0, ByteArray(0))
                            }
                        } else {
                            break
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // No more output — close our end
                    try { sendMessage(output, A_CLSE, localId, remoteId, ByteArray(0)) } catch (_: Exception) {}
                }
                socket.soTimeout = TIMEOUT_MS
                true
            } else {
                Log.w(TAG, "ADB: didn't get OKAY for stream $localId after $attempts messages")
                false
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "ADB command timeout: $command")
            false
        } catch (e: Exception) {
            Log.w(TAG, "ADB command error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Close the persistent connection and clean up.
     */
    private fun closeConnection() {
        try { persistentSocket?.close() } catch (_: Exception) {}
        persistentSocket = null
        persistentOutput = null
        persistentInput = null
        nextLocalId = 1
    }

    // --- ADB Protocol helpers ---

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray?)

    private fun sendMessage(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteArray(24)
        writeInt(header, 0, command)
        writeInt(header, 4, arg0)
        writeInt(header, 8, arg1)
        writeInt(header, 12, data.size)
        writeInt(header, 16, checksum(data))
        writeInt(header, 20, command.inv())
        output.write(header)
        if (data.isNotEmpty()) output.write(data)
        output.flush()
    }

    private fun readMessage(input: InputStream): AdbMessage? {
        val header = ByteArray(24)
        var read = 0
        while (read < 24) {
            val n = input.read(header, read, 24 - read)
            if (n <= 0) return null
            read += n
        }
        val command = readInt(header, 0)
        val arg0 = readInt(header, 4)
        val arg1 = readInt(header, 8)
        val dataLen = readInt(header, 12)
        var data: ByteArray? = null
        if (dataLen > 0 && dataLen < MAX_PAYLOAD * 4) {
            data = ByteArray(dataLen)
            read = 0
            while (read < dataLen) {
                val n = input.read(data, read, dataLen - read)
                if (n <= 0) break
                read += n
            }
        }
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = (v shr 8 and 0xFF).toByte()
        buf[off + 2] = (v shr 16 and 0xFF).toByte()
        buf[off + 3] = (v shr 24 and 0xFF).toByte()
    }

    private fun readInt(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
                (buf[off + 1].toInt() and 0xFF shl 8) or
                (buf[off + 2].toInt() and 0xFF shl 16) or
                (buf[off + 3].toInt() and 0xFF shl 24)
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }
}
