package com.mcsfeb.tvalarmclock.data.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log

import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * DeepLinkResolver - Runtime deep link discovery engine.
 *
 * THE PROBLEM:
 * Streaming apps change their deep link URIs with updates. A format that worked
 * yesterday might stop working tomorrow when Netflix pushes an update.
 *
 * THE SOLUTION:
 * Instead of guessing, we TEST which formats actually work on THIS specific TV
 * by calling resolveActivity() — which checks if an intent would succeed WITHOUT
 * actually launching anything. We cache the results and re-test when apps update.
 *
 * HOW IT WORKS:
 * 1. At app startup, probeAll() tests every URI format for every installed streaming app
 * 2. Working formats are cached in SharedPreferences with the app's version number
 * 3. When launching, verified formats are tried FIRST (most reliable)
 * 4. If a launch fails at runtime, reportFailure() clears that format and re-probes
 * 5. If a streaming app updates, detectAppUpdates() triggers re-probing automatically
 *
 * THREE-TIER FALLBACK:
 *   Tier 1: Verified formats from resolver (proven to work on this device)
 *   Tier 2: Config formats from JSON file (community-curated best guesses)
 *   Tier 3: Hardcoded formats in StreamingApp.kt (last resort)
 */
object DeepLinkResolver {

    private const val TAG = "DeepLinkResolver"
    private const val PREFS_NAME = "resolver_cache"
    private const val FORMAT_SEPARATOR = ";;"
    private const val REPROBE_INTERVAL_MS = 24 * 60 * 60 * 1000L  // 24 hours

    /** In-memory cache of verified formats per app */
    private var verifiedCache: MutableMap<String, List<String>> = mutableMapOf()
    private var loaded = false

    // ==========================================
    // PUBLIC API
    // ==========================================

