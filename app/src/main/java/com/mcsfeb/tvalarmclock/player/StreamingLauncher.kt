package com.mcsfeb.tvalarmclock.player

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.config.DeepLinkResolver
import com.mcsfeb.tvalarmclock.data.model.ContentType
import com.mcsfeb.tvalarmclock.data.model.ContentSpec
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import kotlinx.coroutines.*

/**
 * StreamingLauncher - Hybrid system for high-reliability content launching on Android TV.
 */
class StreamingLauncher(context: Context) {
    companion object {
        private const val TAG = "StreamingLauncher"
        private const val PREFS_NAME = "streaming_launcher_prefs"
        private const val KEY_BEST_METHOD_PREFIX = "best_method_v7_"

        private val globalLaunchScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    private val appContext = context.applicationContext
    private val sharedPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        DeepLinkConfig.load(appContext)
        DeepLinkResolver.load(appContext)
    }

    fun playContent(
        app: StreamingApp,
        type: ContentType,
        identifiers: Map<String, String>,
        searchQuery: String? = null
    ) {
        val spec = ContentSpec(app, type, identifiers, searchQuery)
        Log.i(TAG, "playContent: Starting robust launch for ${app.displayName}")

        globalLaunchScope.launch {
            try {
                launchContentRobustly(spec)
            } catch (e: Exception) {
                Log.e(TAG, "Robust launch execution failed: ${e.message}")
            }
        }
    }

    private suspend fun launchContentRobustly(spec: ContentSpec): LaunchResult {
        val packageName = findInstalledPackage(spec.app)
            ?: return LaunchResult.AppNotInstalled(spec.app.displayName)

        // For APP_ONLY mode, just open the app — no deep links or automation needed
        if (spec.contentType == ContentType.LIVE && spec.identifiers.isEmpty() && spec.searchQuery.isNullOrBlank()) {
            return launchAppOnly(packageName, spec.app)
        }

        Log.d(TAG, "Phase 1: Attempting Deep Link...")
        val success = tryDeepLinkPhase(spec, packageName)

        if (success) {
            Log.d(TAG, "Intent sent. Waiting for Accessibility Service and app stability...")

            // Wait up to 5s for Accessibility Service to connect if it was flapping
            var waitAttempts = 0
            while (AlarmAccessibilityService.instance == null && waitAttempts < 5) {
                delay(1000)
                waitAttempts++
                Log.d(TAG, "Waiting for Accessibility connection... ($waitAttempts/5)")
            }

            // Lead-in for profile bypass: start clicking as soon as service is ready
            if (ProfileAutoSelector.needsProfileSelect(packageName)) {
                Log.i(TAG, "App uses profiles. Starting immediate bypass sequence.")
                ProfileAutoSelector.scheduleAutoSelect(packageName, initialDelay = 1000L)
            }

            delay(7000) // Verification window

            val foreground = AlarmAccessibilityService.instance?.currentPackage
            Log.d(TAG, "Verification check -> Foreground: $foreground, Expected: $packageName")

            if (foreground == packageName || StreamingApp.getAltPackageNamesForPackage(packageName).contains(foreground)) {
                Log.i(TAG, "Verification successful: App is in foreground.")
                return LaunchResult.Success(spec.app.displayName, "deep_link")
            } else {
                Log.w(TAG, "Verification FAILED. App not in foreground. Starting Phase 2.")
            }
        } else {
            Log.w(TAG, "Phase 1 failed to even launch. Starting Phase 2.")
        }

        return triggerAutomationPhase(spec, packageName)
    }

