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
            "com.sling" -> launchSling()
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
     * SLING TV — TESTED Feb 2026
     *
     * Sling is always live TV: normal launch → profile bypass → auto-play last channel.
     *
     * BUG FIX (Bug 1): Reduced delay between CENTER (profile bypass) and MEDIA_PLAY
     * from 5000ms to 1000ms. This minimizes the visible pause/unpause glitch:
     * - CENTER dismisses profile picker (or briefly pauses if already playing)
     * - MEDIA_PLAY immediately overrides to force play state
     * - Result: <1 second pause instead of 5 seconds
     */
    private suspend fun launchSling() {
        Log.i(TAG, "Sling: Normal launch + CENTER + MEDIA_PLAY")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Sling: Waiting 35s for cold start (React Native)...")
        delay(35000)

        // CENTER to dismiss profile picker (if shown).
        // If no profile shown, CENTER toggles play/pause — MEDIA_PLAY immediately fixes it.
        // 100ms delay: so short that any accidental pause is imperceptible before MEDIA_PLAY overrides.
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Sling profile dismiss")
        delay(100)

        // MEDIA_PLAY always forces play state (overrides any pause from CENTER above)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Sling force play")
        delay(3000)

        Log.i(TAG, "Sling: Done (playing last channel)")
    }

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

        // Open search via sidebar (KEYCODE_SEARCH opens Katniss — do NOT use)
        Log.i(TAG, "HBO Max: Opening search via sidebar")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "HBO open sidebar")
        delay(1000)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "HBO focus search")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO open search")
        delay(2500)

        // Type show name — typeTextViaAdb WORKS on Max (standard text field, not custom keyboard)
        Log.i(TAG, "HBO Max: Typing '$searchQuery'")
        typeTextViaAdb(searchQuery)
        delay(2500)  // Wait for results to populate

        // RIGHT×6 jumps from keyboard col 0 to results panel; first result already focused
        Log.i(TAG, "HBO Max: RIGHT×6 to results panel")
        repeat(6) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "HBO to results"); delay(200) }
        delay(400)

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

        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "HBO play episode")
        delay(5000)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "HBO force play")
        delay(3000)

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
     * BUG FIX (Bug 4): Full search flow built from scratch.
     * Hulu is WebView-based (OPAQUE) — all navigation is blind timed DPAD.
     */
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
        Log.i(TAG, "Hulu: Waiting 25s for cold start...")
        delay(25000)

        // Profile bypass (cold start shows profile picker; CENTER selects first profile)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu profile")
        delay(8000)  // Increased: home screen needs time to fully load after profile select

        // Open search via sidebar (KEYCODE_SEARCH opens Katniss — do NOT use)
        Log.i(TAG, "Hulu: Opening search via sidebar")
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT, "Hulu open sidebar")
        delay(1000)
        sendKey(KeyEvent.KEYCODE_DPAD_UP, "Hulu focus search")
        delay(500)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu open search")
        delay(3000)  // Increased: wait for search keyboard to appear

        // Type first 5 chars via keyboard navigation (ADB input text doesn't work on Hulu)
        // Keyboard layout: 6×6 grid identical to Prime Video (row0=a-f, row1=g-l, …, row5=5-0)
        val queryToType = searchQuery.lowercase().filter { it.isLetterOrDigit() }.take(5)
        Log.i(TAG, "Hulu: Typing '$queryToType' via keyboard nav")
        val (_, endCol) = typePvKeyboard(queryToType)
        delay(2000)  // Increased: wait for search results to populate

        // RIGHT×(6-endCol) jumps from keyboard into results panel.
        // First result card is ALREADY focused after this — no DOWN needed.
        val rightsToResults = (6 - endCol).coerceAtLeast(1)
        Log.i(TAG, "Hulu: RIGHT×$rightsToResults to reach results panel")
        repeat(rightsToResults) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Hulu to results"); delay(250) }
        delay(600)

        // First result already focused → open show page
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "Hulu open show")
        delay(8000)  // Increased: show detail page load on Hulu can be slow

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
     * BUG FIX (Bug 4): Full episode selection built from scratch.
     * Disney+ is TRANSPARENT (exposes accessibility), so SEARCH key usually works.
     * Episodes in Disney+ are listed as rows scrolling DOWN (not right).
     */
    private suspend fun launchDisneyPlusWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Disney+: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Disney+: Waiting 30s for cold start...")
        delay(30000)

        // Profile bypass
        val pin = getDisneyPin()
        if (pin.isNotEmpty()) {
            enterDisneyPinFromStartPosition(pin)
            delay(5000)
        } else {
            sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ profile select")
            delay(8000)
        }

        // Open search
        Log.i(TAG, "Disney+: Opening search")
        sendKey(KeyEvent.KEYCODE_SEARCH, "D+ search key")
        delay(3000)

        // Type the show name
        Log.i(TAG, "Disney+: Typing '$searchQuery'")
        typeTextViaAdb(searchQuery)
        delay(4000)

        // Navigate to first result and select it
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to results")
        delay(600)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ select first result")
        delay(6000)

        // Show page layout:
        //   [Hero image / Play button]  ← initial focus after search result opens
        //   [Season tabs: S1 S2 S3 ...]  ← horizontal row, starts at S1
        //   [Episode list: E1, E2, E3 ...]  ← vertical, DOWN to navigate

        // DOWN×1 → Hero button → Season tabs row
        Log.i(TAG, "Disney+: Navigating to Season $season")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to season tabs")
        delay(800)

        // Disney+ starts focused on S1. Navigate RIGHT (season-1) times to target season.
        if (season > 1) {
            repeat(season - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "D+ season right")
                delay(350)
            }
        }
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "D+ select season")
        delay(3000)  // Wait for episode list to reload for selected season

        // DOWN×1 → Season tabs → Episode 1 in episode list
        Log.i(TAG, "Disney+: Navigating to Episode $episode")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ to episode list")
        delay(800)

        // Episodes are stacked vertically — DOWN to reach target episode
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "D+ episode down")
                delay(350)
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
     * PARAMOUNT+ — Search + play show (starts from "continue watching" episode)
     *
     * NAVIGATION DISCOVERY (tested March 2026 on Onn Google TV):
     * - Paramount+ uses Compose-based UI. DPAD navigation is limited.
     * - KEYCODE_SEARCH (84) opens Google's Katniss voice assistant (NOT P+ search).
     *   Katniss blocks all subsequent DPAD presses and cannot accept typed text.
     *   FIX: Use "input tap 62 274" to open P+'s own in-app search sidebar.
     *
     * SEARCH NAVIGATION PATH (verified with ADB/UIAutomator):
     * 1. Tap (62, 274) → P+'s in-app search opens, keyboard focus at 'a' key
     * 2. input text <show name> → types into search field, results appear
     * 3. RIGHT×6 from 'a' key → navigates to the Live TV card in results panel
     * 4. DOWN×1 → moves to the first VOD result card (the show)
     * 5. CENTER → VideoPlayerActivity launches (always plays "continue watching" episode)
     *
     * NOTE: Specific season/episode selection is NOT supported for Paramount+.
     * The P+ Compose UI doesn't expose season/episode controls via DPAD from
     * search results. The show always starts from the last-watched episode.
     * Season/episode params are accepted but ignored.
     */
    private suspend fun launchParamountWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Paramount+: Search mode for '$searchQuery' (S${season}E${episode} — will play continue-watching)")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Paramount+: Waiting 20s for cold start...")
        delay(20000)

        // After a force-stop, P+ always shows "Who's Watching?" profile selection first.
        // Press CENTER to select the default (first) profile, then wait for home screen to load.
        Log.i(TAG, "Paramount+: Selecting profile")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ profile select")
        delay(8000)  // Wait for home screen to fully load after profile selection

        // Open P+'s own in-app search via sidebar tap at (62, 274).
        // NOTE: KEYCODE_SEARCH (84) opens Google Katniss voice assistant instead — do NOT use it.
        Log.i(TAG, "Paramount+: Tapping search icon in sidebar")
        sendShell("input tap 62 274")
        delay(3000)  // Wait for search UI + keyboard to fully appear

        // Clear any stray characters (e.g., 'a' typed by the tap landing near keyboard focus)
        // Send DEL×10 to ensure the field is empty before typing our search term.
        Log.i(TAG, "Paramount+: Clearing search field (DEL×10)")
        repeat(10) {
            sendShell("input keyevent ${KeyEvent.KEYCODE_DEL}")
            delay(80)
        }
        delay(300)

        // Type the search query. After typing, 'a' key remains focused.
        Log.i(TAG, "Paramount+: Typing '$searchQuery'")
        typeTextViaAdb(searchQuery)
        delay(3000)  // Wait for search results to load in the right panel

        // Navigate from 'a' key (keyboard row 1) to the results panel:
        // RIGHT×6: a→b→c→d→e→f→(jumps to Live TV card in results panel at x=642)
        Log.i(TAG, "Paramount+: Navigating RIGHT to results panel")
        repeat(6) {
            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "P+ right to results")
            delay(200)
        }
        delay(500)

        // DOWN×1: Live TV card → first VOD result card (the show)
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "P+ down to show card")
        delay(500)

        // CENTER: Opens the show/episode detail page (ContentDetailsActivity)
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ select show")
        delay(6000)  // Wait for ContentDetailsActivity to load

        // Press CENTER again to click "WATCH NOW" on the detail page → launches VideoPlayerActivity.
        // If we landed directly on VideoPlayerActivity instead, CENTER just shows player controls
        // and MEDIA_PLAY immediately resumes — no harm done either way.
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "P+ press WATCH NOW")
        delay(4000)  // Wait for video to start loading

        // Force play in case video hasn't auto-started
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, "P+ force play")
        delay(3000)

        Log.i(TAG, "Paramount+: Done — playing '$searchQuery' (continue watching)")
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
     * SEARCH NAVIGATION PATH (verified with ADB/screenshots):
     * 1. DPAD_LEFT + UP + CENTER → Opens search screen (keyboard 'a' at row 0 col 0 focused)
     * 2. typePvKeyboard(first5chars) → Types via DPAD navigation key-by-key
     * 3. RIGHT×(6-lastCol) → Jumps past keyboard to suggestion chips row
     * 4. DOWN×1 → First content card (our show) focused
     * 5. CENTER → Show detail page opens
     *
     * EPISODE NAVIGATION PATH (verified with ADB/screenshots):
     * 6. DOWN×3 → Episode card focused at resume/progress position
     * 7. UP×1 → Season dropdown button focused
     * 8. CENTER → Season dropdown opens (shows current season at top)
     * 9. UP×10 → Scroll to Season 1 safely (handles up to 10-season shows)
     * 10. DOWN×(season-1) → Navigate to target season
     * 11. CENTER → Season selected; dropdown button regains focus
     * 12. DOWN×2 → Episode 1 card focused (with visible focus border)
     * 13. RIGHT×(episode-1) → Target episode card focused
     * 14. CENTER → Starts playing; MEDIA_PLAY force-starts
     */
    private suspend fun launchPrimeVideoWithSearch(searchQuery: String, season: Int, episode: Int) {
        Log.i(TAG, "Prime Video: Search mode for '$searchQuery' S${season}E${episode}")
        // NOTE: startActivity already called in performLaunch() (needsPreLaunch=true).
        Log.i(TAG, "Prime Video: Waiting 18s for cold start...")
        delay(18000)

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
        delay(5000)

        // Show page layout on Prime Video (React Native, Onn Google TV):
        //   [Resume / Watch button]  ← initial focus
        //   [Season X  ▾]  ← Season dropdown button (one row below Resume)
        //   [Episode cards: E1  E2  E3 ...]  ← horizontal row below season dropdown

        // DOWN×1 → Resume/Watch button → Season dropdown button
        Log.i(TAG, "Prime Video: Navigating to Season dropdown")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV to season dropdown")
        delay(600)

        // Open season dropdown
        Log.i(TAG, "Prime Video: Opening season dropdown")
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER, "PV open season dropdown")
        delay(1200)  // Dropdown animation takes ~1s

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
        delay(2500)  // Wait for episode list to reload after season change

        // DOWN×1 → Season dropdown → Episode 1 card row
        Log.i(TAG, "Prime Video: Navigating to Episode $episode")
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN, "PV to episode row")
        delay(600)

        // Navigate RIGHT to target episode (episodes are horizontal)
        if (episode > 1) {
            repeat(episode - 1) {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, "PV episode→")
                delay(300)
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
        Log.i(TAG, "ContentLaunchService destroyed")
    }
}
