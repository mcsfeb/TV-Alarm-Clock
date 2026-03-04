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

        // Extract season/episode (1 = default when not specified)
        val season  = extras["season"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val episode = extras["episode"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        Log.i(TAG, "Target: S${season}E${episode}")

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
            "com.sling"                         -> launchSling()
            "com.hulu.livingroomplus"           -> launchHulu(deepLinkUri, extras, season, episode)
            "com.wbd.stream"                    -> launchHboMax(deepLinkUri, season, episode)
            "com.disney.disneyplus"             -> launchDisneyPlus(deepLinkUri, season, episode)
            "com.netflix.ninja"                 -> launchNetflix(deepLinkUri)
            "com.cbs.ott"                       -> launchParamountPlus(deepLinkUri, season, episode)
            "com.amazon.amazonvideo.livingroom" -> launchPrimeVideo(deepLinkUri, season, episode)
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
     * Three modes:
     * A) Search URL: https://play.max.com/search?q=Friends
     *    → deep link → 15s → CENTER (profile) → 15s → sendInputText(query) → 3s
     *    → RIGHT×6 → CENTER (show page) → episode navigation → CENTER (play)
     *    - Max is WebView-based: input text works perfectly
     *    - Search results pre-show query; typing refines it
     *
     * B) Content deep link (UUID format): https://play.max.com/video/watch/{uuid1}/{uuid2}
     *    → deep link → 25s → CENTER (profile) → 8s → MEDIA_PLAY
     *    - Old urn:hbo:episode format NO LONGER WORKS — must use UUID format
     *
     * C) Normal launch (APP_ONLY):
     *    → normal launch → 25s → CENTER (profile) → 8s → CENTER (featured) → MEDIA_PLAY
     */
    private suspend fun launchHboMax(deepLinkUri: String, season: Int = 1, episode: Int = 1) {
        val isSearchUrl = deepLinkUri.contains("/search")
        val hasContentLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY" && !isSearchUrl

        when {
            isSearchUrl -> {
                // Mode A: Search-based launch (tested: Friends, Blue Bloods-style shows)
                //
                // HOW IT WORKS (confirmed via real TV testing session 2):
                //   1. Search URL pre-populates HBO Max search results with the show name.
                //   2. After cold start (25s), profile picker is showing → CENTER dismisses it.
                //   3. After profile dismiss (8s), search results are visible and
                //      FOCUS IS ALREADY ON THE FIRST RESULT CARD (the show we searched for).
                //   4. CENTER opens the show detail page (not MEDIA_PLAY, which would
                //      play immediately without episode selection).
                //   5. navigateToEpisode() selects the correct season/episode.
                //
                // NOTE: No typing needed — the search URL already pre-populates the query.
                //       No RIGHT×N needed — focus lands on the show card after profile.
                val query = extractQuery(deepLinkUri)
                Log.i(TAG, "HBO Max: Search mode for '$query' → target S${season}E${episode}")
                if (!sendDeepLink("com.wbd.stream", deepLinkUri, emptyMap())) return

                // Wait for HBO Max cold start — WebView-based, needs full 25s
                Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
                delay(25000)

                // Profile select — focus now lands on the first search result (the show)
                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
                delay(8000)

                // Open show detail page (focus is on the show card from the search URL)
                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO open show")
                delay(6000)

                // Navigate to the specific season/episode then play
                navigateToEpisode(season, episode, "HBO")
            }

            hasContentLink -> {
                // Mode B: Direct content UUID link
                Log.i(TAG, "HBO Max: Content deep link + profile CENTER")
                if (!sendDeepLink("com.wbd.stream", deepLinkUri, emptyMap())) return

                Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
                delay(25000)

                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
                delay(8000)

                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
                delay(3000)
            }

            else -> {
                // Mode C: Normal launch (no deep link)
                Log.i(TAG, "HBO Max: Normal launch + profile + featured")
                val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.wbd.stream")
                    ?: packageManager.getLaunchIntentForPackage("com.wbd.stream")
                if (launchIntent == null) {
                    Log.e(TAG, "HBO Max: App not installed!")
                    return
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)

                Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
                delay(25000)

                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
                delay(8000)

                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play featured")
                delay(5000)

                sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
                delay(3000)
            }
        }

        Log.i(TAG, "HBO Max: Done")
    }

    /**
     * HULU — TESTED Feb 2026
     *
     * Recipe: Force-stop → deep link → wait 25s → CENTER (profile) → wait 3s
     *         → CENTER (select show) → wait 10s → episode navigation → CENTER (play)
     * - Hulu ignores deep links if app is already running (must force-stop first)
     * - Deep link lands on show search results page
     * - First CENTER: profile bypass
     * - Second CENTER: opens show detail page
     * - Then navigate to specific season/episode before playing
     */
    private suspend fun launchHulu(deepLinkUri: String, extras: Map<String, String>, season: Int = 1, episode: Int = 1) {
        Log.i(TAG, "Hulu: Deep link → S${season}E${episode}")

        // Send the deep link
        if (!sendDeepLink("com.hulu.livingroomplus", deepLinkUri, extras)) return

        // Wait for Hulu cold start
        Log.i(TAG, "Hulu: Waiting 25s for cold start...")
        delay(25000)

        // Profile bypass
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu profile")
        delay(3000)

        // Open show detail page (first search result)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu open show")
        delay(10000)

        // Navigate to the specific season/episode then play
        navigateToEpisode(season, episode, "Hulu")

        Log.i(TAG, "Hulu: Done")
    }

    /**
     * DISNEY+ — TESTED Feb 2026
     *
     * Two modes:
     * A) Search URL: https://www.disneyplus.com/search?q=Moana
     *    → search URL deeplink (BYPASSES PIN screen!) → 30s → typeOnDisneyKeyboard(query)
     *    → 2s → RIGHT×(7-lastCol) → CENTER (show page) → 8s → episode navigation → CENTER (play)
     *    - Native keyboard: input text DOES NOT WORK; must use DPAD grid navigation
     *    - Keyboard layout: 7 cols × 6 rows, always starts focused on 'a' (row=0,col=0)
     *    - Search URL bypass is critical — avoids PIN entirely
     *    - FIX: 500ms stabilization delay before typing prevents stray 'a' key
     *
     * B) Normal launch (APP_ONLY or blank deep link):
     *    → normal launch → 30s → profile CENTER → MEDIA_PLAY
     *    - If PIN configured in SharedPrefs, enters it via PIN pad navigation
     */
    private suspend fun launchDisneyPlus(deepLinkUri: String, season: Int = 1, episode: Int = 1) {
        val isSearchUrl = deepLinkUri.contains("/search")

        if (isSearchUrl) {
            // Mode A: Search-based launch (PIN bypassed by search URL)
            val query = extractQuery(deepLinkUri)
            Log.i(TAG, "Disney+: Search mode for '$query' → S${season}E${episode} (PIN bypassed via search URL)")
            if (!sendDeepLink("com.disney.disneyplus", deepLinkUri, emptyMap())) return

            Log.i(TAG, "Disney+: Waiting 30s for cold start...")
            delay(30000)

            // FIX: Extra stabilization delay so the keyboard is fully ready before we start
            // navigating. Without this, the first keypress can occasionally land while the
            // keyboard animation is still playing, causing a stray character to be typed.
            delay(800)

            // Type the query on the native DPAD keyboard; returns final column position
            val finalCol = typeOnDisneyKeyboard(query)
            delay(2000)

            // From the last typed letter, navigate RIGHT to exit keyboard and reach first result.
            // Tested: from col=0 (e.g. after "moana"), RIGHT×7 = first result card.
            // Formula: 7 - finalCol presses needed from any column.
            val rightPresses = (7 - finalCol).coerceAtLeast(1)
            repeat(rightPresses) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ RIGHT"); delay(200) }

            // Open show detail page
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ open show")
            delay(8000)

            // Navigate to the specific season/episode then play
            navigateToEpisode(season, episode, "D+")

        } else {
            // Mode B: Normal launch (no search URL)
            Log.i(TAG, "Disney+: Normal launch + profile select")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage("com.disney.disneyplus")
                ?: packageManager.getLaunchIntentForPackage("com.disney.disneyplus")
            if (launchIntent == null) {
                Log.e(TAG, "Disney+: App not installed!")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)

            Log.i(TAG, "Disney+: Waiting 30s for cold start...")
            delay(30000)

            val pin = getDisneyPin()
            if (pin.isNotEmpty()) {
                Log.i(TAG, "Disney+: Entering PIN (${pin.length} digits)")
                enterDisneyPinFromStartPosition(pin)
                delay(5000)
            } else {
                // Single CENTER: selects profile AND triggers auto-play
                sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ profile select")
                delay(8000)
            }

            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "D+ force play")
            delay(3000)
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
     * PARAMOUNT+ (CBS Interactive) — TESTED Feb 2026
     *
     * Recipe: Search URL → 20s → CENTER (profile) → 15s → sendInputText(query)
     *         → 3s → RIGHT×6 → CENTER (show page) → episode navigation → CENTER (play)
     *
     * KEY FINDINGS from real TV testing:
     * - Paramount+ is WebView-based: input text works perfectly
     * - Search URL: https://www.paramountplus.com/search/?q=Blue+Bloods
     * - After search URL opens, profile picker appears first → CENTER dismisses it
     * - After profile, search results are already visible (query in URL pre-populates)
     * - sendInputText() refines/confirms the search (types into the search box)
     * - Use URL-encoded query with + for spaces (e.g. "Blue+Bloods")
     * - RIGHT×6 navigates from search box to first result card
     * - CENTER #1: opens show detail page; then navigateToEpisode() selects specific ep
     */
    private suspend fun launchParamountPlus(deepLinkUri: String, season: Int = 1, episode: Int = 1) {
        val query = extractQuery(deepLinkUri)
        Log.i(TAG, "Paramount+: Search for '$query' → S${season}E${episode}")

        if (!sendDeepLink("com.cbs.ott", deepLinkUri, emptyMap())) return

        Log.i(TAG, "Paramount+: Waiting 20s for cold start...")
        delay(20000)

        // Dismiss profile picker
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ profile select")
        delay(15000)

        // Type search query (WebView — input text works)
        sendInputText(query)
        delay(3000)

        // Navigate from search bar to first result card
        repeat(6) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "P+ RIGHT"); delay(200) }

        // Open show detail page
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ open show")
        delay(6000)

        // Navigate to the specific season/episode then play
        navigateToEpisode(season, episode, "P+")

        Log.i(TAG, "Paramount+: Done")
    }

    /**
     * PRIME VIDEO — TESTED Feb 2026
     *
     * Recipe: Search URL → 25s → RIGHT×6 → DOWN → CENTER (show page) → episode navigation → CENTER (play)
     *
     * KEY FINDINGS from real TV testing:
     * - Search URL pre-populates results — NO TYPING NEEDED
     * - URL: https://app.primevideo.com/search?phrase=The+Boys
     * - No profile picker (unlike most other apps)
     * - RIGHT×6 from search bar moves to first result card title
     * - DOWN moves from result title to "Episode 1 Watch now" button
     * - CENTER #1: opens show detail page; then navigateToEpisode() selects specific ep
     */
    private suspend fun launchPrimeVideo(deepLinkUri: String, season: Int = 1, episode: Int = 1) {
        val query = extractQuery(deepLinkUri)
        Log.i(TAG, "Prime Video: Search URL for '$query' → S${season}E${episode}")

        if (!sendDeepLink("com.amazon.amazonvideo.livingroom", deepLinkUri, emptyMap())) return

        // Prime Video loads results without typing — no profile picker
        Log.i(TAG, "Prime Video: Waiting 25s (results pre-populated)...")
        delay(25000)

        // Navigate from search bar to first result card
        repeat(6) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PV RIGHT"); delay(200) }

        // DOWN: move from result title to "Episode 1 Watch now" button
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV DOWN to Watch Now")
        delay(500)

        // Open show detail page
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV open show")
        delay(6000)

        // Navigate to the specific season/episode then play
        navigateToEpisode(season, episode, "PV")

        Log.i(TAG, "Prime Video: Done")
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
    //  SEARCH HELPERS
    // =========================================================================

    /**
     * Extract a human-readable search query from a deep link search URL.
     * Handles both ?q= (HBO Max, Disney+, Paramount+) and ?phrase= (Prime Video).
     * Decodes URL encoding: "Blue+Bloods" → "Blue Bloods", "blue%20bloods" → "blue bloods"
     */
    private fun extractQuery(deepLinkUri: String): String {
        return try {
            val uri = Uri.parse(deepLinkUri)
            val raw = uri.getQueryParameter("q")
                ?: uri.getQueryParameter("phrase")
                ?: ""
            raw.replace("+", " ").replace("%20", " ").trim()
        } catch (e: Exception) {
            Log.w(TAG, "extractQuery failed for $deepLinkUri: ${e.message}")
            ""
        }
    }

    /**
     * Send text input word-by-word via ADB shell input text.
     *
     * WHY WORD-BY-WORD: Passing a full string with spaces to `input text` causes
     * the shell to split on spaces and only type the first word.
     * Solution: send each word separately with KEYCODE_SPACE between them.
     *
     * Works on WebView-based apps (HBO Max, Paramount+).
     * Does NOT work on native keyboard apps (Disney+, Hulu) — use typeOnDisneyKeyboard instead.
     */
    private suspend fun sendInputText(text: String) {
        val words = text.trim().split(" ", "%20").filter { it.isNotBlank() }
        for ((i, word) in words.withIndex()) {
            sendShell("input text $word")
            delay(300)
            if (i < words.size - 1) {
                sendKey(KeyEvent.KEYCODE_SPACE, "SPACE between words")
                delay(200)
            }
        }
    }

    /**
     * Type a search query on the Disney+ native TV keyboard using DPAD navigation.
     *
     * KEYBOARD LAYOUT (mapped via UIAutomator dump, Feb 2026):
     *   Row 0: a b c d e f g
     *   Row 1: h i j k l m n
     *   Row 2: o p q r s t u
     *   Row 3: v w x y z 1 2
     *   Row 4: 3 4 5 6 7 8 9
     *   Row 5: 0 (col 0)
     *   (Space and Delete buttons are above the letter grid)
     *
     * INITIAL STATE: Focus always starts on 'a' (row=0, col=0) when search page opens.
     * NAVIGATION: Move DPAD to target letter, press CENTER to type it.
     * SPACES: Skipped — Disney+ fuzzy search handles multi-word queries fine.
     *
     * Returns the final column position so the caller can calculate how many
     * RIGHT presses are needed to exit the keyboard and reach the first result.
     */
    private suspend fun typeOnDisneyKeyboard(query: String): Int {
        // Map each character to its (row, col) in the 7-column grid
        // "abcdefghijklmnopqrstuvwxyz1234567890": index/7 = row, index%7 = col
        val ordered = "abcdefghijklmnopqrstuvwxyz1234567890"
        val charPos = ordered.mapIndexed { idx, c -> c to Pair(idx / 7, idx % 7) }.toMap()

        var curRow = 0  // Start at 'a'
        var curCol = 0

        for (ch in query.lowercase()) {
            if (ch == ' ') continue  // Skip spaces — fuzzy search handles them

            val (targetRow, targetCol) = charPos[ch] ?: run {
                Log.w(TAG, "Disney+ keyboard: unknown char '$ch', skipping")
                continue
            }

            val rowDiff = targetRow - curRow
            val colDiff = targetCol - curCol

            // Navigate rows
            if (rowDiff > 0) repeat(rowDiff)  { sendKey(KeyEvent.KEYCODE_DPAD_DOWN,  "D+ kbd ↓"); delay(150) }
            else             repeat(-rowDiff) { sendKey(KeyEvent.KEYCODE_DPAD_UP,    "D+ kbd ↑"); delay(150) }

            // Navigate columns
            if (colDiff > 0) repeat(colDiff)  { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ kbd →"); delay(150) }
            else             repeat(-colDiff) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT,  "D+ kbd ←"); delay(150) }

            // Select the letter
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ kbd '$ch'")
            delay(200)

            curRow = targetRow
            curCol = targetCol
        }

        Log.i(TAG, "Disney+ keyboard: typed '${query.lowercase().replace(" ", "")}', ended at col=$curCol")
        return curCol  // Caller uses this to calculate RIGHT presses to reach first result
    }

    // =========================================================================
    //  EPISODE NAVIGATION (shared helper for all show-based apps)
    // =========================================================================

    /**
     * Navigate a streaming app's show detail page to a specific season and episode.
     *
     * CALL AFTER the show detail page is open and the page has had time to load (5-8s delay).
     *
     * LAYOUT assumed (common to HBO Max, Disney+, Paramount+, Prime Video, Hulu TV apps):
     *
     *   [▶ Play / Continue Watching]   ← initial focus after opening show
     *   [Season 1] [Season 2] [...]    ← season selector row (1 DOWN from play button)
     *   [Ep1 card] [Ep2 card] [...]   ← episode list (1 DOWN from season row)
     *
     * STRATEGY:
     *   S1E1  → just CENTER (focus is already on Play S1E1 or first episode card)
     *   S1EN  → DOWN×2 to reach episode list, RIGHT×(N-1) to episode N, CENTER
     *   SMEN  → DOWN×1 to season row, RIGHT×(M-1) to season M, CENTER, wait 2s,
     *           DOWN×1 to episode list, RIGHT×(N-1) to episode N, CENTER
     *
     * NOTE: The exact number of DOWN presses can vary slightly between apps and UI versions.
     * If an app needs a different count, override in the app-specific method.
     * appTag is used for log messages (e.g., "HBO", "D+", "P+", "PV", "Hulu").
     */
    private suspend fun navigateToEpisode(season: Int, episode: Int, appTag: String) {
        Log.i(TAG, "$appTag: navigate to S${season}E${episode}")

        if (season == 1 && episode == 1) {
            // Default case: focus is already on Play S1E1 — just play it
            Log.i(TAG, "$appTag: S1E1 — playing default (no navigation needed)")
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "$appTag play S1E1")
            delay(3000)
            return
        }

        if (season > 1) {
            // Navigate DOWN 1 to reach season selector row
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "$appTag → season row")
            delay(400)

            // Navigate RIGHT to the target season (season N = RIGHT×(N-1) from Season 1)
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "$appTag → S$season")
                delay(250)
            }

            // Select the season and wait for the episode list to refresh
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "$appTag select S$season")
            delay(2000)

            // Navigate DOWN 1 to reach the episode list
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "$appTag → episode list")
            delay(400)
        } else {
            // Season 1: navigate DOWN×2 to skip play button + season row → reach episode list
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "$appTag → past play btn")
            delay(350)
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "$appTag → S1 episode list")
            delay(400)
        }

        // Navigate RIGHT to the target episode (episode N = RIGHT×(N-1) from Ep1)
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "$appTag → E$episode")
                delay(250)
            }
        }

        // Play the selected episode
        Log.i(TAG, "$appTag: pressing play on S${season}E${episode}")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "$appTag play S${season}E${episode}")
        delay(3000)
    }

    // =========================================================================
    //  VOLUME CONTROL
    // =========================================================================

    /**
     * Set the TV volume to a specific level (0-100).
     *
     * TWO-STEP APPROACH:
     * 1. AudioManager.setStreamVolume() — sets the Android box's own audio level immediately.
     *    This is instant and works even before any app is open.
     *
     * 2. ADB KEYCODE_VOLUME_DOWN×15 then KEYCODE_VOLUME_UP×N — sends CEC volume key events
     *    that travel through HDMI to the physical TV/soundbar. We first press down to a
     *    known-low floor, then press up to reach the target level.
     *    This handles TVs where HDMI-CEC volume is separate from the box's own volume.
     *
     * NOTE: STREAM_MUSIC is the correct stream for TV output on Onn Google TV (not
     * STREAM_ACCESSIBILITY or STREAM_SYSTEM). MODIFY_AUDIO_SETTINGS is not required
     * for setStreamVolume on STREAM_MUSIC when called from a foreground service.
     */
    private suspend fun setTvVolume(targetVolume: Int) {
        // Step 1: Set AudioManager stream volume (box audio — immediate)
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val scaledVolume = (targetVolume * maxVolume / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, scaledVolume, 0)
            Log.i(TAG, "Volume (AudioManager): $targetVolume% → $scaledVolume/$maxVolume")
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager volume failed: ${e.message}")
        }

        // Step 2: Send CEC volume key events for the physical TV/soundbar.
        // Press DOWN×15 to get to a known-low level, then UP×N to reach target.
        // 15 down steps = ~0% on most TVs; target steps = targetVolume/7 (rough mapping
        // since CEC step size varies by TV, but ~7% per step is typical).
        try {
            val downSteps = 15
            val upSteps = (targetVolume / 7).coerceIn(0, 20)
            Log.i(TAG, "Volume (CEC): pressing DOWN×$downSteps then UP×$upSteps")

            repeat(downSteps) {
                sendShell("input keyevent ${KeyEvent.KEYCODE_VOLUME_DOWN}")
                delay(80)
            }
            delay(300)
            repeat(upSteps) {
                sendShell("input keyevent ${KeyEvent.KEYCODE_VOLUME_UP}")
                delay(80)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CEC volume key events failed: ${e.message}")
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