    /**
     * Simply open the streaming app to its home screen — no deep link, no search.
     */
    private fun launchAppOnly(packageName: String, app: StreamingApp): LaunchResult {
        try {
            val launchIntent = appContext.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: appContext.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launchIntent)
                Log.i(TAG, "Launched ${app.displayName} (app-only mode)")
                return LaunchResult.Success(app.displayName, "app_only")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${app.displayName}: ${e.message}")
        }
        return LaunchResult.LaunchFailed(app.displayName, "Could not open app")
    }

    private fun tryDeepLinkPhase(spec: ContentSpec, packageName: String): Boolean {
        val intents = mutableListOf<Intent>()

        // Build deep link intents if we have a content ID
        val contentId = spec.identifiers["episodeId"] ?: spec.identifiers["id"] ?: spec.identifiers["channel"] ?: ""
        if (contentId.isNotBlank()) {
            for (format in StreamingApp.getDeepLinkFormats(spec.app)) {
                val url = format.replace("{id}", contentId)
                buildDeepLinkIntent(url, packageName, spec.app)?.let { intents.add(it) }
            }
        }

        // Build search intent if we have a search query
        if (!spec.searchQuery.isNullOrBlank()) {
            intents.add(buildSearchIntent(spec.searchQuery, packageName))
        }

        // Try each intent in order until one works
        for (intent in intents) {
            try {
                Log.d(TAG, "Attempting: ${intent.action} ${intent.data}")
                appContext.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        // Last resort: just open the app
        if (intents.isNotEmpty()) {
            // We had intents but none worked — fall back to plain launch
            try {
                val launchIntent = appContext.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                    ?: appContext.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(launchIntent)
                    return true
                }
            } catch (_: Exception) {}
        }

        return false
    }

    private fun triggerAutomationPhase(spec: ContentSpec, packageName: String): LaunchResult {
        if (!ProfileAutoSelector.isServiceEnabled()) {
            Log.e(TAG, "Phase 2 FAILED: Accessibility not enabled.")
            return LaunchResult.LaunchFailed(spec.app.displayName, "Accessibility required.")
        }

        Log.i(TAG, "Phase 2: Smart Navigation...")

        val launchIntent = appContext.packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: appContext.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "Phase 2 FAILED: No launch intent found for $packageName")
            return LaunchResult.LaunchFailed(spec.app.displayName, "Cannot open app")
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)

        ProfileAutoSelector.scheduleAutoSelect(packageName, initialDelay = 3000L)

        val show = spec.identifiers["showName"] ?: spec.searchQuery ?: ""
        if (show.isNotBlank()) {
            when (spec.app) {
                StreamingApp.NETFLIX -> ProfileAutoSelector.runNetflixRecipe(show, spec.identifiers["season"], spec.identifiers["episode"])
                StreamingApp.SLING_TV -> ProfileAutoSelector.runSlingRecipe(spec.identifiers["channelName"] ?: show)
                else -> ProfileAutoSelector.scheduleSearchAndPlay(show, packageName)
            }
        }

        sharedPrefs.edit { putString(KEY_BEST_METHOD_PREFIX + spec.toPersistentKey(), "automation") }
        return LaunchResult.Success(spec.app.displayName, "automation")
    }

    private fun buildDeepLinkIntent(url: String, packageName: String, app: StreamingApp): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // Apply extras from config (e.g., Netflix source=30)
        StreamingApp.getIntentExtras(app).forEach { (k, v) -> intent.putExtra(k, v) }
        // Fallback: if config had no extras but the enum says Netflix needs source extra
        if (app.requiresSourceExtra && !intent.hasExtra("source")) {
            intent.putExtra("source", "30")
        }
        return intent
    }

    private fun buildSearchIntent(query: String, packageName: String): Intent {
        return Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Launch streaming content based on its LaunchMode.
     * This is the main entry point called from AlarmActivity and ContentPickerScreen.
     */
    fun launch(content: StreamingContent): LaunchResult {
        val identifiers = mutableMapOf<String, String>()
        if (content.contentId.isNotBlank()) identifiers["id"] = content.contentId
        if (content.searchQuery.isNotBlank()) identifiers["showName"] = content.searchQuery

        // Map LaunchMode to ContentType properly
        val contentType = when (content.launchMode) {
            LaunchMode.APP_ONLY -> ContentType.LIVE  // Will be caught by launchAppOnly check
            LaunchMode.SEARCH -> ContentType.EPISODE
            LaunchMode.DEEP_LINK -> ContentType.EPISODE
        }

        // For APP_ONLY, don't pass a search query — just open the app
        val searchQuery = when (content.launchMode) {
            LaunchMode.APP_ONLY -> null
            LaunchMode.SEARCH -> content.searchQuery.ifBlank { content.title }
            LaunchMode.DEEP_LINK -> if (content.searchQuery.isNotBlank()) content.searchQuery else null
        }

        playContent(content.app, contentType, identifiers, searchQuery)
        return LaunchResult.Success(content.app.displayName, "queued")
    }

    fun findInstalledPackage(app: StreamingApp): String? {
        val pkgs = listOf(StreamingApp.getPackageName(app)) + StreamingApp.getAltPackageNames(app)
        for (pkg in pkgs) {
            try {
                appContext.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun getInstalledApps() = StreamingApp.entries.filter { findInstalledPackage(it) != null }
}

sealed class LaunchResult {
    data class Success(val appName: String, val method: String) : LaunchResult()
    data class AppNotInstalled(val appName: String) : LaunchResult()
    data class LaunchFailed(val appName: String, val error: String) : LaunchResult()

    fun displayMessage(): String = when (this) {
        is Success -> "✓ Launched $appName"
        is AppNotInstalled -> "✗ $appName not installed"
        is LaunchFailed -> "✗ Error: $error"
    }
}
