package com.mcsfeb.tvalarmclock.data.config

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * DeepLinkConfig - Loads deep link settings from a JSON config file.
 *
 * WHY A CONFIG FILE?
 * Streaming apps change their deep link formats, package names, and behaviors
 * with updates. Instead of changing code every time Netflix or Hulu pushes
 * an update, we edit a simple JSON file in assets/deep_link_config.json.
 *
 * HOW IT WORKS:
 * 1. On first use, loads the JSON file from assets/ and caches it in memory
 * 2. Each streaming app has an entry with its deep link URIs, package names, etc.
 * 3. StreamingLauncher reads from this config instead of hardcoded values
 * 4. If the config fails to load, hardcoded defaults in StreamingApp.kt are used
 *
 * TO UPDATE DEEP LINKS:
 * Just edit app/src/main/assets/deep_link_config.json and rebuild.
 * Bump the configVersion number when you make changes.
 */
object DeepLinkConfig {

    private const val TAG = "DeepLinkConfig"
    private const val CONFIG_FILE = "deep_link_config.json"

    /** Cached config data - loaded once, used many times */
    private var cachedConfig: Map<String, AppConfig>? = null
    private var configVersion: Int = 0

    /**
     * AppConfig - All the deep link info for one streaming app.
     *
     * @param packageName Primary Android package name
     * @param altPackageNames Alternate packages to check (old versions, different TV brands)
     * @param deepLinkFormats List of URI formats to try, in order. First one is tried first.
     *   Use {id} as placeholder for the content ID.
     * @param searchUrl URL pattern for searching within the app. Use {query} as placeholder.
     * @param intentExtras Key-value pairs to add to the launch intent (e.g., Netflix source=30)
     * @param intentClassName Specific Activity class to target (e.g., Netflix's MainActivity)
     * @param needsForceStopBefore If true, force-stop the app before launching (Hulu quirk)
     * @param contentIdLabel What to call the content ID in the UI (e.g., "Netflix Title ID")
     * @param contentIdHint Help text showing the user what a content ID looks like
     */
    data class AppConfig(
        val packageName: String,
        val altPackageNames: List<String>,
        val deepLinkFormats: List<String>,
        val searchUrl: String?,
        val intentExtras: Map<String, String>,
        val intentClassName: String?,
        val needsForceStopBefore: Boolean,
        val contentIdLabel: String,
        val contentIdHint: String
    )

    /**
     * Load the config from assets. Call this once during app startup.
     * Safe to call multiple times - only loads once.
     */
    fun load(context: Context) {
        if (cachedConfig != null) return  // Already loaded

        try {
            val jsonString = context.assets.open(CONFIG_FILE)
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(jsonString)
            configVersion = root.optInt("configVersion", 0)

            val apps = root.getJSONObject("apps")
            val configMap = mutableMapOf<String, AppConfig>()

            for (appKey in apps.keys()) {
                val appJson = apps.getJSONObject(appKey)
                configMap[appKey] = parseAppConfig(appJson)
            }

            cachedConfig = configMap
            Log.d(TAG, "Loaded deep link config v$configVersion with ${configMap.size} apps")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load deep link config, using hardcoded defaults: ${e.message}")
            cachedConfig = emptyMap()
        }
    }

    /**
     * Get the config for a specific streaming app by its enum name.
     * Returns null if not found in config (caller should fall back to hardcoded defaults).
     */
    fun getAppConfig(appEnumName: String): AppConfig? {
        return cachedConfig?.get(appEnumName)
    }

    /**
     * Get all deep link format URIs for an app, ordered by preference.
     * Returns empty list if app not found.
     */
    fun getDeepLinkFormats(appEnumName: String): List<String> {
        return cachedConfig?.get(appEnumName)?.deepLinkFormats ?: emptyList()
    }

    /**
     * Get the search URL for an app, with {query} placeholder.
     * Returns null if app doesn't support search deep links.
     */
    fun getSearchUrl(appEnumName: String): String? {
        return cachedConfig?.get(appEnumName)?.searchUrl
    }

    /**
     * Get intent extras for an app (e.g., Netflix's source=30).
     * Returns empty map if none needed.
     */
    fun getIntentExtras(appEnumName: String): Map<String, String> {
        return cachedConfig?.get(appEnumName)?.intentExtras ?: emptyMap()
    }

    /**
     * Get the specific Activity class name to target, if any.
     * Returns null if the app should be launched via package only.
     */
    fun getIntentClassName(appEnumName: String): String? {
        return cachedConfig?.get(appEnumName)?.intentClassName
    }

    /**
     * Check if app needs force-stop before re-launching (Hulu quirk).
     */
    fun needsForceStop(appEnumName: String): Boolean {
        return cachedConfig?.get(appEnumName)?.needsForceStopBefore ?: false
    }

    /**
     * Get the current config version number.
     */
    fun getConfigVersion(): Int = configVersion

    /**
     * Check if config has been loaded.
     */
    fun isLoaded(): Boolean = cachedConfig != null

    /**
     * Force reload (for testing or after config updates).
     */
    fun forceReload(context: Context) {
        cachedConfig = null
        load(context)
    }

    // ---- Private helpers ----

    private fun parseAppConfig(json: JSONObject): AppConfig {
        val altPackages = mutableListOf<String>()
        val altArray = json.optJSONArray("altPackageNames")
        if (altArray != null) {
            for (i in 0 until altArray.length()) {
                altPackages.add(altArray.getString(i))
            }
        }

        val deepLinks = mutableListOf<String>()
        val dlArray = json.optJSONArray("deepLinkFormats")
        if (dlArray != null) {
            for (i in 0 until dlArray.length()) {
                deepLinks.add(dlArray.getString(i))
            }
        }

        val extras = mutableMapOf<String, String>()
        val extrasJson = json.optJSONObject("intentExtras")
        if (extrasJson != null) {
            for (key in extrasJson.keys()) {
                extras[key] = extrasJson.getString(key)
            }
        }

        return AppConfig(
            packageName = json.getString("packageName"),
            altPackageNames = altPackages,
            deepLinkFormats = deepLinks,
            searchUrl = if (json.isNull("searchUrl")) null
                else json.optString("searchUrl", "").ifBlank { null },
            intentExtras = extras,
            intentClassName = if (json.isNull("intentClassName")) null
                else json.optString("intentClassName", "").ifBlank { null },
            needsForceStopBefore = json.optBoolean("needsForceStopBefore", false),
            contentIdLabel = json.optString("contentIdLabel", "Content ID"),
            contentIdHint = json.optString("contentIdHint", "")
        )
    }
}
