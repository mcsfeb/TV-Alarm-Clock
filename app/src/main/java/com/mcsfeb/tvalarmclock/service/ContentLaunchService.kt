package com.mcsfeb.tvalarmclock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.*

/**
 * ContentLaunchService - Foreground service that reliably launches streaming content.
 *
 * DESIGN PRINCIPLES (learned from real TV testing Feb 2026):
 *
 * 1. CLEAN STATE FIRST: Before launching ANY app, go HOME and force-stop the target.
 *    This ensures a consistent cold-start every time.
 *
 * 2. MINIMAL ACTIONS: Only send the absolute minimum key events needed.
 *    Extra DPAD_CENTER presses cause apps to freeze or navigate away.
 *
 * 3. VOLUME CONTROL: Set TV volume before launching the app so audio is ready.
 *
 * 4. PER-APP RECIPES: Each app has its own tested launch recipe.
 *    No generic "profile bypass" — each app is different.
 */
class ContentLaunchService : Service() {

    companion object {
        private const val TAG = "ContentLaunchSvc"
        private const val CHANNEL_ID = "content_launch_channel"
        private const val NOTIFICATION_ID = 42

        fun launch(
            context: Context,
            packageName: String,
            deepLinkUri: String,
            extras: Map<String, String> = emptyMap(),
            volume: Int = -1
        ) {
            val intent = Intent(context, ContentLaunchService::class.java).apply {
                putExtra("PACKAGE_NAME", packageName)
                putExtra("DEEP_LINK_URI", deepLinkUri)
                putExtra("VOLUME", volume)
                for ((k, v) in extras) {
                    putExtra("EXTRA_$k", v)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "ContentLaunchService started as foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val deepLinkUri = intent.getStringExtra("DEEP_LINK_URI") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val volume = intent.getIntExtra("VOLUME", -1)

        // Collect extras
        val extras = mutableMapOf<String, String>()
        intent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                if (key.startsWith("EXTRA_")) {
                    extras[key.removePrefix("EXTRA_")] = bundle.getString(key, "")
                }
            }
        }

        serviceScope.launch {
            performLaunch(packageName, deepLinkUri, extras, volume)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    // =========================================================================
    //  MASTER LAUNCH SEQUENCE
    // =========================================================================

    /**
     * The complete, reliable launch sequence for any streaming app.
     *
     * Every launch follows these steps:
     * 1. Initialize ADB connection (needed for key events and shell commands)
     * 2. Set volume (if specified)
     * 3. Go HOME to get a clean starting state
     * 4. Force-stop the target app (ensures clean cold start)
     * 5. Run the app-specific launch recipe
     */
    private suspend fun performLaunch(
        packageName: String,
        deepLinkUri: String,
        extras: Map<String, String>,
        volume: Int
    ) {
        Log.i(TAG, "=== LAUNCH START: $packageName ===")
        Log.i(TAG, "Deep link: $deepLinkUri")

        // Step 1: Initialize ADB connection (we'll need it for key events)
        withContext(Dispatchers.IO) {
            AdbShell.init(this@ContentLaunchService)
        }

        // Step 2: Set volume BEFORE launching app
        if (volume >= 0) {
            setTvVolume(volume)
        }

        // Step 3: Go HOME for a clean state
        Log.i(TAG, "Step: Going HOME for clean state")
        sendKey(KeyEvent.KEYCODE_HOME, "HOME")
        delay(2000)

        // Step 4: Force-stop the target app for a clean cold start
        Log.i(TAG, "Step: Force-stopping $packageName")
        sendShell("am force-stop $packageName")
        delay(1000)

        // Step 5: Run the app-specific launch recipe
        when (packageName) {
            "com.sling" -> launchSling()
            "com.hulu.livingroomplus" -> launchHulu(deepLinkUri, extras)
            "com.wbd.stream" -> launchHboMax(deepLinkUri)
            "com.disney.disneyplus" -> launchDisneyPlus(deepLinkUri)
            "com.netflix.ninja" -> launchNetflix(deepLinkUri)
            else -> launchGeneric(packageName, deepLinkUri, extras)
        }

        Log.i(TAG, "=== LAUNCH COMPLETE: $packageName ===")
    }

    // =========================================================================
    //  APP-SPECIFIC LAUNCH RECIPES
    //  Each recipe is tested and minimal — no extra actions that could cause freezes.
    // =========================================================================

    /**
     * SLING TV — TESTED Feb 2026
     *
     * Recipe: Normal launch → wait 35s → CENTER (profile) → wait → MEDIA_PLAY
     * - Sling auto-plays the last watched channel on launch
     * - Deep links are BROKEN (cold start = stuck player, warm = stops playback)
     * - Cold start shows profile picker that blocks playback
     * - CENTER dismisses the profile picker
     * - IMPORTANT: Don't use multiple CENTERs — CENTER toggles play/pause on the player!
     *   Using even CENTERs = paused. Using MEDIA_PLAY always forces playback.
     * - Final MEDIA_PLAY guarantees playback regardless of state
     */
    private suspend fun launchSling() {
        Log.i(TAG, "Sling: Normal launch + CENTER + MEDIA_PLAY")

        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.sling")
            ?: packageManager.getLaunchIntentForPackage("com.sling")
        if (launchIntent == null) {
            Log.e(TAG, "Sling: App not installed!")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Wait for Sling to fully load (React Native, slow cold start)
        Log.i(TAG, "Sling: Waiting 35s for load...")
        delay(35000)

        // CENTER to dismiss profile picker (if shown)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling profile dismiss")
        delay(5000)

        // MEDIA_PLAY to guarantee playback starts
        // Unlike CENTER (which toggles play/pause), MEDIA_PLAY always plays
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling force play")
        delay(3000)

        Log.i(TAG, "Sling: Done (playing last channel)")
    }

    /**
     * HBO MAX — TESTED Feb 2026
     *
     * Recipe: Deep link → wait 30s → CENTER → wait 5s → CENTER
     * - Deep link goes to content but profile picker appears first
     * - App is completely opaque (WebView) — can't detect what's on screen
     * - First CENTER selects the default profile
     * - Second CENTER may be needed to dismiss a dialog or start playback
     * - DO NOT re-send the deep link — HBO remembers it after profile selection
     */
    private suspend fun launchHboMax(deepLinkUri: String) {
        Log.i(TAG, "HBO Max: Deep link + 2x CENTER for profile bypass")

        // Send the deep link
        if (!sendDeepLink("com.wbd.stream", deepLinkUri, emptyMap())) return

        // Wait for cold start (HBO is very slow — WebView-based)
        Log.i(TAG, "HBO Max: Waiting 30s for cold start...")
        delay(30000)

        // Profile bypass: 2 CENTER presses
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile #1")
        delay(5000)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile #2")
        delay(5000)

        Log.i(TAG, "HBO Max: Done")
    }

    /**
     * HULU — TESTED Feb 2026
     *
     * Recipe: Force-stop → deep link → wait 25s → CENTER → wait 3s → CENTER → wait 10s → CENTER
     * - Hulu ignores deep links if app is already running (must force-stop first)
     * - Deep link lands on show page, not player
     * - First CENTER: profile bypass
     * - Second CENTER: selects show/episode
     * - Third CENTER: starts playback
     * - Force-stop is done in the master sequence, so we skip it here
     */
    private suspend fun launchHulu(deepLinkUri: String, extras: Map<String, String>) {
        Log.i(TAG, "Hulu: Deep link + 3x CENTER")

        // Send the deep link
        if (!sendDeepLink("com.hulu.livingroomplus", deepLinkUri, extras)) return

        // Wait for Hulu cold start
        Log.i(TAG, "Hulu: Waiting 25s for cold start...")
        delay(25000)

        // Profile bypass + start playback
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu profile")
        delay(3000)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu select")
        delay(10000)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu play")
        delay(3000)

        Log.i(TAG, "Hulu: Done")
    }

    /**
     * DISNEY+ — TESTED Feb 2026
     *
     * Recipe: Deep link → wait 28s → clear auto-entered digits → enter PIN
     *
     * KEY FINDINGS from real testing:
     * - Disney+ goes straight to PIN screen (no profile picker first)
     * - Focus starts on digit "5" (row 1, col 1)
     * - The PIN field auto-fills with "555" on load (3 digits entered by initial focus)
     * - Must navigate to DELETE and clear first, then enter the real PIN
     * - PIN pad: [1][2][3] / [4][5][6] / [7][8][9] / [CANCEL][0][DELETE]
     */
    private suspend fun launchDisneyPlus(deepLinkUri: String) {
        Log.i(TAG, "Disney+: Deep link + PIN entry")

        // Send the deep link
        if (!sendDeepLink("com.disney.disneyplus", deepLinkUri, emptyMap())) return

        // Wait for cold start (Disney+ is slow)
        Log.i(TAG, "Disney+: Waiting 28s for cold start + PIN screen...")
        delay(28000)

        // PIN screen appears with focus on "5" (row 1, col 1)
        // PIN field has auto-entered "555" from the initial focus
        // Step 1: Navigate to DELETE (row 3, col 2) from "5" (row 1, col 1): DOWN 2, RIGHT 1
        Log.i(TAG, "Disney+: Navigating to DELETE key to clear auto-entered digits")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ nav DOWN")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ nav DOWN")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ nav RIGHT")
        delay(500)

        // Step 2: Press DELETE 4 times to clear any auto-entered digits
        repeat(4) {
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ DELETE #${it+1}")
            delay(500)
        }
        delay(500)

        // Step 3: Enter the PIN from DELETE position (row 3, col 2)
        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            Log.i(TAG, "Disney+: Entering PIN (${pin.length} digits)")
            enterDisneyPin(pin)
            delay(5000)
        }

        Log.i(TAG, "Disney+: Done")
    }

    /**
     * NETFLIX — TESTED Feb 2026
     *
     * Recipe: Deep link (nflx:// + source=30) → wait 15s → done
     * - Netflix auto-plays with source=30 extra
     * - No profile picker on this TV (deep link bypasses it)
     * - If profile picker appears, one CENTER press selects default
     */
    private suspend fun launchNetflix(deepLinkUri: String) {
        Log.i(TAG, "Netflix: Deep link with source=30")

        val extras = mapOf("source" to "30")
        if (!sendDeepLink("com.netflix.ninja", deepLinkUri, extras)) return

        Log.i(TAG, "Netflix: Waiting 15s for auto-play...")
        delay(15000)

        Log.i(TAG, "Netflix: Done")
    }

    /**
     * GENERIC — For YouTube, Paramount+, Prime Video, Tubi, etc.
     *
     * Recipe: Deep link → wait → done
     * - These apps handle deep links cleanly with auto-play
     * - No profile bypass needed
     */
    private suspend fun launchGeneric(packageName: String, deepLinkUri: String, extras: Map<String, String>) {
        Log.i(TAG, "Generic launch for $packageName")

        if (!sendDeepLink(packageName, deepLinkUri, extras)) return

        val waitTime = getAppLoadWaitTime(packageName)
        Log.i(TAG, "Waiting ${waitTime}ms for $packageName...")
        delay(waitTime)

        Log.i(TAG, "Generic launch done for $packageName")
    }

    // =========================================================================
    //  DISNEY+ PIN ENTRY
    // =========================================================================

    /**
     * Get the Disney+ PIN from SharedPreferences.
     * Stored when user configures Disney+ content in the alarm.
     */
    private fun getDisneyPin(): String {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString("disney_pin", "3472") ?: ""
    }

    /**
     * Enter a Disney+ PIN on the PIN pad.
     *
     * PIN pad layout (tested on Onn Google TV):
     *   Row 0: [1] [2] [3]
     *   Row 1: [4] [5] [6]
     *   Row 2: [7] [8] [9]
     *   Row 3: [CANCEL] [0] [DELETE]
     *
     * Called after clearing the PIN field, so focus is on DELETE (row=3, col=2).
     */
    private suspend fun enterDisneyPin(pin: String) {
        data class Pos(val row: Int, val col: Int)

        val digitPositions = mapOf(
            '1' to Pos(0, 0), '2' to Pos(0, 1), '3' to Pos(0, 2),
            '4' to Pos(1, 0), '5' to Pos(1, 1), '6' to Pos(1, 2),
            '7' to Pos(2, 0), '8' to Pos(2, 1), '9' to Pos(2, 2),
            '0' to Pos(3, 1)
        )

        // Start from DELETE position (row 3, col 2)
        var currentRow = 3
        var currentCol = 2

        for ((i, digit) in pin.withIndex()) {
            val target = digitPositions[digit] ?: continue

            // Navigate vertically
            val rowDiff = target.row - currentRow
            if (rowDiff > 0) {
                repeat(rowDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PIN nav DOWN")
                    delay(500)
                }
            } else if (rowDiff < 0) {
                repeat(-rowDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_UP, "PIN nav UP")
                    delay(500)
                }
            }

            // Navigate horizontally
            val colDiff = target.col - currentCol
            if (colDiff > 0) {
                repeat(colDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PIN nav RIGHT")
                    delay(500)
                }
            } else if (colDiff < 0) {
                repeat(-colDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "PIN nav LEFT")
                    delay(500)
                }
            }

            // Press the digit
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PIN digit ${i + 1}: $digit")
            delay(500)

            currentRow = target.row
            currentCol = target.col
        }
    }

