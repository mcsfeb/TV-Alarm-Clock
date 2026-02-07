package com.mcsfeb.tvalarmclock.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingLauncher - The engine that deep-links into streaming apps.
 *
 * This class handles:
 * 1. Checking if a streaming app is installed on the TV (including alternate package names)
 * 2. Building the correct deep link intent for each app
 * 3. Launching the app with the right content ID
 * 4. Handling errors gracefully if the app isn't installed or the link fails
 *
 * Many streaming apps have DIFFERENT package names depending on the device:
 * - YouTube mobile: com.google.android.youtube
 * - YouTube on Android TV: com.google.android.youtube.tv
 * - HBO was com.hbo.hbonow, then com.hbo.max.android.tv, now com.wbd.stream
 *
 * We check the primary package AND all alternates to find what's actually installed.
 */
class StreamingLauncher(private val context: Context) {

    /**
     * Find which package name is actually installed for a streaming app.
     * Checks the primary package first, then all alternates.
     * Returns null if none are installed.
     */
    fun findInstalledPackage(app: StreamingApp): String? {
        // Check primary package first
        if (isPackageInstalled(app.packageName)) return app.packageName

        // Check alternate package names
        for (altPackage in app.altPackageNames) {
            if (isPackageInstalled(altPackage)) return altPackage
        }

        return null
    }

    /**
     * Launch a streaming app to specific content.
     */
    fun launch(app: StreamingApp, contentId: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        val deepLinkUrl = StreamingApp.buildDeepLink(app, contentId)
        val intent = buildIntent(app, deepLinkUrl, installedPackage)

        return try {
            context.startActivity(intent)
            LaunchResult.Success(app.displayName, deepLinkUrl)
        } catch (e: ActivityNotFoundException) {
            LaunchResult.LaunchFailed(app.displayName, "Activity not found: ${e.message}")
        } catch (e: Exception) {
            LaunchResult.LaunchFailed(app.displayName, "Unexpected error: ${e.message}")
        }
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
     * Checks both primary and alternate package names for each app.
     */
    fun getInstalledApps(): List<StreamingApp> {
        return StreamingApp.entries.filter { findInstalledPackage(it) != null }
    }

    /**
     * Check if a specific package is installed.
     * Uses the new API on Android 13+ and falls back to the old API on older versions.
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
            // Catch any other unexpected exception
            false
        }
    }

    /**
     * Build the intent with app-specific quirks and extras.
     * Uses the actual installed package name (which may be an alternate).
     */
    private fun buildIntent(app: StreamingApp, deepLinkUrl: String, installedPackage: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLinkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // App-specific customizations
        when (app) {
            StreamingApp.NETFLIX -> {
                intent.setClassName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
                intent.putExtra("source", "30")
            }

            StreamingApp.YOUTUBE -> {
                intent.setPackage(installedPackage)
            }

            StreamingApp.HULU -> {
                // Try the specific activity, fall back to just package
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
                // HBO has changed package names multiple times, use whatever is installed
                intent.setPackage(installedPackage)
            }

            StreamingApp.YOUTUBE_TV -> {
                intent.setPackage(installedPackage)
            }

            // All other apps: just set the installed package name
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
    /** The app launched successfully */
    data class Success(val appName: String, val deepLink: String) : LaunchResult()

    /** The streaming app isn't installed on this TV */
    data class AppNotInstalled(val appName: String) : LaunchResult()

    /** The app is installed but the launch failed */
    data class LaunchFailed(val appName: String, val error: String) : LaunchResult()
}
