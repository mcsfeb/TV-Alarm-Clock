package com.mcsfeb.tvalarmclock.player

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.config.DeepLinkResolver
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingLauncher - The engine that deep-links into streaming apps.
 *
 * This class handles:
 * 1. Checking if a streaming app is installed on the TV
 * 2. Building the correct deep link intent for each app (from config file)
 * 3. Trying MULTIPLE URI schemes with automatic fallback if one fails
 * 4. Launching the app with specific content, a search query, or just opening it
 * 5. Handling errors gracefully if the app isn't installed or the link fails
 * 6. Auto-clicking past profile selection screens (via ProfileAutoSelector)
 *
 * LAUNCH STRATEGY (in order of preference):
 * 1. Deep link with primary URI scheme from config → specific content
 * 2. Deep link with alternate URI schemes → same content, different format
 * 3. Search within the app → opens the app and searches for the show
 * 4. App-only launch → just opens the app to its home screen
 *
 * URI SCHEMES ARE CONFIGURABLE:
 * All deep link formats, package names, and app-specific quirks are loaded
 * from assets/deep_link_config.json. If a streaming app pushes an update
 * that breaks its deep link, you just edit the JSON file and rebuild.
 * Hardcoded defaults in StreamingApp.kt are used if the config fails.
 */
