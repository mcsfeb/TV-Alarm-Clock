package com.mcsfeb.tvalarmclock.player

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
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
        
        scope.launch {
            val success = tryLaunchSequence(packageName, contentType, identifiers)
            if (!success) {
                Log.e(TAG, "All launch methods failed for $packageName")
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

        // 2. Try Deep Links
        val deepLinks = getPrioritizedDeepLinks(packageName, identifiers)
        for ((index, uri) in deepLinks.withIndex()) {
            Log.d(TAG, "Attempting Deep Link #$index: $uri")
            
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Add specific extras for Netflix
                if (packageName == "com.netflix.ninja" && uri.toString().contains("watch")) {
                    putExtra("source", "30") // Force playback
                }
            }

            try {
                context.startActivity(intent)
                delay(6000) // Wait 6 seconds for app to load
                
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

        // 3. Fallback to Automation
        Log.i(TAG, "Deep links failed. Falling back to Automation.")
        return launchWithAutomation(packageName, contentType, identifiers, uniqueKey)
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

    private fun getPrioritizedDeepLinks(packageName: String, ids: Map<String, String>): List<Uri> {
        val list = mutableListOf<Uri>()
        val episodeId = ids["episodeId"] ?: ids["id"]
        val channelId = ids["channelName"] ?: ids["channel"] // Sling uses channel name often? No, ID.
        
        when (packageName) {
            "com.netflix.ninja" -> {
                // 1. Specific Episode Watch (source=30 added in intent creation)
                if (!episodeId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.netflix.com/watch/$episodeId"))
                }
                // 2. Title Details Fallback
                val titleId = ids["titleId"] ?: episodeId // fallback to episode ID if title ID missing
                if (!titleId.isNullOrBlank()) {
                    list.add(Uri.parse("https://www.netflix.com/title/$titleId"))
                }
                 // 3. nflx:// scheme
                if (!episodeId.isNullOrBlank()) {
                    list.add(Uri.parse("nflx://www.netflix.com/watch/$episodeId"))
                }
            }
            "com.sling" -> {
                // 1. Sling Watch URL
                if (!channelId.isNullOrBlank()) {
                     list.add(Uri.parse("slingtv://watch.sling.com/watch/channel/$channelId"))
                     list.add(Uri.parse("https://watch.sling.com/watch/channel/$channelId"))
                }
                 // Live/Guide fallback (just open app, handled by automation)
            }
            // Add other apps here...
            else -> {
                // Generic handling if supported
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
