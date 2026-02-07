package com.mcsfeb.tvalarmclock.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * StreamingLauncher - The engine that deep-links into streaming apps.
 *
 * This class handles:
 * 1. Checking if a streaming app is installed on the TV
 * 2. Building the correct deep link intent for each app
 * 3. Launching the app with the right content ID
 * 4. Handling errors gracefully if the app isn't installed or the link fails
 *
 * Each streaming app has quirks:
 * - Netflix needs a special "source=30" extra or it ignores the deep link
 * - YouTube uses a custom "vnd.youtube:" scheme instead of https
 * - Hulu sometimes needs to be force-stopped before re-launching
 * - None of these are official APIs, so they can break with app updates!
 */
class StreamingLauncher(private val context: Context) {

    /**
     * Launch a streaming app to specific content.
     *
     * @param app Which streaming service to open
     * @param contentId The ID of the content to play (video ID, title ID, etc.)
     * @return LaunchResult indicating success or what went wrong
     */
    fun launch(app: StreamingApp, contentId: String): LaunchResult {
        // Step 1: Check if the app is installed
        if (!isAppInstalled(app.packageName)) {
            return LaunchResult.AppNotInstalled(app.displayName)
        }

        // Step 2: Build the deep link URL
        val deepLinkUrl = StreamingApp.buildDeepLink(app, contentId)

        // Step 3: Build the intent with app-specific quirks
        val intent = buildIntent(app, deepLinkUrl)

        // Step 4: Try to launch!
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
     *
     * @param app Which streaming service to open
     * @return LaunchResult indicating success or what went wrong
     */
    fun launchAppOnly(app: StreamingApp): LaunchResult {
        if (!isAppInstalled(app.packageName)) {
            return LaunchResult.AppNotInstalled(app.displayName)
        }

        val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(app.packageName)

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
     *
     * @return List of installed streaming apps
     */
    fun getInstalledApps(): List<StreamingApp> {
        return StreamingApp.entries.filter { isAppInstalled(it.packageName) }
    }

    /**
     * Check if a specific app is installed.
     */
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Build the intent with app-specific quirks and extras.
     */
    private fun buildIntent(app: StreamingApp, deepLinkUrl: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(deepLinkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // App-specific customizations
        when (app) {
            StreamingApp.NETFLIX -> {
                // Netflix on Android TV uses a different package than mobile
                // and REQUIRES the "source=30" extra or the deep link is ignored
                intent.setClassName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
                intent.putExtra("source", "30")
            }

            StreamingApp.YOUTUBE -> {
                // YouTube uses the vnd.youtube: scheme, set package to ensure
                // it opens in the YouTube app and not a browser
                intent.setPackage("com.google.android.youtube")
            }

            StreamingApp.HULU -> {
                // Hulu on Android TV has a specific activity
                intent.setClassName(
                    "com.hulu.livingroomplus",
                    "com.hulu.livingroomplus.WKFactivity"
                )
            }

            StreamingApp.DISNEY_PLUS -> {
                intent.setPackage("com.disney.disneyplus")
            }

            StreamingApp.PRIME_VIDEO -> {
                intent.setPackage("com.amazon.amazonvideo.livingroom")
            }

            StreamingApp.HBO_MAX -> {
                intent.setPackage("com.hbo.hbonow")
            }

            StreamingApp.SLING_TV -> {
                intent.setPackage("com.sling")
            }

            StreamingApp.PEACOCK -> {
                intent.setPackage("com.peacocktv.peacockandroid")
            }

            StreamingApp.PARAMOUNT_PLUS -> {
                intent.setPackage("com.cbs.ott")
            }

            StreamingApp.APPLE_TV -> {
                intent.setPackage("com.apple.atve.androidtv.appletv")
            }

            StreamingApp.TUBI -> {
                intent.setPackage("com.tubitv")
            }

            StreamingApp.PLUTO_TV -> {
                intent.setPackage("tv.pluto.android")
            }

            StreamingApp.CRUNCHYROLL -> {
                intent.setPackage("com.crunchyroll.crunchyroid")
            }

            StreamingApp.YOUTUBE_TV -> {
                intent.setPackage("com.google.android.apps.tv.launcherx")
            }

            StreamingApp.FUBO_TV -> {
                intent.setPackage("com.fubo.firetv")
            }

            StreamingApp.DISCOVERY_PLUS -> {
                intent.setPackage("com.discovery.discoveryplus.androidtv")
            }

            StreamingApp.PLEX -> {
                intent.setPackage("com.plexapp.android")
            }

            StreamingApp.STARZ -> {
                intent.setPackage("com.bydeluxe.d3.android.program.starz")
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