    /**
     * Load cached results from SharedPreferences.
     * Call this early (before any launches) so cached data is available.
     */
    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        for (app in StreamingApp.entries) {
            val cached = prefs.getString("formats_${app.name}", null)
            if (cached != null && cached.isNotBlank()) {
                verifiedCache[app.name] = cached.split(FORMAT_SEPARATOR).filter { it.isNotBlank() }
            }
        }
        loaded = true
        Log.d(TAG, "Loaded resolver cache: ${verifiedCache.size} apps with verified formats")
    }

    /**
     * Probe ALL installed streaming apps to discover working URI formats.
     *
     * This tests each URI format with resolveActivity() — no apps are actually launched.
     * Takes ~100ms total, safe to call on main thread but better on background.
     *
     * Also detects app version changes and re-probes updated apps.
     */
    fun probeAll(context: Context) {
        load(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFullProbe = prefs.getLong("last_full_probe", 0)
        val now = System.currentTimeMillis()

        // Check for app updates first — any updated app needs re-probing
        val updatedApps = detectAppUpdates(context)
        for (app in updatedApps) {
            Log.d(TAG, "${app.displayName} was updated, re-probing...")
            probeApp(context, app)
        }

        // Full re-probe every 24 hours (catches edge cases)
        if (now - lastFullProbe > REPROBE_INTERVAL_MS) {
            Log.d(TAG, "Running full probe (last was ${(now - lastFullProbe) / 3600000}h ago)")

            var totalVerified = 0
            for (app in StreamingApp.entries) {
                val results = probeApp(context, app)
                totalVerified += results.size
            }

            prefs.edit().putLong("last_full_probe", now).apply()
            Log.d(TAG, "Full probe complete: $totalVerified formats verified across ${StreamingApp.entries.size} apps")
        }
    }

    /**
     * Probe ONE specific app to discover working URI formats.
     * Called on-demand (after failure) or during periodic full probe.
     *
     * Returns the list of formats that resolved successfully.
     */
    fun probeApp(context: Context, app: StreamingApp): List<String> {
        val pm = context.packageManager
        val installedPkg = findInstalledPackage(context, app)
        if (installedPkg == null) {
            // App not installed — clear any cached data
            verifiedCache.remove(app.name)
            clearCachedFormats(context, app)
            return emptyList()
        }

        // Gather ALL candidate URI formats from every source
        val candidates = mutableListOf<String>()

        // Source 1: Config file formats
        candidates += DeepLinkConfig.getDeepLinkFormats(app.name)

        // Source 2: Hardcoded format
        candidates += app.deepLinkFormat

        // Source 3: Auto-generated discovery patterns
        candidates += generateCandidatePatterns(app, installedPkg)

        // Deduplicate while preserving order
        val uniqueCandidates = candidates.distinct()

        // Test each format with resolveActivity (NO actual launch!)
        val workingFormats = mutableListOf<String>()
        val extras = StreamingApp.getIntentExtras(app)
        val className = StreamingApp.getIntentClassName(app)

        for (format in uniqueCandidates) {
            if (testFormat(pm, format, installedPkg, extras, className)) {
                workingFormats.add(format)
            }
        }

        // Cache results
        verifiedCache[app.name] = workingFormats
        saveCachedFormats(context, app, workingFormats)

        if (workingFormats.isNotEmpty()) {
            Log.d(TAG, "${app.displayName}: ${workingFormats.size}/${uniqueCandidates.size} formats verified")
        } else {
            Log.w(TAG, "${app.displayName}: NO formats resolved (app-only launch will be used)")
        }

        return workingFormats
    }

    /**
     * Get verified (proven working) formats for an app.
     * Returns empty list if nothing has been verified yet.
     */
    fun getVerifiedFormats(app: StreamingApp): List<String> {
        return verifiedCache[app.name] ?: emptyList()
    }

    /**
     * Report that a format FAILED at runtime (startActivity threw an exception).
     * Removes the format from the verified cache so it won't be tried first next time.
     */
    fun reportFailure(context: Context, app: StreamingApp, failedFormat: String) {
        val current = verifiedCache[app.name]?.toMutableList() ?: return
        current.remove(failedFormat)
        verifiedCache[app.name] = current
        saveCachedFormats(context, app, current)
        Log.w(TAG, "${app.displayName}: Removed failed format from cache: $failedFormat")
    }

    /**
     * Get the number of apps with at least one verified format.
     * Used for the status indicator on the home screen.
     */
    fun getVerifiedAppCount(): Int {
        return verifiedCache.count { it.value.isNotEmpty() }
    }

    /**
     * Get total number of verified formats across all apps.
     */
    fun getTotalVerifiedFormats(): Int {
        return verifiedCache.values.sumOf { it.size }
    }

    /**
     * Check if the resolver has been loaded.
     */
    fun isLoaded(): Boolean = loaded

    // ==========================================
    // APP UPDATE DETECTION
    // ==========================================

    /**
     * Check if any streaming app was updated since our last probe.
     * If an app's version changed, it needs re-probing because the update
     * may have changed which URI formats the app supports.
     */
    private fun detectAppUpdates(context: Context): List<StreamingApp> {
        val pm = context.packageManager
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val needsReprobe = mutableListOf<StreamingApp>()

        for (app in StreamingApp.entries) {
            val pkg = findInstalledPackage(context, app) ?: continue

            val currentVersion = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName ?: "unknown"
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0).versionName ?: "unknown"
                }
            } catch (e: Exception) { continue }

            val savedKey = "version_${app.name}"
            val savedVersion = prefs.getString(savedKey, null)

            if (savedVersion != currentVersion) {
                needsReprobe.add(app)
                prefs.edit().putString(savedKey, currentVersion).apply()
                if (savedVersion != null) {
                    Log.d(TAG, "${app.displayName} updated: $savedVersion → $currentVersion")
                }
            }
        }
        return needsReprobe
    }

    // ==========================================
    // PATTERN GENERATION (THE "DISCOVERY" PART)
    // ==========================================

    /**
     * Generate candidate URI patterns to test beyond what's in the config.
     *
     * This is the "smart" part — we generate common URI patterns that streaming
     * apps typically use, so we can discover formats that aren't in our config.
     * If a streaming app adds a new URI scheme in an update, this catches it.
     */
    private fun generateCandidatePatterns(app: StreamingApp, pkg: String): List<String> {
        val patterns = mutableListOf<String>()

        // === Pattern Group 1: Known domain variations ===
        // Each streaming app has web domains they register for deep linking
        val domains = getKnownDomains(app)
        val pathVariants = listOf("watch", "video", "play", "title", "episode", "movie", "show")

        for (domain in domains) {
            for (path in pathVariants) {
                patterns += "https://$domain/$path/{id}"
            }
            // Some apps also handle http
            patterns += "http://${domains.firstOrNull() ?: continue}/watch/{id}"
        }

        // === Pattern Group 2: Custom URI schemes ===
        // Many apps register custom schemes like "netflix://", "hulu://", etc.
        val customSchemes = getCustomSchemes(app)
        for (scheme in customSchemes) {
            patterns += "$scheme://watch/{id}"
            patterns += "$scheme://play/{id}"
            patterns += "$scheme://video/{id}"
            patterns += "$scheme://{id}"
            patterns += "$scheme://content/{id}"
        }

        // === Pattern Group 3: Package-name-based scheme ===
        // Some apps use their package name as a scheme
        val shortPkg = pkg.substringAfterLast(".")
        if (shortPkg.length > 2) {
            patterns += "$shortPkg://watch/{id}"
            patterns += "$shortPkg://play/{id}"
        }

        return patterns
    }

    /**
     * Known web domains for each streaming app.
     * These are the domains apps register in their Android manifest to handle.
     */
    private fun getKnownDomains(app: StreamingApp): List<String> {
        return when (app) {
            StreamingApp.NETFLIX -> listOf("www.netflix.com", "netflix.com")
            StreamingApp.YOUTUBE -> listOf("www.youtube.com", "youtube.com", "youtu.be", "m.youtube.com")
            StreamingApp.HULU -> listOf("www.hulu.com", "hulu.com")
            StreamingApp.DISNEY_PLUS -> listOf("www.disneyplus.com", "disneyplus.com")
            StreamingApp.PRIME_VIDEO -> listOf("app.primevideo.com", "primevideo.com", "www.amazon.com")
            StreamingApp.HBO_MAX -> listOf("play.max.com", "play.hbomax.com", "max.com", "www.hbomax.com")
            StreamingApp.SLING_TV -> listOf("watch.sling.com", "www.sling.com")
            StreamingApp.PEACOCK -> listOf("www.peacocktv.com", "peacocktv.com")
            StreamingApp.PARAMOUNT_PLUS -> listOf("www.paramountplus.com", "paramountplus.com")
            StreamingApp.APPLE_TV -> listOf("tv.apple.com")
            StreamingApp.TUBI -> listOf("tubitv.com", "www.tubitv.com")
            StreamingApp.PLUTO_TV -> listOf("pluto.tv", "www.pluto.tv")
            StreamingApp.CRUNCHYROLL -> listOf("www.crunchyroll.com", "crunchyroll.com")
            StreamingApp.YOUTUBE_TV -> listOf("tv.youtube.com")
            StreamingApp.FUBO_TV -> listOf("www.fubo.tv", "fubo.tv")
            StreamingApp.DISCOVERY_PLUS -> listOf("www.discoveryplus.com", "discoveryplus.com")
            StreamingApp.PLEX -> listOf("app.plex.tv", "plex.tv")
            StreamingApp.STARZ -> listOf("www.starz.com", "starz.com")
        }
    }

    /**
     * Custom URI schemes that streaming apps are known to use.
     * These are non-HTTP schemes like "nflx://", "hulu://", etc.
     */
    private fun getCustomSchemes(app: StreamingApp): List<String> {
        return when (app) {
            StreamingApp.NETFLIX -> listOf("nflx", "netflix")
            StreamingApp.YOUTUBE -> listOf("vnd.youtube", "youtube")
            StreamingApp.HULU -> listOf("hulu")
            StreamingApp.DISNEY_PLUS -> listOf("disneyplus", "disney")
            StreamingApp.PRIME_VIDEO -> listOf("amzn", "primevideo")
            StreamingApp.HBO_MAX -> listOf("hbomax", "max")
            StreamingApp.SLING_TV -> listOf("sling")
            StreamingApp.PEACOCK -> listOf("peacock")
            StreamingApp.PARAMOUNT_PLUS -> listOf("paramountplus", "cbsaa")
            StreamingApp.APPLE_TV -> listOf("appletv", "videos")
            StreamingApp.TUBI -> listOf("tubi", "tubitv")
            StreamingApp.PLUTO_TV -> listOf("pluto", "plutotv")
            StreamingApp.CRUNCHYROLL -> listOf("crunchyroll")
            StreamingApp.YOUTUBE_TV -> listOf("yttv", "youtubeunplugged")
            StreamingApp.FUBO_TV -> listOf("fubo", "fubotv")
            StreamingApp.DISCOVERY_PLUS -> listOf("discoveryplus", "discovery")
            StreamingApp.PLEX -> listOf("plex")
            StreamingApp.STARZ -> listOf("starz")
        }
    }

    // ==========================================
    // INTENT TESTING
    // ==========================================

    /**
     * Test if a URI format would resolve to an activity WITHOUT launching it.
     *
     * Uses PackageManager.resolveActivity() which checks the app's intent filters.
     * Returns true if the intent would succeed, false otherwise.
     */
    private fun testFormat(
        pm: PackageManager,
        format: String,
        installedPkg: String,
        extras: Map<String, String>,
        className: String?
    ): Boolean {
        // Replace placeholder with a test ID
        val testUrl = format.replace("{id}", "test_probe_123")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(testUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Target the specific app
        if (className != null) {
            try {
                intent.setClassName(installedPkg, className)
            } catch (e: Exception) {
                intent.setPackage(installedPkg)
            }
        } else {
            intent.setPackage(installedPkg)
        }

        // Add extras
        for ((k, v) in extras) {
            intent.putExtra(k, v)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                ) != null
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // PACKAGE HELPERS
    // ==========================================

    /**
     * Find which package name is actually installed for an app.
     */
    private fun findInstalledPackage(context: Context, app: StreamingApp): String? {
        val primaryPkg = StreamingApp.getPackageName(app)
        if (isInstalled(context, primaryPkg)) return primaryPkg

        for (altPkg in StreamingApp.getAltPackageNames(app)) {
            if (isInstalled(context, altPkg)) return altPkg
        }
        return null
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // CACHE PERSISTENCE
    // ==========================================

    private fun saveCachedFormats(context: Context, app: StreamingApp, formats: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = formats.joinToString(FORMAT_SEPARATOR)
        prefs.edit()
            .putString("formats_${app.name}", encoded)
            .putLong("timestamp_${app.name}", System.currentTimeMillis())
            .apply()
    }

    private fun clearCachedFormats(context: Context, app: StreamingApp) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("formats_${app.name}")
            .remove("timestamp_${app.name}")
            .remove("version_${app.name}")
            .apply()
    }
}
