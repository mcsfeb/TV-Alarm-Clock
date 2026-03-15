package com.mcsfeb.tvalarmclock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * PHILOSOPHY (from PLAN.md):
 * - ALWAYS use Search + DPAD navigation for on-demand content. Never deep links.
 * - Deep links are brittle and break on app updates. DPAD search is reliable once tuned.
 * - Only exception: Live TV (Sling) uses normal launch + auto-play.
 *
 * LAUNCH SEQUENCE for every app:
 * 1. ADB init (needed for key injection)
 * 2. Volume: ramp DOWN to 0, then UP to target (always consistent result)
 * 3. Go HOME for clean state
 * 4. Force-stop target app (cold start every time)
 * 5. Run app-specific recipe (search + navigate OR normal launch)
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
            volume: Int = -1,
            searchQuery: String = "",
            seasonNumber: Int = 1,
            episodeNumber: Int = 1
        ) {
            val intent = Intent(context, ContentLaunchService::class.java).apply {
                putExtra("PACKAGE_NAME", packageName)
                putExtra("DEEP_LINK_URI", deepLinkUri)
                putExtra("VOLUME", volume)
                putExtra("SEARCH_QUERY", searchQuery)
                putExtra("SEASON_NUMBER", seasonNumber)
                putExtra("EPISODE_NUMBER", episodeNumber)
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

    // HOME BUTTON INTERRUPT — set to true when user presses HOME, stops all navigation
    @Volatile private var homePressed = false

    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                val reason = intent.getStringExtra("reason") ?: ""
                if (reason == "homekey" || reason == "assist" || reason == "recentapps") {
                    Log.i(TAG, "HOME pressed (reason=$reason) — aborting navigation")
                    homePressed = true
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        // Register HOME button listener
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(homeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(homeReceiver, filter)
        }
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
        val searchQuery = intent.getStringExtra("SEARCH_QUERY") ?: ""
        val seasonNumber = intent.getIntExtra("SEASON_NUMBER", 1)
        val episodeNumber = intent.getIntExtra("EPISODE_NUMBER", 1)

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
            performLaunch(packageName, deepLinkUri, extras, volume, searchQuery, seasonNumber, episodeNumber)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    // =========================================================================
    //  MASTER LAUNCH SEQUENCE
    // =========================================================================

    private suspend fun performLaunch(
        packageName: String,
        deepLinkUri: String,
        extras: Map<String, String>,
        volume: Int,
        searchQuery: String,
        seasonNumber: Int,
        episodeNumber: Int
    ) {
        Log.i(TAG, "=== LAUNCH START: $packageName ===")
        Log.i(TAG, "searchQuery='$searchQuery' season=$seasonNumber episode=$episodeNumber")

        // Step 1: Initialize ADB
        withContext(Dispatchers.IO) {
            AdbShell.init(this@ContentLaunchService)
        }

        val useSearch = searchQuery.isNotBlank()

        // Step 2: Force-stop for cold start — EARLY, while AlarmActivity window is still alive.
        //         AlarmActivity delays its finish() by 5s specifically for this.
        Log.i(TAG, "Step: Force-stopping $packageName")
        sendShell("am force-stop $packageName")
        delay(1000)

        // Step 3: Launch target app — EARLY (t≈2s), while AlarmActivity window is still alive
        //         (alive for 5s) AND while AlarmManager's BAL token is still valid (~10s).
        //         App-specific functions must NOT call startActivity() anymore — it's done here.
        //         Deep-link-only flows (Netflix, Hulu APP_ONLY) handle their own startActivity.
        val needsPreLaunch = when (packageName) {
            "com.sling" -> true
            "com.wbd.stream" -> useSearch             // HBO Max WITH search
            "com.hulu.livingroomplus" -> useSearch    // Hulu WITH search
            "com.disney.disneyplus" -> true           // Disney+ (both modes)
            "com.cbs.ott" -> useSearch                // Paramount+ WITH search
            "com.amazon.amazonvideo.livingroom" -> useSearch  // Prime Video WITH search
            else -> false
        }
        if (needsPreLaunch) {
            Log.i(TAG, "Step: Launching $packageName")
            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                Log.e(TAG, "Step: $packageName not installed!")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        // Step 4: Volume ramp — runs AFTER launch, concurrently with the app's cold start.
        //         No HOME press needed — target app is already the foreground.
        if (volume >= 0) {
            setTvVolume(volume)
        }

        // Step 5: Run app-specific navigation.
        //         Functions flagged with needsPreLaunch=true must NOT call startActivity().
        when (packageName) {
            "com.sling" -> {
                if (useSearch) launchSlingWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchSling()
            }
            "com.hulu.livingroomplus" -> {
                if (useSearch) launchHuluWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchHulu(deepLinkUri, extras)
            }
            "com.wbd.stream" -> {
                if (useSearch) launchHboMaxWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchHboMax(deepLinkUri)
            }
            "com.disney.disneyplus" -> {
                if (useSearch) launchDisneyPlusWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchDisneyPlus(deepLinkUri)
            }
            "com.netflix.ninja" -> launchNetflix(deepLinkUri)
            "com.cbs.ott" -> {
                if (useSearch) launchParamountWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchGeneric(packageName, deepLinkUri, extras)
            }
            "com.amazon.amazonvideo.livingroom" -> {
                if (useSearch) launchPrimeVideoWithSearch(searchQuery, seasonNumber, episodeNumber)
                else launchGeneric(packageName, deepLinkUri, extras)
            }
            else -> launchGeneric(packageName, deepLinkUri, extras)
        }

        Log.i(TAG, "=== LAUNCH COMPLETE: $packageName ===")
    }

    // =========================================================================
    //  APP-SPECIFIC LAUNCH RECIPES
    // =========================================================================

    /**
     * SLING TV — Live TV mode (auto-play last channel).
     *
     * BUG FIX (Bug 1): Reduced delay between CENTER (profile bypass) and MEDIA_PLAY
     * to 100ms. Any pause/unpause glitch is imperceptible before MEDIA_PLAY overrides.
     */
    private suspend fun launchSling() {
        Log.i(TAG, "Sling: Normal launch (APP_ONLY mode)")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Sling: Waiting 35s for cold start (React Native)...")
        delay(35000)
        if (checkAborted()) return

        // MEDIA_PLAY to start live TV playback.
        // Do NOT press CENTER — it may click on "recently viewed" content instead of live TV.
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling force play")
        delay(3000)

        Log.i(TAG, "Sling: Done (playing last channel)")
    }

    /**
     * SLING TV — Search mode for VOD shows.
     *
     * FIXED March 2026:
     * - Do NOT press CENTER on home screen — it clicks "recently viewed" content.
     * - Go directly to nav bar after cold start (DPAD_UP), then navigate to Search.
     *
     * SLING NAV BAR (opened by DPAD_UP):
     * Layout: [My TV] [Guide] [DVR] [On Demand] [Search]
     * LEFT×10 (safe overshoot) → My TV (leftmost). Then RIGHT×4 → Search.
     */
    private suspend fun launchSlingWithSearch(searchQuery: String, season: Int, episode: Int) {
        val cleanQuery = searchQuery.removePrefix("LIVE:").removePrefix("live:").trim()
        val isLiveChannel = searchQuery.startsWith("LIVE:", ignoreCase = true)

        if (isLiveChannel) {
            // Live channels use GUIDE navigation, not search
            launchSlingGuideChannel(cleanQuery)
            return
        }

        Log.i(TAG, "Sling: VOD search for '$cleanQuery' S${season}E${episode}")
        Log.i(TAG, "Sling: Waiting 35s for cold start (React Native)...")
        delay(35000)
        if (checkAborted()) return

        // DO NOT press CENTER — clicks "recently viewed" content

        // ── OPEN NAV BAR ──
        Log.i(TAG, "Sling: Opening nav bar (DPAD_UP)")
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "Sling open nav bar")
        delay(3000)
        if (checkAborted()) return

        // Navigate to Search: LEFT×10 → leftmost (My TV), then RIGHT×4 → Search
        repeat(10) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Sling nav←"); delay(300) }
        delay(500)
        repeat(4) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling nav→"); delay(400) }
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling open Search")
        delay(4000)
        if (checkAborted()) return

        // Type query via 6-col keyboard
        val queryToType = cleanQuery.lowercase().filter { it.isLetterOrDigit() }.take(12)
        Log.i(TAG, "Sling: Typing '$queryToType'")
        val (_, endCol) = typePvKeyboard(queryToType)
        delay(2500)
        if (checkAborted()) return

        // RIGHT×(6-endCol) to jump from keyboard into results panel
        val rightsToResults = (6 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Sling: RIGHT×$rightsToResults to results")
        repeat(rightsToResults) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling to results"); delay(250) }
        delay(500)

        // Select first result
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling select result")
        delay(5000)

        // VOD: navigate to season/episode
        if (season > 1 || episode > 1) {
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Sling to episode area")
            delay(600)
            if (season > 1) {
                repeat(season - 1) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling season→"); delay(350) }
            }
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling select season")
            delay(1500)
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Sling to episodes")
            delay(600)
            if (episode > 1) {
                repeat(episode - 1) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling episode→"); delay(300) }
            }
        }
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling play episode")
        delay(4000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling force play")
        delay(3000)

        Log.i(TAG, "Sling: Done")
    }

    /**
     * SLING TV — Guide navigation for live channels.
     *
     * Opens the Guide from the nav bar, then scrolls to the target channel.
     * Each channel has a known guide position (row number from top).
     *
     * GUIDE NAVIGATION:
     * 1. DPAD_UP → nav bar
     * 2. LEFT×10 → My TV (leftmost), RIGHT×1 → Guide
     * 3. CENTER → opens Guide
     * 4. UP×50 → scroll to very top of guide (safe overshoot)
     * 5. DOWN×(channelPosition) → target channel
     * 6. CENTER → select channel → starts live playback
     */
    private suspend fun launchSlingGuideChannel(channelName: String) {
        Log.i(TAG, "Sling GUIDE: Navigating to live channel '$channelName'")
        Log.i(TAG, "Sling: Waiting 35s for cold start (React Native)...")
        delay(35000)
        if (checkAborted()) return

        // DO NOT press CENTER — clicks "recently viewed" content

        // ── OPEN GUIDE VIA NAV BAR ──
        Log.i(TAG, "Sling: Opening nav bar (DPAD_UP)")
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "Sling open nav bar")
        delay(3000)
        if (checkAborted()) return

        // Navigate to Guide: LEFT×10 → My TV (leftmost), then RIGHT×1 → Guide
        repeat(10) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Sling nav←"); delay(300) }
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling nav→ Guide"); delay(400)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling open Guide")
        delay(5000)  // Guide takes time to load
        if (checkAborted()) return

        // Guide is now open. Scroll to top first, then down to channel.
        Log.i(TAG, "Sling GUIDE: Scrolling to top")
        repeat(50) { sendKey(KeyEvent.KEYCODE_DPAD_UP, "Sling guide↑"); delay(150) }
        delay(1000)

        // Look up channel position in guide
        val guidePos = slingGuidePositions[channelName.lowercase()] ?: run {
            // Unknown channel — try searching for it instead
            Log.w(TAG, "Sling: Channel '$channelName' not in guide map. Trying search fallback.")
            // Fall back to search approach
            sendKey(KeyEvent.KEYCODE_BACK, "Sling back from guide"); delay(2000)
            sendKey(KeyEvent.KEYCODE_DPAD_UP, "Sling open nav bar"); delay(3000)
            repeat(10) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Sling nav←"); delay(300) }
            delay(500)
            repeat(4) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling nav→"); delay(400) }
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling open Search"); delay(4000)
            val queryToType = channelName.lowercase().filter { it.isLetterOrDigit() }.take(12)
            typePvKeyboard(queryToType)
            delay(2500)
            repeat(6) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sling to results"); delay(250) }
            delay(500)
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling select result"); delay(3000)
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling start channel"); delay(3000)
            return
        }

        Log.i(TAG, "Sling GUIDE: DOWN×$guidePos to '$channelName'")
        repeat(guidePos) {
            sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Sling guide↓")
            delay(250)
        }
        delay(500)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling select channel")
        delay(3000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling start live channel")
        delay(3000)

        Log.i(TAG, "Sling GUIDE: Done — playing '$channelName'")
    }

    /**
     * Sling guide channel positions — row number from the TOP of the guide.
     * These positions depend on the user's Sling package and customization.
     * NEEDS USER VERIFICATION — test each channel and adjust positions.
     *
     * Default order is based on Sling Blue+Orange combined package (typical US setup).
     * Positions are 0-indexed from the top of the guide after scrolling all the way up.
     */
    private val slingGuidePositions: Map<String, Int> = mapOf(
        // News
        "cnn" to 0, "fox news" to 1, "msnbc" to 2, "cnbc" to 3,
        "hln" to 4, "bbc news" to 5, "newsnation" to 6,
        // Sports
        "espn" to 7, "espn2" to 8, "tnt sports" to 9,
        "fs1 (fox sports 1)" to 10, "nfl network" to 11,
        "nba tv" to 12, "mlb network" to 13, "golf channel" to 14,
        // Entertainment
        "amc" to 15, "tbs" to 16, "usa network" to 17,
        "fx" to 18, "comedy central" to 19, "bravo" to 20,
        "e!" to 21, "syfy" to 22,
        // Kids
        "cartoon network" to 23, "nickelodeon" to 24,
        "disney channel" to 25, "disney junior" to 26,
        // Movies
        "tcm (turner classic movies)" to 27,
        // Lifestyle
        "hgtv" to 28, "food network" to 29, "tlc" to 30,
        "discovery channel" to 31, "history channel" to 32,
        "a&e" to 33, "lifetime" to 34,
        // Music
        "mtv" to 35, "vh1" to 36
    )

    /**
     * HBO MAX (Max) — Normal launch + profile bypass
     *
     * Used when no search query is set (APP_ONLY mode).
     * BUG NOTE: If you need to reach specific content, use launchHboMaxWithSearch().
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

        Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
        delay(25000)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
        delay(8000)

        if (!hasDeepLink) {
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play featured")
            delay(5000)
        }

        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
        delay(3000)

        Log.i(TAG, "HBO Max: Done")
    }

    /**
     * MAX (formerly HBO Max) — Search + navigate to specific season/episode
     *
     * VERIFIED navigation (live-tested on Onn Google TV, Max app com.wbd.stream):
     *
     * SEARCH:
     * 1. KEYCODE_SEARCH opens Google Katniss — do NOT use it.
     *    FIX: DPAD_LEFT → sidebar ("Home" focused) → DPAD_UP → "Search" → DPAD_CENTER.
     * 2. typeTextViaAdb() WORKS on Max (unlike Hulu/PrimeVideo) — regular text field.
     * 3. After typing, keyboard focus stays at 'a' (row 0, col 0).
     *    RIGHT×6 jumps from col 0 past the keyboard into the results panel.
     *    First result is ALREADY focused — do NOT press DOWN.
     *    CENTER opens the show page.
     *
     * SHOW PAGE (verified on Friends, 10 seasons):
     * 4. Show opens with focus on "Watch SxEy" button (defaults to last-watched episode).
     * 5. DOWN×1 → "Episodes" tab activated; season row appears with last-watched season.
     * 6. DOWN×1 → Focus enters season row (current season is selected/focused).
     * 7. LEFT×20 → Safely scrolls to Season 1 (works for any show with ≤20 seasons).
     * 8. RIGHT×(season-1) → Target season; episodes auto-load (NO CENTER needed).
     * 9. DOWN×1 → Episode cards appear; E1 of that season is focused.
     * 10. RIGHT×(episode-1) → Target episode card focused.
     * 11. CENTER → Plays the episode (ads may precede content).
     */
    private suspend fun launchHboMaxWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "HBO Max: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "HBO Max: Waiting 25s for cold start...")
        delay(25000)

        // Profile bypass (cold-start shows "Who's Watching?" picker)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO profile select")
        delay(8000)  // Home screen takes ~8s to fully load after profile

        if (checkAborted()) return

        // Open search via sidebar (KEYCODE_SEARCH opens Katniss — do NOT use)
        Log.i(TAG, "HBO Max: Opening search via sidebar")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "HBO open sidebar")
        delay(1000)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "HBO focus search")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO open search")
        delay(2500)

        if (checkAborted()) return

        // Type show name — typeTextViaAdb WORKS on Max (standard text field, not custom keyboard)
        Log.i(TAG, "HBO Max: Typing '$searchQuery'")
        typeTextViaAdb(searchQuery)
        delay(2500)  // Wait for results to populate

        // RIGHT×6 jumps from keyboard col 0 to results panel; first result already focused
        Log.i(TAG, "HBO Max: RIGHT×6 to results panel")
        repeat(6) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "HBO to results"); delay(200) }
        delay(400)

        if (checkAborted()) return

        // First result already focused — open show page
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO open show")
        delay(8000)  // Wait for show page to load (increased from 6s)

        // Show page layout:
        //   [Watch Now / Resume button]  ← initial focus
        //   [Episodes tab]
        //   [Season row: S1  S2  S3  ...]  ← starts on latest season (e.g. S10 for Friends)
        //   [Episode cards row]

        // DOWN×1 → From hero/Watch button to "Episodes" tab
        Log.i(TAG, "HBO Max: Navigating to Episodes tab")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "HBO to Episodes tab")
        delay(800)

        // DOWN×1 → From Episodes tab into Season row
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "HBO into season row")
        delay(800)

        // LEFT×20 → Scrolls all the way back to Season 1 (handles shows with up to 20 seasons)
        Log.i(TAG, "HBO Max: LEFT×20 to reach Season 1")
        repeat(20) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "HBO season←"); delay(250) }
        delay(1000)  // Wait for episode list to reload for S1

        // RIGHT×(season-1) → Target season (episodes reload on each season change)
        Log.i(TAG, "HBO Max: RIGHT×${season - 1} to Season $season")
        if (season > 1) {
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "HBO season→")
                delay(400)  // Give more time for episode list to update per season
            }
        }
        delay(1500)  // Allow episode list to fully load for selected season

        // DOWN×1 → From season row into episode cards (E1 of selected season is focused)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "HBO to episode cards")
        delay(600)

        // RIGHT×(episode-1) → Target episode
        Log.i(TAG, "HBO Max: RIGHT×${episode - 1} to Episode $episode")
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "HBO episode→")
                delay(300)
            }
        }
        delay(500)

        if (checkAborted()) return

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play episode")
        delay(5000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
        delay(3000)

        // REMOVED March 2026: The old "dismiss overlay" section (DOWN + MEDIA_PLAY) was navigating
        // to a DIFFERENT episode in the player's episode list, then playing THAT instead.
        // MEDIA_PLAY above is sufficient — any overlay auto-dismisses during playback.

        Log.i(TAG, "HBO Max: Done (S${season}E${episode})")
    }

    /**
     * HULU — Deep link launch (APP_ONLY mode).
     * Hulu is WebView-based (OPAQUE). Used when no search query is set.
     */
    private suspend fun launchHulu(deepLinkUri: String, extras: Map<String, String>) {
        Log.i(TAG, "Hulu: Deep link + 3x CENTER")

        if (!sendDeepLink("com.hulu.livingroomplus", deepLinkUri, extras)) return

        Log.i(TAG, "Hulu: Waiting 25s for cold start...")
        delay(25000)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu profile")
        delay(3000)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu select")
        delay(10000)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu play")
        delay(3000)

        Log.i(TAG, "Hulu: Done")
    }

    /**
     * HULU — Search + navigate to specific season/episode
     *
     * VERIFIED navigation (live-tested on Onn Google TV, Hulu app):
     *
     * SEARCH:
     * 1. KEYCODE_SEARCH opens Google Katniss — do NOT use it.
     *    FIX: DPAD_LEFT → sidebar opens ("Home" focused) → DPAD_UP → "Search" → DPAD_CENTER.
     * 2. typeTextViaAdb() fails on Hulu's custom keyboard.
     *    FIX: typePvKeyboard() — same 6×6 DPAD-navigable layout as Prime Video.
     * 3. After typing first 5 chars (ending at col C), RIGHT×(6-C) jumps to results panel.
     *    The first result card is ALREADY focused — do NOT press DOWN.
     *    → CENTER to open the show page.
     *
     * SHOW PAGE (verified on Abbott Elementary):
     * 4. Show page opens with focus on "START WATCHING: S1 E1" button.
     * 5. DOWN×2 → Episodes tab → S1 E1 in episode list (focus on first episode card).
     * 6. LEFT → season sidebar (SEASON 1 focused; seasons listed vertically).
     * 7. DOWN×(season-1) → target season; episodes auto-load without needing CENTER.
     * 8. RIGHT → episode list; E1 of target season is now focused.
     * 9. DOWN×(episode-1) → target episode card.
     * 10. CENTER → plays the episode.
     */
    private suspend fun launchHuluWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Hulu: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Hulu: Waiting 30s for cold start...")
        delay(30000)  // Increased from 25s: Hulu WebView needs extra time to be interactive
        if (checkAborted()) return

        // Profile bypass (cold start shows profile picker; CENTER selects first profile)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu profile")
        delay(12000)  // Hulu home screen takes long to respond after profile

        if (checkAborted()) return

        // Open search via sidebar — same pattern as HBO Max (LEFT→UP→CENTER).
        // KEYCODE_SEARCH opens Google Katniss/Gemini — do NOT use.
        // LEFT opens the sidebar with "Home" focused, UP moves to "Search" at top.
        Log.i(TAG, "Hulu: Opening search via sidebar (LEFT→UP→CENTER)")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Hulu to sidebar")
        delay(1500)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "Hulu focus search")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu open search")
        delay(5000)  // Hulu search keyboard is slow to load

        if (checkAborted()) return

        // Type first 5 chars via keyboard navigation (ADB input text doesn't work on Hulu)
        // Keyboard layout: 6×6 grid identical to Prime Video (row0=a-f, row1=g-l, …, row5=5-0)
        val queryToType = searchQuery.lowercase().filter { it.isLetterOrDigit() }.take(5)
        Log.i(TAG, "Hulu: Typing '$queryToType' via keyboard nav")
        val (_, endCol) = typePvKeyboard(queryToType)
        delay(3000)  // Increased from 2s: wait for search results to populate

        if (checkAborted()) return

        // RIGHT×(6-endCol) jumps from keyboard into results panel.
        // First result card is ALREADY focused after this — no DOWN needed.
        val rightsToResults = (6 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Hulu: RIGHT×$rightsToResults to reach results panel")
        repeat(rightsToResults) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Hulu to results"); delay(250) }
        delay(600)

        // First result already focused → open show page
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu open show")
        delay(8000)  // Show detail page load on Hulu can be slow

        // Show page layout:
        //   [Start Watching / Resume button]  ← initial focus
        //   [Episodes tab]
        //   [Season sidebar (left) | Episode list (right)]
        //     Season 1           E1   E2   E3 ...
        //     Season 2
        //     ...

        // DOWN×1 → Start Watching button → Episodes tab
        Log.i(TAG, "Hulu: Navigating to episode section")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Hulu to Episodes tab")
        delay(600)

        // DOWN×1 → Episodes tab → Into the episode area (E1 of S1 is focused)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Hulu into episode area")
        delay(800)

        // LEFT → From episode area → Season sidebar (Season 1 focused at top)
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Hulu to season sidebar")
        delay(600)

        // DOWN×(season-1) → Target season; episode list auto-reloads without CENTER
        Log.i(TAG, "Hulu: Navigating to Season $season")
        if (season > 1) {
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Hulu season↓")
                delay(400)
            }
        }
        delay(800)  // Wait for episodes to reload for selected season

        // RIGHT → From season sidebar → Episode list (E1 of selected season is focused)
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Hulu to episode list")
        delay(600)

        // DOWN×(episode-1) → Target episode card
        Log.i(TAG, "Hulu: Navigating to Episode $episode")
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "Hulu episode↓")
                delay(350)
            }
        }
        delay(500)

        if (checkAborted()) return

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu play episode")
        delay(4000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Hulu force play")
        delay(3000)

        Log.i(TAG, "Hulu: Done (S${season}E${episode})")
    }

    /**
     * DISNEY+ — Normal launch + profile bypass (APP_ONLY mode).
     * Used when no search query is set. Search works in separate function.
     */
    private suspend fun launchDisneyPlus(deepLinkUri: String) {
        Log.i(TAG, "Disney+: Normal launch + profile select")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Disney+: Waiting 30s for cold start...")
        delay(30000)

        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            Log.i(TAG, "Disney+: Attempting PIN entry")
            enterDisneyPinFromStartPosition(pin)
            delay(5000)
        } else {
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ profile select")
            delay(8000)
        }

        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "D+ force play")
        delay(3000)

        Log.i(TAG, "Disney+: Done")
    }

    /**
     * DISNEY+ — Search + navigate to specific season/episode
     *
     * BUG FIX: KEYCODE_SEARCH opens Google Gemini (Katniss) on Google TV, NOT Disney+'s
     * own search. typeTextViaAdb() also fails — Disney+ uses a custom all-button keyboard.
     *
     * SEARCH PATH (verified March 2026, Onn Google TV — live ADB dump confirmed):
     * 1. After home loads: DPAD_LEFT → sidebar opens, focus lands on "Home" (item 3 of 9)
     *    NOTE: Do NOT press UP first — it goes to the top tab bar ("For You"), not the sidebar.
     * 2. DPAD_UP → focus moves to "Search" (item 2)
     * 3. DPAD_CENTER → search keyboard appears, focus lands on 'a' (row 0, col 0)
     * 4. Type via typeDisney7Keyboard() (7-col layout: a-g, h-n, o-u, v-z+1-2, 3-9, 0)
     * 5. RIGHT×(7-endCol) → jumps from keyboard directly to first result (col 1)
     *    NOTE: No DOWN needed — already on the correct result row after crossing keyboard.
     *
     * SEASON/EPISODE (FIXED March 2026 — live ADB verified on Bear in the Big Blue House):
     * 6. CENTER → first result opens show page (PLAY button focused)
     * 7. DOWN×1 → EPISODES tab (not SUGGESTED or DETAILS)
     * 8. DOWN×1 → Episode 1 card (right side of split view)
     * 9. LEFT → Season sidebar (Season 1 focused, vertical list)
     * 10. UP×10 → Safe scroll to Season 1 (handles any starting position)
     * 11. DOWN×(season-1) → Target season
     * 12. CENTER → Select season (episodes reload for that season)
     * 13. RIGHT → Episode 1 of selected season (right side)
     * 14. DOWN×(episode-1) → Target episode
     * 15. CENTER → Play
     *
     * KEY INSIGHT: Seasons are a VERTICAL sidebar on the LEFT, NOT horizontal tabs.
     * The old code did RIGHT×(season-1) from EPISODES tab → navigated to SUGGESTED/DETAILS tabs instead.
     */
    private suspend fun launchDisneyPlusWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Disney+: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Disney+: Waiting 30s for cold start...")
        delay(30000)
        if (checkAborted()) return

        // Profile bypass (only if PIN is set).
        // VERIFIED March 2026: single-profile accounts skip the "Who's Watching?" screen entirely
        // and land directly on the home screen after cold start. DO NOT send DPAD_CENTER on the
        // home screen — it plays the featured content and breaks all subsequent navigation.
        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            enterDisneyPinFromStartPosition(pin)
            delay(5000)
        }
        // No CENTER press for single-profile accounts — already on home screen.

        if (checkAborted()) return

        // Open search via sidebar
        // VERIFIED March 2026 (live ADB dump confirmed):
        //   From home content: DPAD_LEFT → sidebar "Home" (item 3 of 9)
        //   UP×1 → sidebar "Search" (item 2 of 9)
        //   CENTER → search keyboard opens, focus lands on 'a' (row 0, col 0)
        // NOTE: The initial UP before LEFT was WRONG — it went to the top tab bar, not the sidebar.
        Log.i(TAG, "Disney+: Opening search via sidebar (LEFT→UP→CENTER)")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "D+ left to sidebar (Home item)")
        delay(800)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "D+ up to Search item")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ open search")
        delay(3000)  // Wait for search keyboard to fully appear

        if (checkAborted()) return

        // Type show name via 7-col DPAD keyboard.
        // VERIFIED March 2026: keyboard opens with focus on 'a' (row 0, col 0).
        // typeDisney7Keyboard() row-reset strategy is confirmed correct.
        // Use up to 15 chars for specificity — 7 was too short and gave ambiguous results.
        Log.i(TAG, "Disney+: Typing '$searchQuery' via 7-col keyboard")
        val queryToType = searchQuery.lowercase().filter { it.isLetterOrDigit() }.take(15)
        val (_, endCol) = typeDisney7Keyboard(queryToType)
        delay(3000)  // Wait for search results to populate

        if (checkAborted()) return

        // Navigate from keyboard to first result.
        // VERIFIED March 2026: keyboard is 7 cols (0-6). From endCol, pressing (7-endCol) RIGHT
        // presses crosses remaining keyboard columns and lands on the FIRST result (col 1).
        // No DOWN needed — we are already in the correct row for the top result.
        val rightsToResults = (7 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Disney+: RIGHT×$rightsToResults to first result")
        repeat(rightsToResults) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ to results"); delay(250) }
        delay(800)

        // Select the first result — no DOWN press needed (already on row 1 of results)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ select show")
        delay(6000)

        if (checkAborted()) return

        // Show page layout (verified March 2026 via live ADB on "Bear in the Big Blue House"):
        //   [PLAY button / Hero]                    ← initial focus
        //   [EPISODES | SUGGESTED | DETAILS tabs]   ← content tab row
        //   [Season sidebar (left) | Episode list (right)]
        //     Season 1          Ep1   Ep2   Ep3 ...  (vertical list)
        //     Season 2
        //     Season 3
        //
        // KEY: Seasons are a VERTICAL sidebar on the LEFT, NOT horizontal tabs.

        // ── SEASON / EPISODE NAVIGATION ──
        // Disney+ show page layout (verified March 2026):
        //   [PLAY button / Hero]                      ← initial focus
        //   [EPISODES | SUGGESTED | DETAILS tabs]     ← content tab row
        //   [Episode list (right) | Season sidebar (left)]
        //
        // IMPORTANT: Seasons are a VERTICAL sidebar on the LEFT.
        // After pressing DOWN twice and LEFT, Season 1 is ALREADY focused.
        // DO NOT press UP×10 — it overshoots past the sidebar into the tab row above!

        // DOWN×1 → PLAY button → EPISODES tab
        Log.i(TAG, "Disney+: Navigating to S${season}E${episode}")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to EPISODES tab")
        delay(1000)

        // DOWN×1 → EPISODES tab → Episode 1 card (right side, Season 1)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to Episode 1")
        delay(1000)

        // LEFT → Episode card → Season sidebar (Season 1 focused)
        Log.i(TAG, "Disney+: LEFT to season sidebar")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "D+ to season sidebar")
        delay(1000)

        // Season 1 is already focused after LEFT. Navigate DOWN×(season-1) to target.
        // NO UP×10 — that overshoots past the sidebar into the EPISODES/SUGGESTED tabs!
        Log.i(TAG, "Disney+: Navigating to Season $season (DOWN×${season - 1})")
        if (season > 1) {
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ season↓")
                delay(500)
            }
        }

        // CENTER → Select the target season (episodes reload for that season)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ select season")
        delay(4000)  // Wait for episode list to reload (increased from 3s)

        if (checkAborted()) return

        // DOWN → Into episode cards (episodes are below the season selector)
        // After CENTER, focus stays on the season. DOWN moves into the episode area.
        Log.i(TAG, "Disney+: DOWN to episodes, then DOWN×${episode - 1} to Episode $episode")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to episode list")
        delay(1000)

        // DOWN×(episode-1) → Target episode (episodes are stacked vertically)
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ episode↓")
                delay(500)
            }
        }
        delay(600)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ play episode")
        delay(5000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "D+ force play")
        delay(3000)

        Log.i(TAG, "Disney+: Done (S${season}E${episode})")
    }

    /**
     * NETFLIX — TESTED Feb 2026
     * Deep link (nflx:// + source=30) auto-plays content directly.
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
     * PARAMOUNT+ — Search + navigate to specific season/episode
     *
     * VERIFIED March 2026 (live ADB on Onn Google TV):
     *
     * COLD START:
     * P+ may or may not show a "Who's Watching?" profile picker.
     * Some accounts go directly to home. Code handles both cases by pressing
     * CENTER (harmless if already on home) then navigating.
     *
     * SIDEBAR LAYOUT (verified via ADB, 9 icons at x=28-124):
     * Item 1 (y≈226-298): SEARCH ← topmost functional item
     * Item 2 (y≈298-370): Home
     * Item 3 (y≈370-442): Shows
     * Items 4-9: Movies, Live TV, My List, Kids, Settings, Profile
     * Two items above Search: P+ logo and profile icon (not Search).
     *
     * SEARCH NAVIGATION:
     * 1. From home content: DOWN→focus content row, LEFT→sidebar
     * 2. UP×10 → safely reach topmost sidebar item
     * 3. DOWN×2 → Search icon (skip logo + profile items above Search)
     * 4. CENTER → search screen opens
     * 5. DOWN×1 → keyboard focused at 'a' (row 0 col 0)
     * 6. typePvKeyboard(query) — confirmed 6-col layout (a-f, g-l, m-r, s-x, y-z+1-4, 5-0)
     * 7. RIGHT×(6-endCol) → results panel
     * 8. DOWN×1 → first show card, CENTER → show detail page
     *
     * EPISODE NAVIGATION (P+ show detail page):
     * - Hero / Watch button (initial focus)
     * - Season tabs row (horizontal: S1, S2, S3...) — RIGHT×(season-1) to target
     * - Episode cards row (horizontal: E1, E2, E3...) — RIGHT×(episode-1) to target
     */
    private suspend fun launchParamountWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Paramount+: Search mode for '$searchQuery' (S${season}E${episode})")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Paramount+: Waiting 30s for cold start...")
        delay(30000)
        if (checkAborted()) return

        // DO NOT press CENTER on cold start — single-profile accounts go straight to home,
        // and CENTER clicks the featured/hero content, breaking all navigation.
        // Instead, go directly to the sidebar.

        if (checkAborted()) return

        // Navigate to sidebar: LEFT from home content area goes to sidebar
        Log.i(TAG, "Paramount+: Navigating to sidebar (no CENTER press)")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "P+ to sidebar")
        delay(1000)

        // UP×10 safely scrolls to topmost sidebar item (logo area)
        repeat(10) { sendKey(KeyEvent.KEYCODE_DPAD_UP, "P+ sidebar↑"); delay(200) }
        delay(500)

        // DOWN×1 → Search icon (first interactive item below the logo at top)
        // VERIFIED: Top=[72,47][276,143] (logo), DOWN×1=[72,112][253,208] (Search)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ sidebar↓ 1 (Search)"); delay(300)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ open search")
        delay(4000)

        if (checkAborted()) return

        // Keyboard activation: DOWN×1 focuses keyboard at 'a' (row 0, col 0)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ activate keyboard")
        delay(500)

        // Type show name via 6-col DPAD keyboard (same layout as Prime Video)
        val queryToType = searchQuery.lowercase().filter { it.isLetterOrDigit() }.take(8)
        Log.i(TAG, "Paramount+: Typing '$queryToType'")
        val (_, endCol) = typePvKeyboard(queryToType)
        delay(3000)

        if (checkAborted()) return

        // RIGHT×(6-endCol) jumps from keyboard into results panel
        val rightsToResults = (6 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Paramount+: RIGHT×$rightsToResults to results panel")
        repeat(rightsToResults) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "P+ to results"); delay(200) }
        delay(500)

        // First result card — may need DOWN×1 if results start below the search suggestions row
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ down to show card")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ select show")
        delay(6000)

        if (checkAborted()) return

        // ── SEASON / EPISODE NAVIGATION ──
        // P+ show detail: hero button → [DOWN] → season tabs (S1,S2,S3...) → [DOWN] → episode cards
        Log.i(TAG, "Paramount+: Navigating to S${season}E${episode}")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ to season tabs")
        delay(800)

        // Navigate to target season (S1 is focused by default — RIGHT×(season-1))
        if (season > 1) {
            Log.i(TAG, "Paramount+: Selecting Season $season")
            repeat(season - 1) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "P+ season→"); delay(400) }
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ select season")
            delay(2000)
        }

        // DOWN×1 → episode cards row
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ to episode cards")
        delay(600)

        // Navigate to target episode (E1 is focused by default — RIGHT×(episode-1))
        if (episode > 1) {
            Log.i(TAG, "Paramount+: Selecting Episode $episode")
            repeat(episode - 1) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "P+ episode→"); delay(350) }
        }
        delay(500)

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ play episode")
        delay(4000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "P+ force play")
        delay(3000)

        Log.i(TAG, "Paramount+: Done — S${season}E${episode} of '$searchQuery'")
    }

    /**
     * PRIME VIDEO — Search + navigate to specific season/episode
     *
     * NAVIGATION DISCOVERY (tested March 2026 on Onn Google TV):
     * - KEYCODE_SEARCH opens Google Katniss voice assistant — do NOT use it.
     *   FIX: Use DPAD_LEFT + DPAD_UP + DPAD_CENTER to open Prime Video's own search sidebar.
     * - ADB `input text` doesn't work on Prime Video's custom React Native keyboard.
     *   FIX: Navigate keyboard letter-by-letter with DPAD + CENTER (typePvKeyboard).
     *
     * SEARCH NAVIGATION PATH (verified working):
     * 1. DPAD_LEFT + UP + CENTER → Opens search screen (keyboard 'a' at row 0 col 0 focused)
     * 2. typePvKeyboard(first5chars) → Types via DPAD navigation key-by-key
     * 3. RIGHT×(6-lastCol) → Jumps past keyboard to suggestion chips row
     * 4. DOWN×1 → First content card (our show) focused
     * 5. CENTER → Show detail page opens
     *
     * EPISODE NAVIGATION PATH:
     * Prime Video show page layout (for subscription shows):
     *   [Watch Now / Resume button]   ← initial focus
     *   [Season X ▾ dropdown]         ← DOWN×1 from Watch Now
     *   [Episode cards row]           ← DOWN×1 from season dropdown
     *
     * 6. DOWN×1 → Season dropdown button
     * 7. CENTER → Season dropdown opens
     * 8. UP×15 → Scroll to Season 1 safely
     * 9. DOWN×(season-1) → Navigate to target season
     * 10. CENTER → Season selected; focus returns to dropdown button
     * 11. DOWN×1 → Episode 1 card focused
     * 12. RIGHT×(episode-1) → Target episode card
     * 13. CENTER → Play; MEDIA_PLAY force-starts
     */
    private suspend fun launchPrimeVideoWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Prime Video: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Prime Video: Waiting 18s for cold start...")
        delay(18000)

        if (checkAborted()) return

        // Open search via sidebar navigation.
        // NOTE: KEYCODE_SEARCH (84) opens Google Katniss voice assistant on this device — do NOT use.
        Log.i(TAG, "Prime Video: Opening search via sidebar (DPAD_LEFT + UP + CENTER)")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "PV open sidebar")
        delay(1000)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "PV focus search")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV open search")
        delay(2500)

        // Type first 5 chars of show name via keyboard navigation.
        // ADB `input text` does NOT work on Prime Video's custom keyboard.
        val queryToType = searchQuery.lowercase().filter { it.isLetterOrDigit() }.take(5)
        Log.i(TAG, "Prime Video: Typing '$queryToType' via keyboard navigation")
        val (_, endCol) = typePvKeyboard(queryToType)
        delay(1500)

        if (checkAborted()) return

        // Navigate from keyboard to suggestion chips row.
        // From keyboard column C, RIGHT×(6-C) jumps past the keyboard into the results panel.
        val rightsToResults = (6 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Prime Video: Jumping to results (RIGHT×$rightsToResults)")
        repeat(rightsToResults) {
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PV to chips")
            delay(200)
        }
        delay(300)

        // DOWN×1: suggestion chips → first content card (our show)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV to first result card")
        delay(600)

        // Open show detail page
        Log.i(TAG, "Prime Video: Opening show detail page")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV open show")
        delay(6000)  // Increased: show page needs time to fully load

        if (checkAborted()) return

        // DO NOT press BACK — it navigates AWAY from the show page entirely,
        // losing the show we just searched for. If a trailer auto-plays, DOWN
        // navigation will still work to reach the season dropdown.

        // SHOW PAGE LAYOUT (Prime Video, subscription shows):
        //   [Play / Resume button]   ← initial focus
        //   [Season X ▾ dropdown]    ← below (DOWN×1)
        //   [Episode cards row]      ← below dropdown (DOWN×1 from dropdown)

        // Navigate to season dropdown — DOWN×1 from hero/play button
        Log.i(TAG, "Prime Video: Navigating to Season dropdown (DOWN×1)")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV to season dropdown")
        delay(800)

        // Open season dropdown
        Log.i(TAG, "Prime Video: Opening season dropdown")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV open season dropdown")
        delay(1500)

        // Scroll UP×15 to safely reach Season 1 in the dropdown list
        Log.i(TAG, "Prime Video: Scrolling to Season 1 in dropdown")
        repeat(15) {
            sendKey(KeyEvent.KEYCODE_DPAD_UP, "PV season↑ to S1")
            delay(200)
        }
        delay(400)

        // Navigate DOWN to target season
        Log.i(TAG, "Prime Video: Selecting Season $season")
        if (season > 1) {
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV season↓")
                delay(350)
            }
        }
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV select season")
        delay(3000)  // Increased: episodes need time to load after season change

        if (checkAborted()) return

        // DOWN×1 → Season dropdown → Episode 1 card row
        Log.i(TAG, "Prime Video: Navigating to Episode $episode")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV to episode row")
        delay(600)

        // Navigate RIGHT to target episode (episodes are horizontal cards)
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PV episode→")
                delay(350)
            }
        }
        delay(500)

        // Play the episode
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV play")
        delay(4000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "PV force play")
        delay(3000)

        Log.i(TAG, "Prime Video: Done (S${season}E${episode})")
    }

    /**
     * Navigate Prime Video's on-screen keyboard and type a string letter by letter.
     *
     * Keyboard layout (verified March 2026, Onn Google TV):
     *   Row 0: a  b  c  d  e  f
     *   Row 1: g  h  i  j  k  l
     *   Row 2: m  n  o  p  q  r
     *   Row 3: s  t  u  v  w  x
     *   Row 4: y  z  1  2  3  4
     *   Row 5: 5  6  7  8  9  0
     *
     * Starting position: row=0, col=0 ('a'). Returns (row, col) of last key pressed,
     * which is used to calculate how many RIGHT presses are needed to reach the results panel.
     */
    private suspend fun typePvKeyboard(text: String): Pair<Int, Int> {
        data class Pos(val row: Int, val col: Int)

        val charMap = mapOf(
            'a' to Pos(0,0), 'b' to Pos(0,1), 'c' to Pos(0,2), 'd' to Pos(0,3), 'e' to Pos(0,4), 'f' to Pos(0,5),
            'g' to Pos(1,0), 'h' to Pos(1,1), 'i' to Pos(1,2), 'j' to Pos(1,3), 'k' to Pos(1,4), 'l' to Pos(1,5),
            'm' to Pos(2,0), 'n' to Pos(2,1), 'o' to Pos(2,2), 'p' to Pos(2,3), 'q' to Pos(2,4), 'r' to Pos(2,5),
            's' to Pos(3,0), 't' to Pos(3,1), 'u' to Pos(3,2), 'v' to Pos(3,3), 'w' to Pos(3,4), 'x' to Pos(3,5),
            'y' to Pos(4,0), 'z' to Pos(4,1),
            '1' to Pos(4,2), '2' to Pos(4,3), '3' to Pos(4,4), '4' to Pos(4,5),
            '5' to Pos(5,0), '6' to Pos(5,1), '7' to Pos(5,2), '8' to Pos(5,3), '9' to Pos(5,4), '0' to Pos(5,5)
        )

        var curRow = 0
        var curCol = 0

        for (ch in text) {
            val target = charMap[ch] ?: continue  // Skip unmapped chars (spaces, punctuation)

            val rowDiff = target.row - curRow
            if (rowDiff > 0) repeat(rowDiff)   { sendKey(KeyEvent.KEYCODE_DPAD_DOWN,  "PV kbd↓ '$ch'"); delay(200) }
            else if (rowDiff < 0) repeat(-rowDiff) { sendKey(KeyEvent.KEYCODE_DPAD_UP,   "PV kbd↑ '$ch'"); delay(200) }

            val colDiff = target.col - curCol
            if (colDiff > 0) repeat(colDiff)   { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PV kbd→ '$ch'"); delay(200) }
            else if (colDiff < 0) repeat(-colDiff) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT,  "PV kbd← '$ch'"); delay(200) }

            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV type '$ch'")
            delay(300)

            curRow = target.row
            curCol = target.col
        }

        return Pair(curRow, curCol)
    }

    /**
     * Navigate Disney+'s on-screen keyboard and type a string letter by letter.
     *
     * Keyboard layout (verified March 2026, Onn Google TV, Disney+ app):
     *   Row 0: a  b  c  d  e  f  g   (7 columns)
     *   Row 1: h  i  j  k  l  m  n
     *   Row 2: o  p  q  r  s  t  u
     *   Row 3: v  w  x  y  z  1  2
     *   Row 4: 3  4  5  6  7  8  9
     *   Row 5: 0
     *
     * Starting position: row=0, col=0 ('a').
     * Returns (0, col) of the last key pressed — always resets to row 0 after each char.
     * Caller uses (7 - endCol) RIGHT presses to reach the results panel.
     *
     * NOTE: typeTextViaAdb() does NOT work on Disney+ (no EditText in search field).
     *
     * ROW-RESET STRATEGY: Before navigating to each character, we press UP back to row 0
     * first. This avoids relying on arbitrary UP navigation mid-sequence (which is
     * unreliable on some TV keyboards). Each character is typed using only DOWN navigation
     * from row 0, making the sequence predictable and robust.
     */
    private suspend fun typeDisney7Keyboard(text: String): Pair<Int, Int> {
        data class Pos(val row: Int, val col: Int)

        val charMap = mapOf(
            'a' to Pos(0,0), 'b' to Pos(0,1), 'c' to Pos(0,2), 'd' to Pos(0,3), 'e' to Pos(0,4), 'f' to Pos(0,5), 'g' to Pos(0,6),
            'h' to Pos(1,0), 'i' to Pos(1,1), 'j' to Pos(1,2), 'k' to Pos(1,3), 'l' to Pos(1,4), 'm' to Pos(1,5), 'n' to Pos(1,6),
            'o' to Pos(2,0), 'p' to Pos(2,1), 'q' to Pos(2,2), 'r' to Pos(2,3), 's' to Pos(2,4), 't' to Pos(2,5), 'u' to Pos(2,6),
            'v' to Pos(3,0), 'w' to Pos(3,1), 'x' to Pos(3,2), 'y' to Pos(3,3), 'z' to Pos(3,4),
            '1' to Pos(3,5), '2' to Pos(3,6),
            '3' to Pos(4,0), '4' to Pos(4,1), '5' to Pos(4,2), '6' to Pos(4,3), '7' to Pos(4,4), '8' to Pos(4,5), '9' to Pos(4,6),
            '0' to Pos(5,0)
        )

        var curRow = 0
        var curCol = 0

        for (ch in text) {
            val target = charMap[ch] ?: continue  // Skip unmapped chars

            // Step 1: Reset to row 0 by pressing UP from current row.
            // This way each character always navigates DOWN-only from row 0,
            // avoiding unreliable mid-sequence UP navigation.
            if (curRow > 0) {
                repeat(curRow) { sendKey(KeyEvent.KEYCODE_DPAD_UP, "D+ kbd reset↑ '$ch'"); delay(250) }
                curRow = 0
            }

            // Step 2: Adjust column while on row 0 (LEFT/RIGHT from curCol to target.col)
            val colDiff = target.col - curCol
            if (colDiff > 0) repeat(colDiff)    { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ kbd→ '$ch'"); delay(250) }
            else if (colDiff < 0) repeat(-colDiff) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT,  "D+ kbd← '$ch'"); delay(250) }
            curCol = target.col

            // Step 3: Navigate DOWN to target row
            if (target.row > 0) {
                repeat(target.row) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ kbd↓ '$ch'"); delay(250) }
            }
            curRow = target.row

            // Step 4: Press CENTER to type the character
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ type '$ch'")
            delay(500)
        }

        // After all characters, reset to row 0 so caller can reliably RIGHT-to-results
        if (curRow > 0) {
            repeat(curRow) { sendKey(KeyEvent.KEYCODE_DPAD_UP, "D+ kbd final↑"); delay(250) }
            curRow = 0
        }

        return Pair(0, curCol)
    }

    /**
     * GENERIC — For YouTube, Tubi, and apps without a specific recipe.
     * Uses deep link if available, otherwise normal launch.
     */
    private suspend fun launchGeneric(packageName: String, deepLinkUri: String, extras: Map<String, String>) {
        val hasDeepLink = deepLinkUri.isNotBlank() && deepLinkUri != "APP_ONLY"

        if (hasDeepLink) {
            Log.i(TAG, "Generic deep link launch for $packageName")
            if (!sendDeepLink(packageName, deepLinkUri, extras)) return
        } else {
            Log.i(TAG, "Generic normal launch for $packageName")
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

    private fun getDisneyPin(): String {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString("disney_pin", "") ?: ""
    }

    /**
     * Enter Disney+ PIN starting from the PIN screen's initial focus position.
     * Initial focus lands on "5" (row 1, col 1). Navigate to DELETE first to clear, then enter PIN.
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
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ DELETE #${it + 1}")
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

        // Start from DELETE position (row 3, col 2)
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
    //  VOLUME CONTROL — Feature A: Ramp DOWN to 0 then UP to target
    // =========================================================================

    /**
     * Set TV volume by ramping DOWN to 0 first, then UP to the target level.
     *
     * WHY: setStreamVolume() is unreliable (doesn't always update the TV's physical volume).
     * KEY EVENTS are reliable: they interact with the same hardware path as the remote control.
     *
     * Starting from unknown volume → ramp ALL THE WAY down to 0 first → then up to target.
     * This guarantees the same end result every time, regardless of starting volume.
     */
    private suspend fun setTvVolume(targetVolume: Int) {
        if (targetVolume < 0) return

        Log.i(TAG, "Volume: ramping DOWN to 0...")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Ramp ALL THE WAY DOWN (max steps + 5 extra for safety)
        repeat(maxSteps + 5) {
            sendKey(KeyEvent.KEYCODE_VOLUME_DOWN, "vol-")
            delay(60)  // 60ms between presses — TV responds within this window
        }
        delay(400)

        // targetVolume is the EXACT number of UP presses — no scaling.
        // User sets volume=15 → exactly 15 VOLUME_UP presses happen.
        // This is the same as pressing the remote UP button 15 times from 0.
        Log.i(TAG, "Volume: pressing UP $targetVolume times (max device steps: $maxSteps)")
        repeat(targetVolume) {
            sendKey(KeyEvent.KEYCODE_VOLUME_UP, "vol+")
            delay(60)
        }

        Log.i(TAG, "Volume: done — set to $targetVolume presses from 0")
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /**
     * Type text into the currently focused field using ADB shell input text.
     * Works with ADB over TCP (localhost:5555).
     * Spaces are encoded as %s for compatibility.
     */
    private suspend fun typeTextViaAdb(text: String) {
        withContext(Dispatchers.IO) {
            // Remove characters that break shell command parsing
            val safe = text.replace("'", "")
                .replace("\"", "")
                .replace("`", "")
                .replace("(", "").replace(")", "")
                .replace("&", "").replace(";", "")
                .replace("\\", "")
            // Encode spaces as %s (ADB input text compatibility)
            val escaped = safe.replace(" ", "%s")
            Log.d(TAG, "ADB input text: $escaped")
            AdbShell.sendShellCommand("input text $escaped")
        }
        delay(500)
    }

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
        "com.google.android.youtube.tv" -> 12000
        "com.netflix.ninja" -> 15000
        "com.cbs.ott" -> 18000
        "com.amazon.amazonvideo.livingroom" -> 18000
        "com.tubitv" -> 15000
        else -> 20000
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
        try { unregisterReceiver(homeReceiver) } catch (_: Exception) {}
        Log.i(TAG, "ContentLaunchService destroyed")
    }

    /**
     * Returns true if the user pressed HOME (or Recents) — caller should immediately return.
     */
    private fun checkAborted(): Boolean {
        if (homePressed) Log.i(TAG, "Navigation aborted: HOME was pressed")
        return homePressed
    }
}