    // =========================================================================
    //  VOLUME CONTROL
    // =========================================================================

    /**
     * Set the TV volume to a specific level (0-100).
     * Uses AudioManager for the device's own volume, which controls HDMI-CEC
     * volume on most Android TV devices.
     */
    private fun setTvVolume(targetVolume: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val scaledVolume = (targetVolume * maxVolume / 100).coerceIn(0, maxVolume)

            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                scaledVolume,
                0 // No UI flag — don't show volume bar on screen
            )
            Log.i(TAG, "Volume set to $targetVolume% (device level: $scaledVolume/$maxVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set volume: ${e.message}")
        }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** Send a single key event via ADB shell. */
    private suspend fun sendKey(keyCode: Int, label: String) {
        withContext(Dispatchers.IO) {
            try {
                val sent = AdbShell.sendKeyEvent(keyCode)
                Log.i(TAG, "$label: success=$sent")
            } catch (e: Exception) {
                Log.w(TAG, "$label failed: ${e.message}")
            }
        }
    }

    /** Send a shell command via ADB. */
    private suspend fun sendShell(command: String) {
        withContext(Dispatchers.IO) {
            try {
                AdbShell.sendShellCommand(command)
            } catch (e: Exception) {
                Log.w(TAG, "Shell command failed ($command): ${e.message}")
            }
        }
    }

    /** Send a deep link intent to a streaming app. */
    private fun sendDeepLink(packageName: String, deepLinkUri: String, extras: Map<String, String>): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                for ((k, v) in extras) {
                    putExtra(k, v)
                }
            }
            startActivity(intent)
            Log.i(TAG, "Deep link sent: $deepLinkUri -> $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Deep link failed for $packageName: ${e.message}")
            false
        }
    }

    /** Per-app cold start wait times (tested on Onn Google TV Feb 2026). */
    private fun getAppLoadWaitTime(packageName: String): Long = when (packageName) {
        "com.google.android.youtube.tv" -> 12000  // YouTube: fast
        "com.netflix.ninja" -> 15000              // Netflix: Gibbon renderer
        "com.cbs.ott" -> 18000                    // Paramount+: moderate
        "com.amazon.amazonvideo.livingroom" -> 18000 // Prime Video: moderate
        "com.tubitv" -> 15000                     // Tubi: moderate
        else -> 20000                             // Default: safe fallback
    }

    // =========================================================================
    //  NOTIFICATION BOILERPLATE
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Content Launch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Launching streaming content"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Launching content...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Launching content...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "ContentLaunchService destroyed")
    }
}
