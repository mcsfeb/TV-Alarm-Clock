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
 * 2. SEARCH IS THE FALLBACK: When deep links fail or aren't available, use the
 *    "virtual keyboard" approach: KEYCODE_SEARCH + `input text <query>` via ADB.
 *    This types directly into the app's search field without navigating the on-screen
 *    keyboard. Works for Sling channels, HBO shows, Disney+ content, etc.
 *
 * 3. MINIMAL ACTIONS: Only send the absolute minimum key events needed.
 *    Extra DPAD_CENTER presses cause apps to freeze or navigate away.
 *
 * 4. GENEROUS TIMEOUTS: Cold starts on Android TV can be 40+ seconds for WebView
 *    apps (HBO Max, Hulu). Always wait long enough before sending key events.
 *
 * 5. VOLUME CONTROL: Set TV volume via both AudioManager AND ADB media command
 *    so it works whether audio goes through internal or HDMI output.
 *
 * 6. PER-APP RECIPES: Each app has its own tested launch recipe.
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

        // Collect extras (channel name, search query, etc.)
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
        Log.i(TAG, "Extras: $extras")

        // Step 1: Initialize ADB connection (we'll need it for key events)
        withContext(Dispatchers.IO) {
            AdbShell.init(this@ContentLaunchService)
        }

        // Step 2: Set volume BEFORE launching app (so audio is ready)
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
        // Pull channel name and search query from extras
        val channelName = extras["channelName"] ?: ""
        val searchQuery = extras["searchQuery"] ?: ""

        when (packageName) {
            "com.sling" -> launchSling(channelName)
            "com.hulu.livingroomplus" -> launchHulu(deepLinkUri, extras)
            "com.wbd.stream" -> launchHboMax(deepLinkUri, searchQuery)
            "com.disney.disneyplus" -> launchDisneyPlus(deepLinkUri, searchQuery)
            "com.netflix.ninja" -> launchNetflix(deepLinkUri)
            else -> launchGeneric(packageName, deepLinkUri, extras)
        }

        Log.i(TAG, "=== LAUNCH COMPLETE: $packageName ===")
    }

    // =========================================================================
    //  APP-SPECIFIC LAUNCH RECIPES
    // =========================================================================

    /**
     * SLING TV — TESTED Feb 2026 / Updated with channel search
     *
     * Recipe:
     *   Normal launch → wait 45s (React Native cold start) → CENTER (profile dismiss)
     *   → IF channel name known: searchAndLaunchContent(channelName)
     *   → ELSE: MEDIA_PLAY (resumes last channel)
     *
     * WHY SEARCH: Deep links break Sling's ExoPlayer on cold start.
     * Instead: launch normally, wait, then use KEYCODE_SEARCH + input text to
     * navigate to the specific channel the user chose.
     *
     * WHY 45s: Sling is a React Native app. On Onn Google TV, cold start (including
     * JS bundle load) takes 35–45s before the profile screen appears.
     */
    private suspend fun launchSling(channelName: String) {
        Log.i(TAG, "Sling: Normal launch (channel='$channelName')")

        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.sling")
            ?: packageManager.getLaunchIntentForPackage("com.sling")
        if (launchIntent == null) {
            Log.e(TAG, "Sling: App not installed!")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Wait for Sling to fully load (React Native, very slow cold start)
        Log.i(TAG, "Sling: Waiting 45s for load...")
        delay(45000)

        // CENTER to dismiss profile picker (if shown)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling profile dismiss")
        delay(5000)

        if (channelName.isNotBlank()) {
            // Navigate to the specific channel the user chose via search
            Log.i(TAG, "Sling: Searching for channel '$channelName'")
            searchAndLaunchContent(channelName, isLiveTV = true)
        } else {
            // No specific channel — just force-play whatever Sling auto-selects
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling force play")
            delay(3000)
            Log.i(TAG, "Sling: Done (playing last channel)")
        }
    }

    /**
     * HBO MAX (Max) — TESTED Feb 2026 / Updated with search support
     *
     * Two modes:
     * A) Deep link with UUID: deep link → 35s → CENTER (profile) → auto-plays specific content
     * B) Normal launch with search: normal → 35s → CENTER (profile) → search for show → play
     * C) Normal launch (no search query): normal → 35s → CENTER (profile) → CENTER (featured) → MEDIA_PLAY
     *
     * WHY 35s: HBO Max is WebView-based, very slow cold start. 25s was not enough.
     *
     * NOTE: UUID format = https://play.max.com/video/watch/{uuid1}/{uuid2}
     * Old urn:hbo:episode format no longer works.
     */
    private suspend fun launchHboMax(deepLinkUri: String, searchQuery: String) {
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "HBO Max: Deep link launch")
            if (!sendDeepLink("com.wbd.stream", deepLinkUri, emptyMap())) return
        } else {
            Log.i(TAG, "HBO Max: Normal launch (search='$searchQuery')")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.wbd.stream")
                ?: packageManager.getLaunchIntentForPackage("com.wbd.stream")
            if (launchIntent == null) {
                Log.e(TAG, "HBO Max: App not installed!")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        // Wait for cold start — HBO Max WebView is very slow, 35s is safer than 25s
        Log.i(TAG, "HBO Max: Waiting 35s for cold start...")
        delay(35000)

        // CENTER #1: Select profile
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
        delay(8000)

        if (!hasDeepLink) {
            if (searchQuery.isNotBlank()) {
                // Search for the specific show the user chose
                Log.i(TAG, "HBO Max: Searching for '$searchQuery'")
                searchAndLaunchContent(searchQuery, isLiveTV = false)
            } else {
                // No specific content — play whatever is featured
                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play featured")
                delay(5000)
                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
                delay(3000)
            }
        } else {
            // Deep link: content should auto-play after profile select
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO ensure play")
            delay(3000)
        }

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
     * DISNEY+ — TESTED Feb 2026 / Updated with search support and longer timeout
     *
     * Recipe:
     *   Normal launch → wait 40s → profile select (CENTER or PIN)
     *   → IF search query: searchAndLaunchContent(searchQuery)
     *   → ELSE: MEDIA_PLAY (auto-plays Continue Watching)
     *
     * WHY SEARCH: Deep links don't reliably navigate on Disney+ TV app.
     * After profile selection, search for the specific show the user chose.
     *
     * WHY 40s: Disney+ cold start can take 35-40s on Onn Google TV.
     * The 30s wait was causing profile press to fire before the screen appeared.
     *
     * DO NOT send multiple CENTERs — the second CENTER pauses playback.
     * MEDIA_PLAY is the safe way to ensure playback starts.
     */
    private suspend fun launchDisneyPlus(deepLinkUri: String, searchQuery: String) {
        Log.i(TAG, "Disney+: Normal launch (search='$searchQuery')")

        // Always use normal launch — deep links are unreliable on Disney+ TV app
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.disney.disneyplus")
            ?: packageManager.getLaunchIntentForPackage("com.disney.disneyplus")
        if (launchIntent == null) {
            Log.e(TAG, "Disney+: App not installed!")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        // Wait for cold start — increased from 30s to 40s for reliability
        Log.i(TAG, "Disney+: Waiting 40s for cold start...")
        delay(40000)

        // Handle profile selection (with or without PIN)
        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            Log.i(TAG, "Disney+: Attempting PIN entry")
            enterDisneyPinFromStartPosition(pin)
            delay(5000)
        } else {
            // Single CENTER selects profile
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ profile select")
            delay(8000)
        }

        if (searchQuery.isNotBlank()) {
            // Search for the specific show the user chose
            Log.i(TAG, "Disney+: Searching for '$searchQuery'")
            searchAndLaunchContent(searchQuery, isLiveTV = false)
        } else {
            // No specific content — MEDIA_PLAY starts auto-play (Continue Watching)
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "D+ force play")
            delay(3000)
            Log.i(TAG, "Disney+: Done (auto-play)")
        }
    }

    /**
     * NETFLIX — TESTED Feb 2026
     *
     * Recipe: Deep link (nflx:// + source=30) → wait 15s → done
     * - Netflix auto-plays with source=30 extra
     * - No profile picker on this TV (deep link bypasses it)
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
    //  VIRTUAL KEYBOARD / SEARCH NAVIGATION
    // =========================================================================

    /**
     * Search for content by name using ADB text injection.
     *
     * This is the "virtual keyboard" approach: instead of navigating the on-screen
     * keyboard key by key, we use `adb shell input text <query>` to type directly
     * into whatever search field is focused. This bypasses all keyboard navigation
     * and works even with complex on-screen keyboards.
     *
     * Flow:
     *   1. KEYCODE_SEARCH (84) — opens the app's search UI
     *   2. `input text <query>` — types the text directly into the search field
     *   3. KEYCODE_ENTER (66) — submits the search
     *   4. DPAD_DOWN x2 — moves focus to the first result
     *   5. DPAD_CENTER (23) — opens the selected result
     *   6. DPAD_CENTER (23) again — for live TV: selects "Watch Live" or confirms
     *   7. KEYCODE_MEDIA_PLAY — ensures playback starts
     *
     * @param query The channel name (e.g., "ESPN") or show name (e.g., "Succession")
     * @param isLiveTV True for live TV channels (needs extra confirm step)
     */
    private suspend fun searchAndLaunchContent(query: String, isLiveTV: Boolean) {
        Log.i(TAG, "searchAndLaunchContent: '$query' (isLiveTV=$isLiveTV)")

        // Step 1: Open search (KEYCODE_SEARCH = 84)
        sendShell("input keyevent 84")
        delay(2500)

        // Step 2: Type the query directly into the search field
        // Replace spaces with %s (Android input command's space escape)
        // This avoids quoting issues when passing through ADB shell
        val inputText = query.trim().replace(" ", "%s")
        Log.i(TAG, "Typing search text: '$inputText'")
        sendShell("input text $inputText")
        delay(3000)

        // Step 3: Submit search (KEYCODE_ENTER = 66)
        sendShell("input keyevent 66")
        delay(3000)

        // Step 4: Navigate down to first result
        // First DOWN exits the search bar, second DOWN reaches results
        sendShell("input keyevent 20")  // DPAD_DOWN
        delay(500)
        sendShell("input keyevent 20")  // DPAD_DOWN
        delay(500)

        // Step 5: Select the first result
        sendShell("input keyevent 23")  // DPAD_CENTER
        delay(3000)

        // Step 6: For live TV, there's often a "Watch Live" or "Play" option to confirm
        if (isLiveTV) {
            sendShell("input keyevent 23")  // DPAD_CENTER — confirms "Watch Live"
            delay(2000)
        }

        // Step 7: Ensure playback starts
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Search: force play")
        delay(2000)

        Log.i(TAG, "searchAndLaunchContent: Done")
    }

    // =========================================================================
    //  DISNEY+ PIN ENTRY
    // =========================================================================

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
     * Step 1: Navigate to DELETE (row 3, col 2) and clear auto-entered digits
     * Step 2: Enter PIN digits from DELETE position
     */
    private suspend fun enterDisneyPinFromStartPosition(pin: String) {
        Log.i(TAG, "Disney+: Navigating to DELETE key to clear auto-entered digits")
        // Navigate from "5" (row 1, col 1) to DELETE (row 3, col 2): DOWN 2, RIGHT 1
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

        Log.i(TAG, "Disney+: Entering PIN (${pin.length} digits)")
        enterDisneyPin(pin)
    }

    private suspend fun enterDisneyPin(pin: String) {
        data class Pos(val row: Int, val col: Int)

        val digitPositions = mapOf(
            '1' to Pos(0, 0), '2' to Pos(0, 1), '3' to Pos(0, 2),
            '4' to Pos(1, 0), '5' to Pos(1, 1), '6' to Pos(1, 2),
            '7' to Pos(2, 0), '8' to Pos(2, 1), '9' to Pos(2, 2),
            '0' to Pos(3, 1)
        )

        var currentRow = 3
        var currentCol = 2

        for ((i, digit) in pin.withIndex()) {
            val target = digitPositions[digit] ?: continue

            val rowDiff = target.row - currentRow
            if (rowDiff > 0) {
                repeat(rowDiff) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PIN nav DOWN"); delay(300) }
            } else if (rowDiff < 0) {
                repeat(-rowDiff) { sendKey(KeyEvent.KEYCODE_DPAD_UP, "PIN nav UP"); delay(300) }
            }

            val colDiff = target.col - currentCol
            if (colDiff > 0) {
                repeat(colDiff) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PIN nav RIGHT"); delay(300) }
            } else if (colDiff < 0) {
                repeat(-colDiff) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "PIN nav LEFT"); delay(300) }
            }

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
     *
     * Uses TWO methods to ensure the volume actually changes:
     *
     * Method 1: AudioManager.setStreamVolume() — controls Android's software volume.
     *   Works for internal audio output and most HDMI setups.
     *
     * Method 2: ADB `media volume --stream 3 --set N` — runs via ADB shell with
     *   elevated permissions, bypasses any app-level restrictions. Stream 3 = MUSIC.
     *
     * If AudioManager fails (e.g., restricted by the OS), the ADB command is the backup.
     */
    private suspend fun setTvVolume(targetVolume: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val scaledVolume = (targetVolume * maxVolume / 100).coerceIn(0, maxVolume)

        // Method 1: AudioManager (fastest, no ADB required)
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                scaledVolume,
                0 // No UI — don't show volume bar on screen
            )
            Log.i(TAG, "Volume: AudioManager set $targetVolume% → level $scaledVolume/$maxVolume")
        } catch (e: Exception) {
            Log.w(TAG, "Volume: AudioManager failed: ${e.message}")
        }

        // Method 2: ADB media volume command (works even if AudioManager is restricted)
        // Runs after ADB is initialized so connection is ready
        withContext(Dispatchers.IO) {
            try {
                val result = AdbShell.sendShellCommand("media volume --stream 3 --set $scaledVolume")
                Log.i(TAG, "Volume: ADB media volume --set $scaledVolume success=$result")
            } catch (e: Exception) {
                Log.w(TAG, "Volume: ADB command failed: ${e.message}")
            }
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
                val ok = AdbShell.sendShellCommand(command)
                Log.i(TAG, "Shell '$command': success=$ok")
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
