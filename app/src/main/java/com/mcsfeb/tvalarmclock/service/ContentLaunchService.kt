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

        // Step 3 & 4: Go HOME + force-stop for a clean state.
        // EXCEPTION: Sling and Disney+ skip both HOME and force-stop.
        //
        // WHY skip HOME for Sling/Disney+:
        // ADB key events have ~2-3s of connection latency on first use. If we send
        // HOME and then immediately call startActivity(), Sling launches before
        // the HOME event is processed. Then HOME arrives AFTER Sling opens and
        // pushes Sling to the background — killing the launch.
        //
        // WHY skip force-stop for Sling/Disney+:
        // Both use normal launch (auto-resume preferred). Force-stopping React
        // Native apps like Sling can corrupt their startup state and cause freezes.
        val skipCleanup = setOf("com.sling", "com.disney.disneyplus")
        if (packageName !in skipCleanup) {
            Log.i(TAG, "Step: Going HOME for clean state")
            sendKey(KeyEvent.KEYCODE_HOME, "HOME")
            delay(2000)

            Log.i(TAG, "Step: Force-stopping $packageName")
            sendShell("am force-stop $packageName")
            delay(1000)
        } else {
            Log.i(TAG, "Step: Skipping HOME + force-stop for $packageName (direct launch)")
        }

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
     * HBO MAX (Max) — TESTED Feb 2026 (3/3 pass)
     *
     * Two modes:
     * A) Deep link with UUID: deep link → 25s → CENTER (profile) → auto-plays specific content
     * B) Normal launch (APP_ONLY): normal launch → 25s → CENTER (profile) → CENTER (featured) → MEDIA_PLAY
     *
     * IMPORTANT: Old urn:hbo:episode format NO LONGER WORKS.
     * Max now uses UUID format: https://play.max.com/video/watch/{uuid1}/{uuid2}
     * Deep links always open the app but only navigate to content with correct UUID format.
     *
     * - App is completely opaque (WebView) — can't detect what's on screen
     * - MEDIA_PLAY guarantees playback in all cases
     */
    private suspend fun launchHboMax(deepLinkUri: String) {
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "HBO Max: Deep link + profile CENTER")
            if (!sendDeepLink("com.wbd.stream", deepLinkUri, emptyMap())) return
        } else {
            Log.i(TAG, "HBO Max: Normal launch + profile + play")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.wbd.stream")
                ?: packageManager.getLaunchIntentForPackage("com.wbd.stream")
            if (launchIntent == null) {
                Log.e(TAG, "HBO Max: App not installed!")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        // Wait for cold start (HBO is very slow — WebView-based)
        Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
        delay(25000)

        // CENTER #1: Select profile
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
        delay(8000)

        if (!hasDeepLink) {
            // Normal launch needs extra CENTER to select featured content
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play featured")
            delay(5000)
        }

        // MEDIA_PLAY guarantees playback regardless of state
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
        delay(3000)

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
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "Hulu: Deep link + 3x CENTER")
            if (!sendDeepLink("com.hulu.livingroomplus", deepLinkUri, extras)) return
        } else {
            Log.i(TAG, "Hulu: Normal launch (APP_ONLY)")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.hulu.livingroomplus")
                ?: packageManager.getLaunchIntentForPackage("com.hulu.livingroomplus")
            if (launchIntent == null) { Log.e(TAG, "Hulu: App not installed!"); return }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

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
     * DISNEY+ — TESTED Feb 2026 (3/3 pass, PIN removed)
     *
     * Recipe: Normal launch → wait 30s → CENTER (profile select → auto-plays)
     *
     * KEY FINDINGS from real testing:
     * - Deep links do NOT reliably navigate to content on Disney+ TV app
     * - Normal launch → profile select works perfectly (3/3 tests)
     * - Single CENTER after load selects profile AND starts auto-playing content
     * - DO NOT send multiple CENTERs — the second CENTER pauses/disrupts playback
     * - MEDIA_PLAY as final safety net to ensure playback
     * - If PIN is re-enabled, PIN pad entry logic is available below
     */
    private suspend fun launchDisneyPlus(deepLinkUri: String) {
        Log.i(TAG, "Disney+: Normal launch + profile select")

        // Always use normal launch — deep links are unreliable on Disney+ TV app
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.disney.disneyplus")
            ?: packageManager.getLaunchIntentForPackage("com.disney.disneyplus")
        if (launchIntent == null) {
            Log.e(TAG, "Disney+: App not installed!")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Wait for cold start (Disney+ is slow)
        Log.i(TAG, "Disney+: Waiting 30s for cold start...")
        delay(30000)

        // Check if PIN screen is needed
        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            Log.i(TAG, "Disney+: Attempting PIN entry (PIN is configured)")
            enterDisneyPinFromStartPosition(pin)
            delay(5000)
        } else {
            // No PIN — single CENTER selects profile and triggers auto-play
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ profile select")
            delay(8000)
        }

        // MEDIA_PLAY to guarantee playback
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "D+ force play")
        delay(3000)

        Log.i(TAG, "Disney+: Done")
    }

    /**
     * NETFLIX — TESTED Feb 2026
     *
     * Recipe: Deep link (nflx:// + source=30) → wait 15s → done
     * - Netflix auto-plays with source=30 extra
     * - No profile picker on this TV (deep link bypasses it)
     * - If profile picker appears, one CENTER press selects default
     * - APP_ONLY: normal launch (for Test Open or no content ID)
     */
    private suspend fun launchNetflix(deepLinkUri: String) {
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "Netflix: Deep link with source=30")
            val extras = mapOf("source" to "30")
            if (!sendDeepLink("com.netflix.ninja", deepLinkUri, extras)) return
        } else {
            Log.i(TAG, "Netflix: Normal launch (APP_ONLY)")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.netflix.ninja")
                ?: packageManager.getLaunchIntentForPackage("com.netflix.ninja")
            if (launchIntent == null) { Log.e(TAG, "Netflix: App not installed!"); return }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        Log.i(TAG, "Netflix: Waiting 15s for auto-play...")
        delay(15000)

        Log.i(TAG, "Netflix: Done")
    }

    /**
     * GENERIC — For YouTube, Paramount+, Prime Video, Tubi, etc.
     *
     * Recipe: Deep link → wait → done (if deep link available)
     *         Normal launch → wait → done (if APP_ONLY)
     * - These apps handle deep links cleanly with auto-play
     * - No profile bypass needed
     */
    private suspend fun launchGeneric(packageName: String, deepLinkUri: String, extras: Map<String, String>) {
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "Generic deep link launch for $packageName")
            if (!sendDeepLink(packageName, deepLinkUri, extras)) return
        } else {
            Log.i(TAG, "Generic normal launch for $packageName (no deep link)")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.e(TAG, "$packageName: App not installed!")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

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
     * Empty string = no PIN (profile has no PIN lock).
     * Set to PIN digits (e.g., "3472") if PIN is enabled.
     */
    private fun getDisneyPin(): String {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString("disney_pin", "") ?: ""
    }

    /**
     * Enter a Disney+ PIN starting from the PIN screen's initial focus position.
     *
     * PIN pad layout (tested on Onn Google TV):
     *   Row 0: [1] [2] [3]
     *   Row 1: [4] [5] [6]
     *   Row 2: [7] [8] [9]
     *   Row 3: [CANCEL] [0] [DELETE]
     *
     * Initial focus lands on "5" (row 1, col 1).
     * The PIN field auto-fills with "555" from initial focus.
     * Step 1: Navigate to DELETE (row 3, col 2) and clear
     * Step 2: Enter PIN digits from DELETE position
     */
    private suspend fun enterDisneyPinFromStartPosition(pin: String) {
        // Navigate from "5" (row 1, col 1) to DELETE (row 3, col 2): DOWN 2, RIGHT 1
        Log.i(TAG, "Disney+: Navigating to DELETE key to clear auto-entered digits")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ nav DOWN")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ nav DOWN")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ nav RIGHT")
        delay(500)

        // Press DELETE 4 times to clear any auto-entered digits
        repeat(4) {
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ DELETE #${it+1}")
            delay(500)
        }
        delay(500)

        // Enter the PIN from DELETE position (row 3, col 2)
        Log.i(TAG, "Disney+: Entering PIN (${pin.length} digits)")
        enterDisneyPin(pin)
    }

    /**
     * Navigate the Disney+ PIN pad and enter digits.
     * Called from DELETE position (row=3, col=2).
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
                    delay(300)
                }
            } else if (rowDiff < 0) {
                repeat(-rowDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_UP, "PIN nav UP")
                    delay(300)
                }
            }

            // Navigate horizontally
            val colDiff = target.col - currentCol
            if (colDiff > 0) {
                repeat(colDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PIN nav RIGHT")
                    delay(300)
                }
            } else if (colDiff < 0) {
                repeat(-colDiff) {
                    sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "PIN nav LEFT")
                    delay(300)
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
