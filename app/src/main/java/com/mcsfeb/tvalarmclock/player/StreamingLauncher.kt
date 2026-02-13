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
import com.mcsfeb.tvalarmclock.data.model.LaunchMode
import com.mcsfeb.tvalarmclock.data.model.StreamingApp
import com.mcsfeb.tvalarmclock.data.model.StreamingContent

class StreamingLauncher(
    private val context: Context,
    private val autoProfileSelect: Boolean = false
) {
    companion object {
        private const val TAG = "StreamingLauncher"
    }

    init {
        DeepLinkConfig.load(context)
        DeepLinkResolver.load(context)
    }

    /**
     * The primary entry point for launching content.
     * Decides the best strategy (Deep Link, Search, or App Only) based on the content's launchMode.
     */
    fun launch(content: StreamingContent): LaunchResult {
        Log.d(TAG, "Launching: ${content.app.displayName} - ${content.title} (Mode: ${content.launchMode})")
        
        return when (content.launchMode) {
            LaunchMode.DEEP_LINK -> {
                val result = launchWithDeepLinkOnly(content.app, content.contentId)
                if (result is LaunchResult.Success) result
                else {
                    Log.w(TAG, "Deep link failed, falling back to search/app only")
                    if (content.searchQuery.isNotBlank()) {
                        launchWithSearch(content.app, content.searchQuery)
                    } else {
                        launchAppOnly(content.app)
                    }
                }
            }
            LaunchMode.SEARCH -> {
                if (content.searchQuery.isNotBlank()) {
                    launchWithSearch(content.app, content.searchQuery)
                } else {
                    launchAppOnly(content.app)
                }
            }
            LaunchMode.APP_ONLY -> launchAppOnly(content.app)
        }
    }

    fun findInstalledPackage(app: StreamingApp): String? {
        val primaryPkg = StreamingApp.getPackageName(app)
        if (isPackageInstalled(primaryPkg)) return primaryPkg

        for (altPkg in StreamingApp.getAltPackageNames(app)) {
            if (isPackageInstalled(altPkg)) return altPkg
        }
        return null
    }

    fun launchWithDeepLinkOnly(app: StreamingApp, contentId: String): LaunchResult {
        if (contentId.isBlank()) return LaunchResult.LaunchFailed(app.displayName, "Content ID is blank.")

        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        if (StreamingApp.needsForceStop(app)) {
            forceStopApp(installedPackage)
        }

        val formats = StreamingApp.getDeepLinkFormats(app)
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

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
                Log.w(TAG, "Format failed: $deepLinkUrl → ${e.message}")
                DeepLinkResolver.reportFailure(context, app, format)
            } catch (e: Exception) {
                Log.w(TAG, "Format error: $deepLinkUrl → ${e.message}")
                DeepLinkResolver.reportFailure(context, app, format)
            }
        }

        return LaunchResult.LaunchFailed(app.displayName, "All deep link formats failed.")
    }

    fun launchWithSearch(app: StreamingApp, showName: String): LaunchResult {
        val installedPackage = findInstalledPackage(app)
            ?: return LaunchResult.AppNotInstalled(app.displayName)

        if (StreamingApp.needsForceStop(app)) {
            forceStopApp(installedPackage)
        }

        val encoded = Uri.encode(showName)
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

        val searchUrl = StreamingApp.getSearchUrl(app)
        if (searchUrl != null) {
            val resolvedUrl = searchUrl.replace("{query}", encoded)
            try {
                val intent = buildIntentFromConfig(resolvedUrl, installedPackage, extras, className)
                context.startActivity(intent)
                Log.d(TAG, "Search (URL) launched ${app.displayName}: $resolvedUrl")

                if (autoProfileSelect) {
                    ProfileAutoSelector.scheduleAutoSelect(installedPackage)
                }
                return LaunchResult.Success(app.displayName, "search: $showName")
            } catch (e: Exception) {
                Log.w(TAG, "Search (URL) failed for ${app.displayName}: ${e.message}")
            }
        }

        try {
            val globalSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, showName)
                putExtra("android.intent.extra.FOCUS", "vnd.android.cursor.item/video")
                setPackage(installedPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(globalSearch)
            Log.d(TAG, "Search (Media) launched for ${app.displayName}")

            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "media search: $showName")
        } catch (e: Exception) {
            Log.w(TAG, "Search (Media) failed for ${app.displayName}: ${e.message}")
        }

        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(installedPackage)
                putExtra(SearchManager.QUERY, showName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(searchIntent)
            Log.d(TAG, "Search (Action) launched for ${app.displayName}")

            if (autoProfileSelect) {
                ProfileAutoSelector.scheduleAutoSelect(installedPackage)
            }
            return LaunchResult.Success(app.displayName, "search: $showName")
        } catch (e: Exception) {
            Log.w(TAG, "Search (Action) failed for ${app.displayName}: ${e.message}")
        }

        Log.w(TAG, "All search strategies failed for ${app.displayName}, opening app only")
        return launchAppOnly(app)
    }

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

    fun getInstalledApps(): List<StreamingApp> {
        return StreamingApp.entries.filter { findInstalledPackage(it) != null }
    }

    fun getResolverStatus(): Pair<Int, Int> {
        return Pair(DeepLinkResolver.getVerifiedAppCount(), DeepLinkResolver.getTotalVerifiedFormats())
    }

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

        if (className != null) {
            intent.setClassName(installedPackage, className)
        } else {
            intent.setPackage(installedPackage)
        }

        for ((key, value) in extras) {
            intent.putExtra(key, value)
        }

        return intent
    }

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

sealed class LaunchResult {
    data class Success(val appName: String, val deepLink: String) : LaunchResult()
    data class AppNotInstalled(val appName: String) : LaunchResult()
    data class LaunchFailed(val appName: String, val error: String) : LaunchResult()

    fun displayMessage(): String = when (this) {
        is Success -> "✓ Launched $appName"
        is AppNotInstalled -> "✗ $appName is not installed on this TV"
        is LaunchFailed -> "✗ Could not open $appName: $error"
    }
}