class StreamingLauncher(
    private val context: Context,
    /**
     * When true, automatically sends D-pad key presses after launch to click
     * past profile selection screens ("Who's Watching?").
     *
     * Set to TRUE for alarm-triggered launches (user is asleep, needs full automation).
     * Set to FALSE for test launches from ContentPicker (user is actively using the app).
     */
    private val autoProfileSelect: Boolean = false
) {
    companion object {
        private const val TAG = "StreamingLauncher"
    }

    init {
        // Load the deep link config on first use
        DeepLinkConfig.load(context)
        // Load cached resolver results so verified formats are available immediately
        DeepLinkResolver.load(context)
    }

    /**
     * Find which package name is actually installed for a streaming app.
     * Checks the primary package first (from config), then all alternates.
     * Returns null if none are installed.
     */
    fun findInstalledPackage(app: StreamingApp): String? {
        val primaryPkg = StreamingApp.getPackageName(app)
        if (isPackageInstalled(primaryPkg)) return primaryPkg

        for (altPkg in StreamingApp.getAltPackageNames(app)) {
            if (isPackageInstalled(altPkg)) return altPkg
        }
        return null
    }

    /**
     * Launch a streaming app to specific content using a deep link ID.
     *
     * FALLBACK CHAIN:
     * 1. Try each URI format from config (primary first, then alternates)
     * 2. If ALL deep link formats fail → fall back to app-only launch
     *
     * This means if Netflix changes their URI scheme, we try the others
     * automatically before giving up.
     */
    fun launch(app: StreamingApp, contentId: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        // Force-stop the app first if needed (Hulu quirk)
        if (StreamingApp.needsForceStop(app)) {
            forceStopApp(installedPackage)
        }

        // Get all deep link formats to try (from config, then hardcoded fallback)
        val formats = StreamingApp.getDeepLinkFormats(app)
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

        // Try each URI format until one works
        // Note: formats are already in 3-tier order (verified → config → hardcoded)
        for (format in formats) {
            val deepLinkUrl = format.replace("{id}", contentId)
            val intent = buildIntentFromConfig(deepLinkUrl, installedPackage, extras, className)

            try {
                context.startActivity(intent)
                Log.d(TAG, "Launched ${app.displayName} with: $deepLinkUrl")

                if (autoProfileSelect) {
                    ProfileAutoSelector.scheduleAutoSelect(installedPackage)
                }
                return LaunchResult.Success(app.displayName, deepLinkUrl)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Format failed for ${app.displayName}: $deepLinkUrl → ${e.message}")
                // Report failure to resolver so this format is deprioritized
                DeepLinkResolver.reportFailure(context, app, format)
            } catch (e: Exception) {
                Log.w(TAG, "Format error for ${app.displayName}: $deepLinkUrl → ${e.message}")
                DeepLinkResolver.reportFailure(context, app, format)
            }
        }

        // All deep link formats failed → re-probe, then try search, then app-only
        Log.w(TAG, "All deep link formats failed for ${app.displayName}, re-probing...")
        DeepLinkResolver.probeApp(context, app)

        // Try searching for the content ID as a last resort before app-only
        // Some apps might find the content by ID-based search
        Log.d(TAG, "Attempting search fallback for ${app.displayName} with: $contentId")
        val searchResult = launchWithSearch(app, contentId)
        if (searchResult is LaunchResult.Success) {
            return LaunchResult.Success(app.displayName, "search fallback: $contentId")
        }

        return launchAppOnly(app)
    }

    /**
     * Launch a streaming app and search for a show/movie by name.
     *
     * FALLBACK CHAIN:
     * 1. App-specific search URL from config (most reliable)
     * 2. Android's built-in SEARCH intent targeted at the app
     * 3. Global media search (Android TV's search system)
     * 4. Just open the app
     */
    fun launchWithSearch(app: StreamingApp, showName: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        // Force-stop if needed
        if (StreamingApp.needsForceStop(app)) {
            forceStopApp(installedPackage)
        }

        val encoded = Uri.encode(showName)
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

        // Strategy 1: App-specific search URL from config
        val searchUrl = StreamingApp.getSearchUrl(app)
        if (searchUrl != null) {
            val resolvedUrl = searchUrl.replace("{query}", encoded)
            try {
                val intent = buildIntentFromConfig(resolvedUrl, installedPackage, extras, className)
                context.startActivity(intent)
                Log.d(TAG, "Search launched ${app.displayName}: $resolvedUrl")

                if (autoProfileSelect) {
                    ProfileAutoSelector.scheduleAutoSelect(installedPackage)
                }
                return LaunchResult.Success(app.displayName, "search: $showName")
            } catch (e: Exception) {
                Log.w(TAG, "Search URL failed for ${app.displayName}: ${e.message}")
            }
        }

        // Strategy 2: Android's built-in SEARCH intent
        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(installedPackage)
                putExtra(SearchManager.QUERY, showName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(searchIntent)
            Log.d(TAG, "ACTION_SEARCH launched for ${app.displayName}")

            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "search: $showName")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SEARCH failed for ${app.displayName}: ${e.message}")
        }

        // Strategy 3: Global media search (Android TV)
        try {
            val globalSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, showName)
                putExtra("android.intent.extra.FOCUS", "vnd.android.cursor.item/video")
                setPackage(installedPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(globalSearch)
            Log.d(TAG, "Media search launched for ${app.displayName}")

            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "media search: $showName")
        } catch (e: Exception) {
            Log.w(TAG, "Media search failed for ${app.displayName}: ${e.message}")
        }

        // Strategy 4: Just open the app
        Log.w(TAG, "All search strategies failed for ${app.displayName}, opening app only")
        return launchAppOnly(app)
    }

    /**
     * Launch a streaming app without specific content (just open the app).
     */
    fun launchAppOnly(app: StreamingApp): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(installedPackage)
            ?: context.packageManager.getLaunchIntentForPackage(installedPackage)

        return if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)

                if (autoProfileSelect) {
                    ProfileAutoSelector.scheduleAutoSelect(installedPackage)
                }
                LaunchResult.Success(app.displayName, "app launch (no specific content)")
            } catch (e: Exception) {
                LaunchResult.LaunchFailed(app.displayName, e.message ?: "Unknown error")
            }
        } else {
            LaunchResult.LaunchFailed(app.displayName, "Could not create launch intent")
        }
    }

    /**
     * Check which streaming apps are installed on this TV.
     */
    fun getInstalledApps(): List<StreamingApp> {
        return StreamingApp.entries.filter { findInstalledPackage(it) != null }
    }

    /**
     * Get a status summary of the deep link resolver for UI display.
     * Returns a pair of (verified app count, total verified formats).
     */
    fun getResolverStatus(): Pair<Int, Int> {
        return Pair(DeepLinkResolver.getVerifiedAppCount(), DeepLinkResolver.getTotalVerifiedFormats())
    }

    /**
     * Verify if a deep link would resolve to an activity without actually launching it.
     * Tries all URI formats from config and returns true if ANY would work.
     */
    fun verifyDeepLink(app: StreamingApp, contentId: String): Boolean {
        val installedPackage = findInstalledPackage(app) ?: return false
        if (contentId.isBlank()) return false

        val formats = StreamingApp.getDeepLinkFormats(app)
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

        for (format in formats) {
            val deepLinkUrl = format.replace("{id}", contentId)
            val intent = buildIntentFromConfig(deepLinkUrl, installedPackage, extras, className)

            val resolves = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.resolveActivity(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                    ) != null
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
                }
            } catch (e: Exception) {
                false
            }

            if (resolves) return true
        }
        return false
    }

    /**
     * Check if a specific package is installed.
     */
    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    // ========== Private helpers ==========

    /**
     * Build an intent using config-driven parameters.
     *
     * This replaces the old app-specific switch/when block.
     * All app-specific quirks (extras, class names) come from the JSON config.
     */
    private fun buildIntentFromConfig(
        deepLinkUrl: String,
        installedPackage: String,
        extras: Map<String, String>,
        className: String?
    ): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLinkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Set the target: either a specific Activity class, or just the package
        if (className != null) {
            intent.setClassName(installedPackage, className)
        } else {
            intent.setPackage(installedPackage)
        }

        // Add any required extras (e.g., Netflix source=30)
        for ((key, value) in extras) {
            intent.putExtra(key, value)
        }

        return intent
    }

    /**
     * Force-stop an app before re-launching (Hulu quirk).
     * Some apps get confused if you deep-link while they're already running
     * with different content. Force-stopping ensures a clean launch.
     *
     * NOTE: This requires the app to have been launched at least once.
     * On some devices, this may not work without root or device-admin privileges.
     */
    private fun forceStopApp(packageName: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.killBackgroundProcesses(packageName)
            Log.d(TAG, "Force-stopped $packageName before re-launch")
        } catch (e: Exception) {
            Log.w(TAG, "Could not force-stop $packageName: ${e.message}")
        }
    }
}

/**
 * LaunchResult - What happened when we tried to open a streaming app.
 *
 * Each result has a user-friendly message for display on the alarm screen.
 */
sealed class LaunchResult {
    /** App launched successfully (deep link, search, or app-only) */
    data class Success(val appName: String, val deepLink: String) : LaunchResult()

    /** The streaming app is not installed on this TV */
    data class AppNotInstalled(val appName: String) : LaunchResult()

    /** Launch was attempted but failed */
    data class LaunchFailed(val appName: String, val error: String) : LaunchResult()

    /** User-friendly message for display */
    fun displayMessage(): String = when (this) {
        is Success -> "\u2713 Launched $appName"
        is AppNotInstalled -> "\u2717 $appName is not installed on this TV"
        is LaunchFailed -> "\u2717 Could not open $appName: $error"
    }
}
