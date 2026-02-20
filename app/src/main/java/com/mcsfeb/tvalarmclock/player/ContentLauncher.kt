package com.mcsfeb.tvalarmclock.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.service.AdbShell
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import com.mcsfeb.tvalarmclock.service.ContentLaunchService
import kotlinx.coroutines.*

/**
 * ContentLauncher - The central logic for launching specific content on TV apps.
 * 
 * Implements a "hybrid" strategy:
 * 1. Checks for a known "best method" (deep link vs automation).
 * 2. Tries a prioritized chain of deep links (e.g., Netflix "watch" -> Netflix "title").
 * 3. Verifies success by checking if the target app is in the foreground.
 * 4. Falls back to basic app launch + AccessibilityService automation.
 */
class ContentLauncher(private val context: Context) {

    companion object {
        private const val TAG = "ContentLauncher"
        private const val PREFS_NAME = "content_launcher_prefs"
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        // Singleton instance
        @Volatile
        private var INSTANCE: ContentLauncher? = null
        
        fun getInstance(context: Context): ContentLauncher {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContentLauncher(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Main entry point to launch content.
     *
     * Uses ContentLaunchService (foreground service) to survive process freezing.
     * When our activity launches a streaming app and goes to background, Android
     * freezes our process. The foreground service stays alive to:
     * 1. Send the deep link intent
     * 2. Wait for the app to cold-start
     * 3. Send DPAD_CENTER to bypass profile pickers
     *
     * @param packageName The package name of the target app (e.g., "com.netflix.ninja")
     * @param contentType "episode" | "movie" | "live"
     * @param identifiers Map of IDs: "episodeId", "titleId", "showName", "season", "episode", "channelName"
     */
    fun launchContent(
        packageName: String,
        contentType: String,
        identifiers: Map<String, String>
    ) {
        Log.i(TAG, "launchContent: $packageName, type=$contentType, ids=$identifiers")

        // Build the best deep link URI
        val deepLinks = getPrioritizedDeepLinks(packageName, identifiers)
        if (deepLinks.isNotEmpty()) {
            val uri = deepLinks.first().toString()
            Log.i(TAG, "Using foreground service to launch: $uri")

            // Build extras map (e.g., Netflix source=30)
            val extras = mutableMapOf<String, String>()
            if (packageName == "com.netflix.ninja") {
                extras["source"] = "30"
            }

            ContentLaunchService.launch(context, packageName, uri, extras)
        } else {
            // No deep links available — fall back to basic app launch + automation
            Log.i(TAG, "No deep links for $packageName, falling back to automation")
            scope.launch {
                launchWithAutomation(packageName, contentType, identifiers,
                    "best_method_${packageName}_default")
            }
        }
    }

    private suspend fun tryLaunchSequence(
        packageName: String,
        contentType: String,
        identifiers: Map<String, String>
    ): Boolean {
        val uniqueKey = "best_method_${packageName}_${identifiers["episodeId"] ?: identifiers["channelName"] ?: "default"}"
        val bestMethod = prefs.getString(uniqueKey, null)

        // 1. Try saved best method first
        if (bestMethod == "automation") {
            Log.d(TAG, "Saved method is 'automation'. Skipping deep links.")
            return launchWithAutomation(packageName, contentType, identifiers, uniqueKey)
        }

        // 2. Force-stop the app first if needed (Hulu requires this)
        if (packageName == "com.hulu.livingroomplus") {
            try {
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
                delay(1000)
            } catch (_: Exception) {}
        }

        // 3. Try Deep Links
        val deepLinks = getPrioritizedDeepLinks(packageName, identifiers)
        for ((index, uri) in deepLinks.withIndex()) {
            Log.d(TAG, "Attempting Deep Link #$index: $uri")

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Add specific extras for Netflix
                if (packageName == "com.netflix.ninja" && uri.toString().contains("watch")) {
                    putExtra("source", "30") // Force playback — REQUIRED or lands on home
                }
            }

            try {
                context.startActivity(intent)

                // Wait time depends on the app — cold starts are slow on TV devices
                val waitTime = getAppLoadWaitTime(packageName)
                Log.d(TAG, "Waiting ${waitTime}ms for $packageName to load...")
                delay(waitTime)

                // Profile bypass: press CENTER to dismiss profile picker if shown
                performProfileBypass(packageName)

                // Give extra time after profile bypass for content to load
                delay(5000)

                if (isAppForeground(packageName)) {
                    Log.i(TAG, "Deep link success! App $packageName is foreground.")
                    prefs.edit { putString(uniqueKey, "deep_link_$index") }
                    return true
                } else {
                    Log.w(TAG, "Deep link failed: App not in foreground.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Deep link error: ${e.message}")
            }
        }

        // 4. Fallback to Automation
        Log.i(TAG, "Deep links failed. Falling back to Automation.")
        return launchWithAutomation(packageName, contentType, identifiers, uniqueKey)
    }

    /**
     * Get the appropriate wait time for each app to cold-start on TV.
     * Tested on Onn Google TV (Feb 2026) — actual measured times.
     */
    private fun getAppLoadWaitTime(packageName: String): Long = when (packageName) {
        "com.sling" -> 30000               // Sling: profile picker at ~25s
        "com.wbd.stream" -> 30000          // HBO Max: very slow, WebView-based
        "com.hulu.livingroomplus" -> 25000  // Hulu: slow, WebView-based
        "com.disney.disneyplus" -> 25000    // Disney+: slow, custom renderer
        "com.cbs.ott" -> 20000             // Paramount+: moderately slow
        "com.amazon.amazonvideo.livingroom" -> 20000  // Prime Video: moderate
        "com.netflix.ninja" -> 15000       // Netflix: custom Gibbon renderer
        "com.google.android.youtube.tv" -> 12000      // YouTube: relatively fast
        else -> 20000
    }

    /**
     * Press DPAD_CENTER to bypass profile picker screens.
     * Most TV apps show a "Who's Watching?" screen on launch.
     * Tested: simple CENTER press selects the first (default) profile.
     *
     * Uses AdbShell (TCP to localhost:5555) because apps can't inject keys into
     * other apps. ADB shell runs with elevated permissions.
     */
    private suspend fun performProfileBypass(packageName: String) {
        val appsWithProfiles = setOf(
            "com.netflix.ninja",
            "com.wbd.stream",
            "com.hulu.livingroomplus",
            "com.sling",
            "com.cbs.ott",
            "com.disney.disneyplus"
        )

        if (packageName in appsWithProfiles) {
            Log.d(TAG, "Sending DPAD_CENTER for profile bypass on $packageName via AdbShell")

            // Initialize AdbShell if needed (generates RSA keys, opens TCP connection)
            AdbShell.init(context)

            // Run on IO thread since AdbShell uses blocking TCP
            withContext(Dispatchers.IO) {
                try {
                    val sent1 = AdbShell.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    Log.d(TAG, "Profile bypass CENTER #1: $sent1")
                } catch (e: Exception) {
                    Log.w(TAG, "Profile bypass CENTER #1 failed: ${e.message}")
                }
            }

            delay(2000)

            // Second press in case first one was absorbed by loading screen
            withContext(Dispatchers.IO) {
                try {
                    val sent2 = AdbShell.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                    Log.d(TAG, "Profile bypass CENTER #2: $sent2")
                } catch (e: Exception) {
                    Log.w(TAG, "Profile bypass CENTER #2 failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun launchWithAutomation(
        packageName: String,
        contentType: String,
        identifiers: Map<String, String>,
        uniqueKey: String
    ): Boolean {
        // Basic Launch
        val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: context.packageManager.getLaunchIntentForPackage(packageName)
        
        if (launchIntent == null) {
            Log.e(TAG, "Automation failed: App not installed or no launch intent.")
            return false
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launchIntent)
        
        // Short delay to allow activity start.
        // Wait 2.5s as requested by user.
        delay(2500)

        // Check if Accessibility Service is ready
        val service = AlarmAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "Automation failed: Accessibility Service not running.")
            return false
        }

        // Trigger Navigation
        val success = service.performContentNavigation(packageName, contentType, identifiers)
        if (success) {
            prefs.edit { putString(uniqueKey, "automation") }
        }
        return success
    }

    private fun isAppForeground(targetPkg: String): Boolean {
        // Use AccessibilityService to peek at current package
        val current = AlarmAccessibilityService.instance?.currentPackage
        Log.d(TAG, "Foreground check: current=$current, target=$targetPkg")
        return current == targetPkg || 
               StreamingApp.getAltPackageNamesForPackage(targetPkg).contains(current)
    }

    /**
     * Build prioritized deep link URIs for each app.
     * TESTED on Onn Google TV (Feb 2026) — these are the VERIFIED working formats.
     */
    private fun getPrioritizedDeepLinks(packageName: String, ids: Map<String, String>): List<Uri> {
        val list = mutableListOf<Uri>()
        val contentId = ids["episodeId"] ?: ids["id"] ?: ids["contentId"]
        val channelName = ids["channelName"] ?: ids["channel"]
        val titleId = ids["titleId"]
        val showName = ids["showName"] ?: ids["title"]

        when (packageName) {
            "com.netflix.ninja" -> {
                // TESTED: nflx:// scheme goes straight to playback with source=30
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("nflx://www.netflix.com/watch/$contentId"))
                    list.add(Uri.parse("https://www.netflix.com/watch/$contentId"))
                }
                // Fallback to title page
                val tid = titleId ?: contentId
                if (!tid.isNullOrBlank() && tid != contentId) {
                    list.add(Uri.parse("https://www.netflix.com/title/$tid"))
                }
            }

            "com.sling" -> {
                // TESTED Feb 2026: Deep link is BROKEN on cold start (player opens,
                // stream gets stuck at ExoPlayer shutter). Only works when Sling is
                // already warm/playing. ContentLaunchService handles this with a
                // two-phase approach: normal launch first, then deep link.
                if (!channelName.isNullOrBlank()) {
                    val encoded = Uri.encode(channelName)
                    list.add(Uri.parse("slingtv://watch.sling.com/watch/live?channelName=$encoded"))
                }
                // Fallback: open Sling to its guide
                list.add(Uri.parse("slingtv://open"))
            }

            "com.google.android.youtube.tv" -> {
                // TESTED: https:// auto-plays video. vnd.youtube: may NOT auto-play.
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.youtube.com/watch?v=$contentId"))
                    list.add(Uri.parse("vnd.youtube:$contentId"))
                }
            }

            "com.wbd.stream" -> {
                // TESTED: https://play.max.com is the verified authority
                // App is completely opaque but intent resolves correctly
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://play.max.com/video/watch/$contentId"))
                    list.add(Uri.parse("https://play.max.com/episode/$contentId"))
                    list.add(Uri.parse("https://play.max.com/movie/$contentId"))
                }
            }

            "com.hulu.livingroomplus" -> {
                // TESTED: https://www.hulu.com/watch/{uuid} format
                // Hulu is completely opaque; deep link is our only option
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.hulu.com/watch/$contentId"))
                    list.add(Uri.parse("hulu://video/$contentId"))
                }
            }

            "com.disney.disneyplus" -> {
                // TESTED: https://www.disneyplus.com/video/{id} resolves correctly
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.disneyplus.com/video/$contentId"))
                    list.add(Uri.parse("disneyplus://www.disneyplus.com/video/$contentId"))
                }
            }

            "com.cbs.ott" -> {
                // TESTED: https://www.paramountplus.com/watch/{id} and /live-tv/ work
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.paramountplus.com/watch/$contentId"))
                    list.add(Uri.parse("pplus://www.paramountplus.com/watch/$contentId"))
                }
                // Live TV fallback
                list.add(Uri.parse("https://www.paramountplus.com/live-tv/"))
            }

