package com.mcsfeb.tvalarmclock.service

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
 * SETUP (one-time per device):
 *   1. Connect computer to TV via USB/WiFi ADB
 *   2. Run: adb tcpip 5555
 *   3. On first use, the TV will show "Allow USB debugging?" - tap "Always allow"
 *
 * This handles ADB protocol including RSA authentication.
 */
object AdbShell {
    private const val TAG = "AdbShell"
    private const val ADB_PORT = 5555
    private const val TIMEOUT_MS = 5000

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

    private var cachedPrivateKey: java.security.PrivateKey? = null
    private var cachedPublicKeyBytes: ByteArray? = null

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

                // Format public key like ADB expects: base64 of RSA public key + " host@tv-alarm\n"
                val pubKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
                val pubKeyForAdb = "$pubKeyBase64 tv-alarm-clock@android\n"
                cachedPublicKeyBytes = pubKeyForAdb.toByteArray()
                pubFile.writeBytes(cachedPublicKeyBytes!!)

                Log.i(TAG, "Generated new ADB key pair")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ADB keys: ${e.message}")
        }
    }

    fun sendKeyEvent(keyCode: Int): Boolean {
        return sendShellCommand("input keyevent $keyCode")
    }

    fun sendShellCommand(command: String): Boolean {
        if (cachedPrivateKey == null) {
            Log.w(TAG, "ADB keys not initialized. Call AdbShell.init() first.")
            return false
        }

        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // Step 1: CNXN
            val identity = "host::\u0000"
            sendMessage(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, identity.toByteArray())

            // Step 2: Read response
            var response = readMessage(input) ?: run {
                Log.w(TAG, "No response from ADB")
                return false
            }

            // Step 3: Handle AUTH if required
            if (response.command == A_AUTH && response.arg0 == ADB_AUTH_TOKEN) {
                val token = response.data ?: ByteArray(0)

                // Sign the token with our private key
                val signature = java.security.Signature.getInstance("SHA1withRSA").apply {
                    initSign(cachedPrivateKey)
                    update(token)
                }.sign()

                // Send signature
                sendMessage(output, A_AUTH, ADB_AUTH_SIGNATURE, 0, signature)

                // Read response — might be CNXN (if key is trusted) or AUTH again
                response = readMessage(input) ?: run {
                    Log.w(TAG, "No response after AUTH_SIGNATURE")
                    return false
                }

                if (response.command == A_AUTH) {
                    // Key not trusted yet — send our public key
                    Log.i(TAG, "Sending public key for authorization (check TV for 'Allow USB debugging' dialog)")
                    sendMessage(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, cachedPublicKeyBytes!!)

                    // Wait longer for user to accept the dialog on TV
                    socket.soTimeout = 30000 // 30 second timeout for user interaction
                    response = readMessage(input) ?: run {
                        Log.w(TAG, "No response after sending public key. User may need to accept dialog on TV.")
                        return false
                    }
                }
            }

            if (response.command != A_CNXN) {
                Log.w(TAG, "ADB connection not established. Response: ${Integer.toHexString(response.command)}")
                return false
            }

            Log.d(TAG, "ADB connected! Sending shell command: $command")

            // Step 4: Open shell
            val shellCmd = "shell:$command\u0000"
            sendMessage(output, A_OPEN, 1, 0, shellCmd.toByteArray())

            // Step 5: Read OKAY
            val okResp = readMessage(input)
            val success = okResp?.command == A_OKAY
            if (success) {
                Log.d(TAG, "ADB shell command succeeded: $command")
            } else {
                Log.w(TAG, "ADB shell response: ${okResp?.command?.let { Integer.toHexString(it) } ?: "null"}")
            }

            // Close
            sendMessage(output, A_CLSE, 1, 0, ByteArray(0))
            success
        } catch (e: java.net.ConnectException) {
            Log.d(TAG, "ADB TCP not available (run 'adb tcpip 5555')")
            false
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "ADB TCP timeout — user may need to accept auth dialog")
            false
        } catch (e: Exception) {
            Log.w(TAG, "ADB shell failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
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

    private fun readMessage(input: java.io.InputStream): AdbMessage? {
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
