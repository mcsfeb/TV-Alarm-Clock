package com.mcsfeb.tvalarmclock.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.mcsfeb.tvalarmclock.service.AlarmAccessibilityService

/**
 * ProfileAutoSelector - Automatically clicks past profile selection screens.
 *
 * THE PROBLEM:
 * Most streaming apps (HBO Max, Netflix, Hulu, Disney+) show a "Who's watching?"
 * profile picker when they open. If the alarm launches the app while the user is
 * asleep, the profile screen blocks everything - the show never starts playing.
 *
 * THE SOLUTION:
 * After launching a streaming app, we wait a few seconds (for the app to load),
 * then send simulated D-pad key presses to click the first/default profile.
 *
 * HOW IT WORKS:
 * Uses the AlarmAccessibilityService to inject key events. The accessibility
 * service runs with elevated permissions that allow sending key presses to
 * other apps. The user needs to enable it once in Settings > Accessibility.
 *
 * TIMING:
 * - Wait 5 seconds after launch for the app to load
 * - Send DPAD_CENTER to select the currently highlighted profile
 * - Wait 2 more seconds, send ENTER as backup confirmation
 * - Wait 2 more seconds, send another DPAD_CENTER for secondary screens
 */
object ProfileAutoSelector {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Apps that are known to show profile selection screens on Android TV.
     * We only auto-click for these apps to avoid accidental key presses
     * on apps that go straight to content.
     */
    private val appsWithProfileScreens = setOf(
        "com.netflix.ninja",                      // Netflix
        "com.wbd.stream",                         // Max (HBO)
        "com.hbo.hbonow",                         // HBO Max (old)
        "com.hbo.max.android.tv",                 // HBO Max (alt)
        "com.hulu.livingroomplus",                // Hulu
        "com.disney.disneyplus",                  // Disney+
        "com.amazon.amazonvideo.livingroom",       // Prime Video
        "com.peacocktv.peacockandroid",            // Peacock
        "com.cbs.ott",                            // Paramount+
        "com.apple.atve.androidtv.appletv"        // Apple TV+
    )

    /**
     * Check if the accessibility service is enabled and ready.
     */
    fun isServiceEnabled(): Boolean = AlarmAccessibilityService.isRunning()

    /**
     * Schedule auto-profile-select key presses after launching a streaming app.
     *
     * @param packageName The package name of the launched app
     * @param initialDelayMs How long to wait for the app to load (default: 5 seconds)
     */
    fun scheduleAutoSelect(packageName: String, initialDelayMs: Long = 5000L) {
        // Only auto-click for apps known to have profile screens
        if (packageName !in appsWithProfileScreens) return

        // Need the accessibility service to be running
        if (!isServiceEnabled()) return

        // Step 1: Wait for the app to load, then press DPAD_CENTER
        // This selects the first/default profile
        handler.postDelayed({
            AlarmAccessibilityService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        }, initialDelayMs)

        // Step 2: Wait a bit more, then press ENTER as backup
        // Some apps need ENTER instead of DPAD_CENTER, or need a confirmation
        handler.postDelayed({
            AlarmAccessibilityService.instance?.sendKey(KeyEvent.KEYCODE_ENTER)
        }, initialDelayMs + 2000L)

        // Step 3: One more DPAD_CENTER after another delay
        // For apps that have a "Continue Watching" or other intermediary screen
        handler.postDelayed({
            AlarmAccessibilityService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
        }, initialDelayMs + 4000L)
    }

    /**
     * Cancel any pending auto-select key presses.
     * Call this if the user manually dismisses the alarm.
     */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
    }
}
