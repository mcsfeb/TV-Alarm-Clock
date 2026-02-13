package com.mcsfeb.tvalarmclock.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService
import com.mcsfeb.tvalarmclock.service.UiController

/**
 * ProfileAutoSelector - Now a high-level UI automation scheduler.
 *
 * This object is responsible for scheduling UI automation tasks after an app is launched.
 * It uses the powerful UiController to interact with other apps via the Accessibility Service.
 *
 * PRIMARY TASKS:
 * 1. scheduleAutoSelect(): Clicks past "Who's Watching?" profile screens.
 * 2. scheduleSearchAndPlay(): A multi-step process to search for content and play it.
 */
object ProfileAutoSelector {

    private const val TAG = "UiAutomation"
    private val handler = Handler(Looper.getMainLooper())
    private val uiController: UiController? get() = AlarmAccessibilityService.instance?.uiController

    private val appsWithProfileScreens = setOf(
        "com.netflix.ninja",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.hbo.max.android.tv",
        "com.hulu.livingroomplus",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.peacocktv.peacockandroid",
        "com.cbs.ott",
        "com.apple.atve.androidtv.appletv"
    )

    fun isServiceEnabled(): Boolean = AlarmAccessibilityService.isRunning()

    fun scheduleAutoSelect(packageName: String, initialDelayMs: Long = 4000L) {
        if (packageName !in appsWithProfileScreens) {
            Log.d(TAG, "Skipping auto-select for $packageName (not a profile-screen app)")
            return
        }
        if (!isServiceEnabled()) {
            Log.w(TAG, "Cannot auto-select profile: Accessibility Service not running.")
            return
        }

        Log.d(TAG, "Scheduling 5 click attempts for $packageName")
        // Staggered attempts to click the focused profile.
        (0..4).forEach { i ->
            val delay = initialDelayMs + (i * 2500L)
            handler.postDelayed({
                Log.d(TAG, "Attempt ${i + 1} to select profile...")
                uiController?.clickFocusedElement()
            }, delay)
        }
    }

    /**
     * Schedules a sequence of UI actions to find and play content.
     * This is the heart of the "Smart Assistant" fallback.
     *
     * @param searchQuery The title of the show/movie to search for.
     * @param targetPackage The package name of the streaming app.
     */
    fun scheduleSearchAndPlay(searchQuery: String, targetPackage: String) {
        if (!isServiceEnabled()) {
            Log.e(TAG, "Cannot search-and-play: Accessibility Service not running.")
            return
        }

        Log.d(TAG, "Scheduling search-and-play for '$searchQuery' in $targetPackage")

        // The sequence of operations, with delays between each.
        val automationSequence = listOf(
            // Step 1: Wait for app to load, then find and click the "Search" icon/button.
            { uiController?.findAndClick(text = "Search", packageName = targetPackage) } to 6000L,

            // Step 2: Type the search query into the text field.
            { uiController?.typeText(searchQuery) } to 2000L,

            // Step 3: Wait for search results, then find and click the matching show/movie poster.
            { uiController?.findAndClick(descriptionContains = searchQuery, packageName = targetPackage) } to 5000L,

            // Step 4: Wait for content page to load, then find and click the "Play" button.
            { uiController?.findAndClick(text = "Play", packageName = targetPackage) } to 5000L
        )

        var cumulativeDelay = 0L
        automationSequence.forEach { (action, delay) ->
            cumulativeDelay += delay
            handler.postDelayed({
                val success = action.invoke() ?: false
                if (!success) {
                    Log.w(TAG, "A step in the search-and-play sequence failed. Aborting.")
                    cancelPending()
                    // Optional: Consider a root-based fallback here if this step fails.
                }
            }, cumulativeDelay)
        }
    }

    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Cancelled all pending UI automation")
    }
}
