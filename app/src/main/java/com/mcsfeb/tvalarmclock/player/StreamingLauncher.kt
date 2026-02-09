package com.mcsfeb.tvalarmclock.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingLauncher - The engine that deep-links into streaming apps.
 *
 * This class handles:
 * 1. Checking if a streaming app is installed on the TV
 * 2. Building the correct deep link intent for each app
 * 3. Launching the app with specific content, a search query, or just opening it
 * 4. Handling errors gracefully if the app isn't installed or the link fails
 * 5. Auto-clicking past profile selection screens (via ProfileAutoSelector)
 *
 * THREE WAYS TO LAUNCH:
 * - launch(app, contentId) → Deep link to specific content (if you have the ID)
 * - launchWithSearch(app, showName) → Open the app and search for a show by name
 * - launchAppOnly(app) → Just open the app to its home screen
 *
 * After launching, ProfileAutoSelector automatically sends simulated D-pad
 * key presses to click past "Who's Watching?" profile screens. This way
 * the alarm can fully auto-play content even when the user is asleep.
 *
 * The "search" approach is the most reliable for getting to specific content
 * without needing proprietary content IDs. Most Android TV apps support
 * either the global SEARCH intent or their own search deep links.
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

    /**
     * Find which package name is actually installed for a streaming app.
     * Checks the primary package first, then all alternates.
     * Returns null if none are installed.
     */
    fun findInstalledPackage(app: StreamingApp): String? {
        if (isPackageInstalled(app.packageName)) return app.packageName
        for (altPackage in app.altPackageNames) {
            if (isPackageInstalled(altPackage)) return altPackage
        }
        return null
    }

    /**
     * Launch a streaming app to specific content using a deep link ID.
     * Use this when you have the app's internal content ID.
     */
    fun launch(app: StreamingApp, contentId: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        val deepLinkUrl = StreamingApp.buildDeepLink(app, contentId)
        val intent = buildIntent(app, deepLinkUrl, installedPackage)

        return try {
            context.startActivity(intent)
            // Auto-click past profile selection screen if this app has one
            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            LaunchResult.Success(app.displayName, deepLinkUrl)
        } catch (e: ActivityNotFoundException) {
            LaunchResult.LaunchFailed(app.displayName, "Activity not found: ${e.message}")
        } catch (e: Exception) {
            LaunchResult.LaunchFailed(app.displayName, "Unexpected error: ${e.message}")
        }
    }

    /**
     * Launch a streaming app and search for a show/movie by name.
     *
     * This is the BEST way to get to specific content because:
     * - No proprietary content IDs needed
     * - Works for any show available on the platform
     * - Opens the app directly to the show's page (usually)
     *
     * Strategy per app:
     * 1. Try app-specific search deep link (most reliable)
     * 2. Fall back to Android's global SEARCH intent targeted at the app
     * 3. Final fallback: just open the app
     */
    fun launchWithSearch(app: StreamingApp, showName: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        // First try: App-specific search deep link
        val searchUrl = getSearchDeepLink(app, showName)
        if (searchUrl != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(searchUrl)
                    setPackage(installedPackage)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                // Netflix needs special handling
                if (app == StreamingApp.NETFLIX) {
                    intent.setClassName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
                    intent.putExtra("source", "30")
                }
                context.startActivity(intent)
                // Auto-click past profile selection screen
                if (autoProfileSelect) {
                    ProfileAutoSelector.scheduleAutoSelect(installedPackage)
                }
                return LaunchResult.Success(app.displayName, "search: $showName")
            } catch (e: Exception) {
                // Fall through to next strategy
            }
        }

        // Second try: Android's built-in SEARCH intent targeted at the app
        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(installedPackage)
                putExtra(SearchManager.QUERY, showName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(searchIntent)
            // Auto-click past profile selection screen
            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "search: $showName")
        } catch (e: Exception) {
            // Fall through to next strategy
        }

        // Third try: Global content search (Android TV's built-in search system)
        try {
            val globalSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, showName)
                putExtra("android.intent.extra.FOCUS", "vnd.android.cursor.item/video")
                setPackage(installedPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(globalSearch)
            // Auto-click past profile selection screen
            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "media search: $showName")
        } catch (e: Exception) {
            // Fall through to fallback
        }

        // Final fallback: just open the app
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
                // Auto-click past profile selection screen
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

    /**
     * Get app-specific search deep link URL.
     *
     * Many streaming apps support search via URL deep links.
     * This is MORE RELIABLE than the generic Android SEARCH intent
     * because each app handles their own URL scheme.
     */
    private fun getSearchDeepLink(app: StreamingApp, query: String): String? {
        val encoded = Uri.encode(query)
        return when (app) {
            StreamingApp.NETFLIX ->
                "https://www.netflix.com/search?q=$encoded"
            StreamingApp.HBO_MAX ->
                "https://play.max.com/search?q=$encoded"
            StreamingApp.HULU ->
                "https://www.hulu.com/search?q=$encoded"
            StreamingApp.DISNEY_PLUS ->
                "https://www.disneyplus.com/search?q=$encoded"
            StreamingApp.PRIME_VIDEO ->
                "https://app.primevideo.com/search?phrase=$encoded"
            StreamingApp.YOUTUBE ->
                "https://www.youtube.com/results?search_query=$encoded"
            StreamingApp.PARAMOUNT_PLUS ->
                "https://www.paramountplus.com/search/?q=$encoded"
            StreamingApp.PEACOCK ->
                "https://www.peacocktv.com/search?query=$encoded"
            StreamingApp.CRUNCHYROLL ->
                "https://www.crunchyroll.com/search?q=$encoded"
            StreamingApp.TUBI ->
                "https://tubitv.com/search/$encoded"
            StreamingApp.APPLE_TV ->
                "https://tv.apple.com/search?term=$encoded"
            StreamingApp.DISCOVERY_PLUS ->
                "https://www.discoveryplus.com/search?q=$encoded"
            // Live TV apps don't really have search deep links
            StreamingApp.SLING_TV -> null
            StreamingApp.YOUTUBE_TV -> null
            StreamingApp.FUBO_TV -> null
            StreamingApp.PLUTO_TV -> null
            StreamingApp.PLEX -> null
            StreamingApp.STARZ -> null
        }
    }

    /**
     * Verify if a deep link would resolve to an activity without actually launching it.
     * Returns true if the intent can be handled, false otherwise.
     *
     * Use this to check if a deep link will work BEFORE the alarm fires,
     * so the user gets a warning if the link is bad.
     */
    fun verifyDeepLink(app: StreamingApp, contentId: String): Boolean {
        val installedPackage = findInstalledPackage(app) ?: return false
        if (contentId.isBlank()) return false

        val deepLinkUrl = StreamingApp.buildDeepLink(app, contentId)
        val intent = buildIntent(app, deepLinkUrl, installedPackage)

        return try {
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
    }

    /**
     * Build the intent with app-specific quirks and extras.
     */
    private fun buildIntent(app: StreamingApp, deepLinkUrl: String, installedPackage: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLinkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        when (app) {
            StreamingApp.NETFLIX -> {
                intent.setClassName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
                intent.putExtra("source", "30")
            }
            StreamingApp.YOUTUBE -> {
                intent.setPackage(installedPackage)
            }
            StreamingApp.HULU -> {
                try {
                    intent.setClassName(
                        "com.hulu.livingroomplus",
                        "com.hulu.livingroomplus.WKFactivity"
                    )
                } catch (e: Exception) {
                    intent.setPackage(installedPackage)
                }
            }
            StreamingApp.HBO_MAX -> {
                intent.setPackage(installedPackage)
            }
            StreamingApp.YOUTUBE_TV -> {
                intent.setPackage(installedPackage)
            }
            else -> {
                intent.setPackage(installedPackage)
            }
        }

        return intent
    }
}

/**
 * LaunchResult - What happened when we tried to open a streaming app.
 */
sealed class LaunchResult {
    data class Success(val appName: String, val deepLink: String) : LaunchResult()
    data class AppNotInstalled(val appName: String) : LaunchResult()
    data class LaunchFailed(val appName: String, val error: String) : LaunchResult()
}
