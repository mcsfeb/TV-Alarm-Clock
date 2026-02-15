package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.ArrayDeque

/**
 * AlarmAccessibilityService - Monitors foreground apps and enables UI automation.
 * 
 * Simplified TV-first bypass logic using native accessibility actions.
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

    private val streamingPackages = listOf(
        "com.paramountplus", "com.cbs.ott", "com.wbd.stream", "com.hbo", "com.max", 
        "com.netflix.ninja", "com.sling", "com.disney.disneyplus", "com.hulu.plus",
        "com.amazon.amazonvideo.livingroom", "com.peacocktv.peacockandroid", 
        "com.google.android.youtube.tv", "com.tubitv.tv", "tv.pluto.android"
    )

    // Profile screen keywords — must be specific enough to avoid false positives
    // on home screens. "who" alone is too broad; "default"/"primary" appear elsewhere.
    private val profileKeywords = listOf(
        "who's watching", "who is watching", "choose your profile",
        "select profile", "switch profile", "continue watching as",
        "manage profiles", "add profile", "edit profile"
    )

    // Home screen keywords — if we see any of these, we're past the profile screen
    private val homeScreenKeywords = listOf(
        "home", "search", "guide", "library", "my stuff", "live tv",
        "browse", "movies", "shows", "new & popular", "watchlist"
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
        Log.i(TAG, "Alarm Accessibility Service connected (TV Mode).")
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
                if (isProfileScreen(pkg)) {
                    bypassProfileScreen(pkg)
                }

                // 2. Navigation Recipe (if pending)
                val pending = pendingNavigation
                if (pending != null && pending.packageName == pkg) {
                    // Wait a moment for any profile bypass transition to settle
                    delay(1000)
                    // Double check we are not still on profile
                    if (!isProfileScreen(pkg)) {
                        Log.i(TAG, "Executing pending navigation for $pkg")
                        runNavigationRecipe(pending)
                        pendingNavigation = null
                    } else {
                        Log.w(TAG, "Still on profile screen, will retry on next event.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in automation sequence", e)
            }
        }
    }

    /**
     * Bypasses profile selection screens using real DPAD key events.
     *
     * TV apps do NOT respond to accessibility ACTION_CLICK or gesture taps on
     * their profile screens. They ONLY respond to DPAD key events (left, right,
     * center). This method uses `input keyevent` via shell to send real key
     * events that the app will actually process.
     *
     * Strategy phases:
     * 1. DPAD_CENTER x2 — most profile screens pre-focus the default/first profile
     * 2. Navigate LEFT to first profile, then CENTER — handles off-center focus
     * 3. Navigate UP then LEFT then CENTER — handles apps with vertical layout
     * 4. Try accessibility-based click on visible profile nodes as last resort
     */
    private suspend fun bypassProfileScreen(packageName: String) {
        Log.i(TAG, "Profile screen detected for $packageName — starting DPAD bypass")

        // Phase 1: Double-tap DPAD_CENTER (most apps pre-focus the default profile)
        Log.d(TAG, "Phase 1: Double-tap DPAD_CENTER")
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        delay(600)
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

        delay(2000)
        if (!isProfileScreen(packageName)) {
            Log.i(TAG, "Profile bypass successful (Phase 1: double DPAD_CENTER).")
            return
        }

        // Phase 2: Navigate to first profile (LEFT x3 to be safe), then CENTER
        Log.d(TAG, "Phase 2: DPAD_LEFT x3 -> DPAD_CENTER")
        repeat(3) {
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            delay(300)
        }
        delay(300)
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

        delay(2000)
        if (!isProfileScreen(packageName)) {
            Log.i(TAG, "Profile bypass successful (Phase 2: left-then-center).")
            return
        }

        // Phase 3: Try UP first (some apps like Max/Paramount have vertical layout)
        Log.d(TAG, "Phase 3: DPAD_UP -> DPAD_LEFT x2 -> DPAD_CENTER")
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP)
        delay(300)
        repeat(2) {
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            delay(300)
        }
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

        delay(2000)
        if (!isProfileScreen(packageName)) {
            Log.i(TAG, "Profile bypass successful (Phase 3: up-left-center).")
            return
        }

        // Phase 4: Try DOWN first (some apps put profiles below a header/logo)
        Log.d(TAG, "Phase 4: DPAD_DOWN -> DPAD_LEFT x2 -> DPAD_CENTER")
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        delay(300)
        repeat(2) {
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            delay(300)
        }
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

        delay(2000)
        if (!isProfileScreen(packageName)) {
            Log.i(TAG, "Profile bypass successful (Phase 4: down-left-center).")
            return
        }

        // Phase 5: Last resort — try accessibility click on any profile-like node
        Log.w(TAG, "Phase 5: All DPAD phases failed. Trying accessibility node click.")
        tryAccessibilityProfileClick(packageName)
    }

    /**
     * Last-resort fallback: find clickable nodes that look like profile items
     * and try ACTION_CLICK directly. This rarely works on TV apps but costs nothing to try.
     */
    private fun tryAccessibilityProfileClick(packageName: String) {
        val root = rootInActiveWindow ?: return
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (!queue.isEmpty() && visited < 80) {
                val node = queue.poll() ?: continue
                visited++

                // Look for clickable ImageViews or FrameLayouts (profile avatars)
                if (node.isClickable && node.isVisibleToUser) {
                    val cls = node.className?.toString() ?: ""
                    if (cls.contains("Image") || cls.contains("FrameLayout") || cls.contains("Card")) {
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        if (r.width() > 80 && r.height() > 80 && r.width() < 600) {
                            Log.d(TAG, "Attempting accessibility click on profile node: $cls at ${r}")
                            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                Log.i(TAG, "Accessibility click succeeded on profile node.")
                                if (node != root) node.recycle()
                                while (!queue.isEmpty()) {
                                    val n = queue.poll()
                                    if (n != root) n?.recycle()
                                }
                                return
                            }
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                if (node != root) node.recycle()
            }

            // Clean up remaining
            while (!queue.isEmpty()) {
                val n = queue.poll()
                if (n != root) n?.recycle()
            }
        } finally {
            root.recycle()
        }
        Log.e(TAG, "All profile bypass methods exhausted for $packageName.")
    }

    private fun isProfileScreen(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        var count = 0
        val maxNodes = 60 // Limit scan depth

        try {
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                count++
                
                if (count > maxNodes) {
                    if (node != root) node.recycle()
                    while (!queue.isEmpty()) {
                         val n = queue.poll()
                         if (n != root) n?.recycle()
                    }
                    return false
                }

                val text = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""

                // Positive Match
                if (profileKeywords.any { text.contains(it) || desc.contains(it) }) {
                    if (node != root) node.recycle()
                    while (!queue.isEmpty()) {
                         val n = queue.poll()
                         if (n != root) n?.recycle()
                    }
                    return true
                }
                
                // Negative (Fail Fast) Match
                if (homeScreenKeywords.any { text.contains(it) || desc.contains(it) }) {
                     if (node != root) node.recycle()
                     while (!queue.isEmpty()) {
                         val n = queue.poll()
                         if (n != root) n?.recycle()
                    }
                    return false
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                
                if (node != root) {
                    node.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in isProfileScreen", e)
        } finally {
            while (!queue.isEmpty()) {
                 val n = queue.poll()
                 if (n != root) n?.recycle()
            }
            root.recycle()
        }
        return false
    }

    private suspend fun runNavigationRecipe(nav: PendingNavigation) {
        val ids = nav.identifiers
        Log.d(TAG, "Running recipe for ${nav.packageName}")
        
        delay(1500) // Initial stabilization delay

        when {
            nav.packageName.contains("netflix") -> runNetflixRecipe(nav.contentType, ids)
            nav.packageName.contains("sling") -> runSlingRecipe(nav.contentType, ids)
            else -> {
                Log.w(TAG, "No specific recipe for ${nav.packageName}. Running generic search.")
                runGenericSearch(ids["showName"] ?: ids["title"] ?: "")
            }
        }
    }

    private suspend fun runNetflixRecipe(contentType: String, ids: Map<String, String>) {
        val showName = ids["showName"] ?: ids["title"] ?: return
        val season = ids["season"]
        val episode = ids["episode"]
        val searchText = if (season != null && episode != null) "$showName S${season}E${episode}" else showName
        Log.d(TAG, "Netflix Recipe: Searching for '$searchText'")

        // Try accessibility click on "Search" first, then DPAD fallback
        if (!retryAction("Find Search") { clickText("Search") || clickDesc("Search") }) {
            // DPAD fallback: navigate to top-left where Search usually lives
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP)
            delay(300)
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            delay(300)
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        }
        delay(1000)
        retryAction("Type Text") { setText(searchText) }
        delay(2000)
        // Move to first result and select it
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        delay(500)
        uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        delay(1500)
        retryAction("Click Play") { clickText("Play") || clickDesc("Play") || clickText("Resume") }
    }

    private suspend fun runSlingRecipe(contentType: String, ids: Map<String, String>) {
        val channelName = ids["channelName"] ?: return
        Log.d(TAG, "Sling Recipe: Tune to channel '$channelName'")
        delay(2000)
        // Navigate down to Guide/Live TV section
        repeat(4) {
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            delay(300)
        }
        if (!retryAction("Find Guide") { clickText("Guide") || clickText("Live TV") }) {
            // Fallback: try search
            clickDesc("Search")
            delay(500)
            setText(channelName)
            delay(2000)
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            delay(300)
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    private suspend fun runGenericSearch(query: String) {
        if (query.isBlank()) return
        Log.d(TAG, "Generic Search for '$query'")
        delay(2000)
        if (clickText("Search") || clickDesc("Search")) {
            delay(1000)
            setText(query)
            delay(2000)
            // Select first result
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            delay(500)
            uiController.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            delay(1000)
            clickText("Play")
        }
    }

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

    private fun setText(text: String): Boolean {
        return uiController.typeText(text)
    }
}