            "com.amazon.amazonvideo.livingroom" -> {
                // TESTED Feb 2026: Intent filter accepts watch.amazon.com authority.
                // Use ASIN-based URL for direct playback.
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("https://watch.amazon.com/watch?asin=$contentId"))
                    list.add(Uri.parse("https://app.primevideo.com/detail?gti=$contentId"))
                }
            }

            "com.tubitv" -> {
                // TESTED Feb 2026: tubitv:// scheme with media-playback authority for auto-play.
                // media-details for show page. live-news for live news section.
                if (!contentId.isNullOrBlank()) {
                    list.add(Uri.parse("tubitv://media-playback/$contentId"))
                    list.add(Uri.parse("tubitv://media-details/$contentId"))
                    list.add(Uri.parse("https://tubitv.com/movies/$contentId"))
                }
            }

            else -> {
                // Generic: try the format from StreamingApp enum if available
                if (!contentId.isNullOrBlank()) {
                    val app = StreamingApp.entries.find {
                        StreamingApp.getPackageName(it) == packageName
                    }
                    if (app != null) {
                        val format = app.deepLinkFormat
                        if (format.isNotBlank()) {
                            list.add(Uri.parse(format.replace("{id}", contentId)))
                        }
                    }
                }
            }
        }
        return list
    }

    fun findInstalledPackage(app: StreamingApp): String? {
        val pkgs = listOf(StreamingApp.getPackageName(app)) + StreamingApp.getAltPackageNames(app)
        for (pkg in pkgs) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun getInstalledApps() = StreamingApp.entries.filter { findInstalledPackage(it) != null }
}
