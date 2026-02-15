package com.mcsfeb.tvalarmclock.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import com.mcsfeb.tvalarmclock.service.UiController

/**
 * ProfileAutoSelector - Orchestrates UI automation for profile selection and content search.
 */
object ProfileAutoSelector {

    private const val TAG = "AutomationManager"
    private val handler = Handler(Looper.getMainLooper())
    private val ui: UiController? get() = AlarmAccessibilityService.instance?.uiController

    private val appsWithProfiles = setOf(
        "com.netflix.ninja",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.hbo.max.android.tv",
        "com.hulu.livingroomplus",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.peacocktv.peacockandroid",
        "com.apple.atve.androidtv.appletv",
        "com.paramountplus.mobile",
        "com.cbs.ott"
    )

    fun isServiceEnabled(): Boolean {
        val enabled = AlarmAccessibilityService.isRunning()
        if (!enabled) Log.w(TAG, "Accessibility Service is NOT running!")
        return enabled
    }

    fun needsProfileSelect(packageName: String): Boolean = packageName in appsWithProfiles

    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Cancelled all pending UI automation")
    }

    /**
     * Bypasses profile screens by selecting the default profile.
     *
     * Strategy: Double-tap DPAD_CENTER. On Android TV profile screens
     * (Netflix "Who's Watching?", Max, Disney+, etc.), the first CENTER
     * press focuses/highlights the default profile, the second press
     * selects it. We retry this pattern a few times as the app loads.
     */
    fun scheduleAutoSelect(packageName: String, initialDelay: Long = 4000L) {
        if (!isServiceEnabled()) return
        Log.d(TAG, "Starting profile auto-select routine for $packageName with delay ${initialDelay}ms")

        // Attempt 1: Double-tap DPAD_CENTER after app loads
        handler.postDelayed({
            Log.d(TAG, "Profile attempt 1: Double-tap DPAD_CENTER")
            doubleTapCenter()
        }, initialDelay)

        // Attempt 2: Try again in case app was slow to reach profile screen
        handler.postDelayed({
            Log.d(TAG, "Profile attempt 2: Double-tap DPAD_CENTER")
            doubleTapCenter()
        }, initialDelay + 3000L)

        // Attempt 3: Final try for very slow-loading apps
        handler.postDelayed({
            Log.d(TAG, "Profile attempt 3: Double-tap DPAD_CENTER")
            doubleTapCenter()
        }, initialDelay + 6000L)
    }

    /**
     * Double-tap DPAD_CENTER with a short gap.
     * First press focuses the default profile, second press selects it.
     */
    private fun doubleTapCenter() {
        ui?.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        handler.postDelayed({
            ui?.sendKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        }, 400L)
    }

    fun runNetflixRecipe(show: String, season: String?, episode: String?) {
        if (!isServiceEnabled()) return
        val query = buildString {
            append(show)
            if (season != null) append(" S$season")
            if (episode != null) append(" E$episode")
        }
        Log.d(TAG, "Running Netflix search recipe: $query")

        val steps = listOf(
            { tryClick(description = "Search") || tryClick(text = "Search") } to 7000L,
            { ui?.typeText(query) } to 2000L,
            { tryClick(description = show) || (ui?.clickFocused() ?: false) } to 4000L,
            { tryClick(text = "Episodes") } to 3000L,
            { tryClick(text = "Play") || tryClick(description = "Play") } to 3000L
        )
        runSteps(steps)
    }

    fun runSlingRecipe(channelName: String) {
        if (!isServiceEnabled()) return
        Log.d(TAG, "Running Sling search recipe: $channelName")

        val steps = listOf(
            { tryClick(text = "Search") || tryClick(description = "Search") } to 8000L,
            { ui?.typeText(channelName) } to 2000L,
            { tryClick(text = channelName) || (ui?.clickFocused() ?: false) } to 4000L,
            { tryClick(text = "Watch") || tryClick(text = "Play") } to 3000L
        )
        runSteps(steps)
    }

    fun scheduleSearchAndPlay(searchQuery: String, targetPackage: String) {
        if (!isServiceEnabled()) return
        Log.d(TAG, "Running generic search fallback for '$searchQuery'")

        val steps = listOf(
            { tryClick(text = "Search", packageName = targetPackage) } to 7000L,
            { ui?.typeText(searchQuery) } to 2000L,
            { tryClick(text = searchQuery, packageName = targetPackage) || (ui?.clickFocused() ?: false) } to 5000L,
            { tryClick(text = "Play") || tryClick(text = "Watch") } to 3000L
        )
        runSteps(steps)
    }

    /** Helper: tries findAndClick, returns false if ui is null or node not found. */
    private fun tryClick(
        text: String? = null,
        description: String? = null,
        packageName: String? = null
    ): Boolean = ui?.findAndClick(text = text, description = description, packageName = packageName) ?: false

    private fun runSteps(steps: List<Pair<() -> Any?, Long>>) {
        var cumulativeDelay = 0L
        steps.forEach { (action, delay) ->
            cumulativeDelay += delay
            handler.postDelayed({
                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "Step failed: ${e.message}")
                }
            }, cumulativeDelay)
        }
    }
}
