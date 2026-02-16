package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.ArrayDeque

/**
 * AlarmAccessibilityService - Monitors foreground apps and enables UI automation.
 *
 * Uses ADB-over-TCP for robust key injection and native accessibility for detection.
 * Uses AppNavigationGuide for tested deep link and DPAD strategies.
 *
 * STRATEGY (based on real device testing):
 * 1. Deep links are the PRIMARY way to open content (most reliable)
 * 2. Profile bypass uses DPAD (CENTER click, PIN entry for Disney+)
 * 3. DPAD navigation is a LAST RESORT — most apps are opaque to accessibility
 */
class AlarmAccessibilityService : AccessibilityService() {

    lateinit var uiController: UiController
        private set

    var currentPackage: String? = null
        private set

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    // State for pending navigation
    private var pendingNavigation: PendingNavigation? = null

    data class PendingNavigation(
        val packageName: String,
        val contentType: String,
        val identifiers: Map<String, String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Verified installed packages from real device scan
    private val streamingPackages = listOf(
        AppNavigationGuide.Packages.SLING,
        AppNavigationGuide.Packages.DISNEY_PLUS,
        AppNavigationGuide.Packages.HBO_MAX,
        AppNavigationGuide.Packages.HULU,
        AppNavigationGuide.Packages.PARAMOUNT,
        AppNavigationGuide.Packages.NETFLIX,
        AppNavigationGuide.Packages.YOUTUBE,
        AppNavigationGuide.Packages.PRIME_VIDEO,
        AppNavigationGuide.Packages.TUBI
    )

    // Keywords from real device testing
    private val homeScreenKeywords = listOf(
        "home", "search", "guide", "library", "my stuff", "live tv",
        "browse", "movies", "shows", "recommended for you", "sports on sling"
    )

    companion object {
        private const val TAG = "AlarmA11yService"
        @Volatile
        var instance: AlarmAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        uiController = UiController(this)

        // Initialize ADB connection
        AdbShell.init(this)
        Log.i(TAG, "Alarm Accessibility Service connected (Deep Link + DPAD Mode).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != "com.android.systemui") {
                if (pkg != currentPackage) {
                    Log.d(TAG, "Window/Content changed: $pkg")
                    currentPackage = pkg
                }

                // Always check for profile screen if it's a streaming app
                if (streamingPackages.any { pkg.contains(it) }) {
                    checkAndBypassProfile(pkg)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Accessibility Service destroyed.")
    }

    /**
     * Public API for ContentLauncher to trigger navigation recipes.
     */
    fun performContentNavigation(
        packageName: String,
        contentType: String,
        identifiers: Map<String, String>
    ): Boolean {
        Log.i(TAG, "Queuing navigation for $packageName ($contentType)")
        pendingNavigation = PendingNavigation(packageName, contentType, identifiers)

        // Trigger immediate check if we are already in the app
        if (currentPackage == packageName) {
            checkAndBypassProfile(packageName)
        }
        return true
    }

    private fun checkAndBypassProfile(pkg: String) {
        // Debounce: don't start a new job if one is running for the same package
        if (currentJob?.isActive == true) return

        currentJob = serviceScope.launch {
            try {
                // 1. Detection & Bypass (Always Active)
                val profileType = detectProfileScreen(pkg)
                if (profileType != ProfileType.NONE) {
                    bypassProfileScreen(pkg, profileType)
                }

                // 2. Navigation Recipe (if pending)
                val pending = pendingNavigation
                if (pending != null && pending.packageName == pkg) {
                    // Double check we are not still on profile
                    if (detectProfileScreen(pkg) == ProfileType.NONE) {
                        Log.i(TAG, "Executing pending navigation for $pkg")
                        runNavigationRecipe(pending)
                        pendingNavigation = null
                    } else {
                        Log.w(TAG, "Still on profile screen, skipping recipe.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in automation sequence", e)
            }
        }
    }

    // =====================================================================
    //  PROFILE DETECTION (improved with real resource IDs from device testing)
    // =====================================================================
    enum class ProfileType {
        NONE,           // Not a profile screen
        SIMPLE_CLICK,   // Just press CENTER to select first profile
        PIN_ENTRY,      // Disney+ style PIN pad
        OPAQUE_CLICK    // App is opaque but we detected "profile" keyword
    }

    private fun detectProfileScreen(packageName: String): ProfileType {
        val root = rootInActiveWindow ?: return ProfileType.NONE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var count = 0
        val maxNodes = 80
        var foundProfileKeyword = false
        var foundPinScreen = false
        var foundSlingProfile = false

        try {
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                count++

                if (count > maxNodes) {
                    if (node != root) node.recycle()
                    break
                }

                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val resId = node.viewIdResourceName ?: ""

                // Check for specific resource IDs first (most reliable)
                if (resId == AppNavigationGuide.ProfileResIds.SLING_PROFILE_SCREEN ||
                    resId.contains("SwitchUserProfile")) {
                    foundSlingProfile = true
                }
                if (resId == AppNavigationGuide.ProfileResIds.DISNEY_PIN_PROMPT ||
                    resId.contains("enterPin")) {
                    foundPinScreen = true
                }

                // Check keywords
                if (AppNavigationGuide.profileKeywords.any { text.contains(it) || desc.contains(it) }) {
                    foundProfileKeyword = true
                }

                // Negative match: if we see home screen content, we're past the profile
                if (homeScreenKeywords.any { text.contains(it) || desc.contains(it) }) {
                    if (node != root) node.recycle()
                    break // Not a profile screen
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }

                if (node != root) node.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in detectProfileScreen", e)
        } finally {
            while (!queue.isEmpty()) {
                val n = queue.poll()
                if (n != root) n?.recycle()
            }
            root.recycle()
        }

        return when {
            foundPinScreen -> ProfileType.PIN_ENTRY
            foundSlingProfile -> ProfileType.SIMPLE_CLICK
            foundProfileKeyword -> ProfileType.SIMPLE_CLICK
            else -> ProfileType.NONE
        }
    }

    // =====================================================================
    //  PROFILE BYPASS (improved with tested strategies)
    // =====================================================================
    private suspend fun bypassProfileScreen(packageName: String, type: ProfileType) {
        Log.i(TAG, "Profile screen detected for $packageName (type=$type) — starting bypass")

        when (type) {
            ProfileType.PIN_ENTRY -> {
                bypassDisneyPinScreen(packageName)
            }
            ProfileType.SIMPLE_CLICK, ProfileType.OPAQUE_CLICK -> {
                bypassSimpleProfileScreen(packageName)
            }
            ProfileType.NONE -> { /* No-op */ }
        }
    }

    private suspend fun bypassSimpleProfileScreen(packageName: String) {
        // Phase 1: Simple Center Click (tested: works on Sling, Netflix, most apps)
        Log.d(TAG, "Phase 1: Center Click")
        uiController.clickFocused()

        delay(2000)
        if (detectProfileScreen(packageName) == ProfileType.NONE) {
            Log.i(TAG, "Profile bypass successful (Phase 1).")
            return
        }

        // Phase 2: Double click (for apps where first click selects, second confirms)
        Log.d(TAG, "Phase 2: Double Center Click")
        uiController.clickFocused()
        delay(800)
        uiController.clickFocused()

        delay(1500)
        if (detectProfileScreen(packageName) == ProfileType.NONE) {
            Log.i(TAG, "Profile bypass successful (Phase 2).")
            return
        }

        // Phase 3: Navigate to first profile and click (Left x3 -> Center)
        Log.d(TAG, "Phase 3: Navigate Left then Click")
        repeat(3) {
            uiController.moveFocus(UiController.FOCUS_LEFT)
            delay(300)
        }
        uiController.clickFocused()

        delay(1500)
        if (detectProfileScreen(packageName) == ProfileType.NONE) {
            Log.i(TAG, "Profile bypass successful (Phase 3).")
            return
        }

        Log.w(TAG, "All profile bypass phases failed for $packageName")
    }

    private suspend fun bypassDisneyPinScreen(packageName: String) {
        // TODO: Get PIN from alarm settings / user configuration
        // For now, we can't bypass PIN-protected profiles without the PIN
        Log.w(TAG, "Disney+ PIN screen detected. PIN entry required but not configured.")
        Log.w(TAG, "User must configure their Disney+ PIN in alarm settings.")

        // If a PIN is configured in the future, use:
        // val sequence = AppNavigationGuide.generateDisneyPinSequence(pin)
        // for (keyCode in sequence) {
        //     uiController.sendKeyEvent(keyCode)
        //     delay(300)
        // }
    }

    // =====================================================================
    //  NAVIGATION RECIPES (deep link focused)
    // =====================================================================
    private suspend fun runNavigationRecipe(nav: PendingNavigation) {
        val ids = nav.identifiers
        Log.d(TAG, "Running recipe for ${nav.packageName}")

        delay(1500) // Stabilization delay after profile bypass

        // For most apps, deep links handle everything — the recipe is just a fallback
        // for when the deep link didn't fully work (e.g., landed on show page, need to click Play)
        when {
            nav.packageName.contains("netflix") -> runNetflixFallback(ids)
            nav.packageName.contains("hulu") -> runHuluFallback(ids)
            else -> runGenericFallback(ids)
        }
    }

    private suspend fun runNetflixFallback(ids: Map<String, String>) {
        // Netflix deep links usually go directly to playback
        // This fallback is for when we land on the show details page
        Log.d(TAG, "Netflix fallback: looking for Play/Resume button")
        delay(2000)
        retryAction("Click Play") {
            clickText("Play") || clickDesc("Play") || clickText("Resume") || clickDesc("Resume")
        }
    }

    private suspend fun runHuluFallback(ids: Map<String, String>) {
        // Hulu is opaque, but if deep link landed us on a show page, try clicking Play
        Log.d(TAG, "Hulu fallback: looking for Play button")
        delay(2000)
        retryAction("Click Play") {
            clickText("Play") || clickText("Resume") || clickText("Watch")
        }
    }

    private suspend fun runGenericFallback(ids: Map<String, String>) {
        // Generic: just try to find and click a Play/Watch button
        Log.d(TAG, "Generic fallback: looking for Play button")
        delay(2000)
        retryAction("Click Play") {
            clickText("Play") || clickDesc("Play") ||
            clickText("Resume") || clickDesc("Resume") ||
            clickText("Watch") || clickDesc("Watch")
        }
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================
    private suspend fun retryAction(name: String, maxRetries: Int = 3, action: () -> Boolean): Boolean {
        repeat(maxRetries) { i ->
            if (action()) {
                Log.d(TAG, "Action '$name' succeeded on attempt ${i + 1}")
                return true
            }
            delay(500)
        }
        Log.e(TAG, "Action '$name' failed after $maxRetries attempts")
        return false
    }

    private fun clickText(text: String): Boolean {
        return uiController.findAndClick(text = text)
    }

    private fun clickDesc(desc: String): Boolean {
        return uiController.findAndClick(description = desc)
    }
}
