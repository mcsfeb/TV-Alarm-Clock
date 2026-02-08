package com.mcsfeb.tvalarmclock.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.IOException

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
 * 1. We use Android's "input" shell command to simulate remote control presses
 * 2. First we send DPAD_CENTER to select the currently highlighted profile
 * 3. Some apps need an extra ENTER key press to confirm
 * 4. If the shell command fails (some devices block it), it fails silently -
 *    the app still opens, user just has to pick their profile manually
 *
 * TIMING:
 * - Wait 4 seconds after launch for the app to load
 * - Send the first key press (select profile)
 * - Wait 2 more seconds for any confirmation dialog
 * - Send a second key press (confirm if needed)
 *
 * NOTE: This uses Runtime.exec("input keyevent ...") which works on most
 * Android TV devices. It does NOT require root. If a device blocks this
 * command, the auto-select simply won't work and the user picks manually.
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
     * Schedule auto-profile-select key presses after launching a streaming app.
     *
     * @param packageName The package name of the launched app
     * @param initialDelayMs How long to wait for the app to load (default: 5 seconds)
     */
    fun scheduleAutoSelect(packageName: String, initialDelayMs: Long = 5000L) {
        // Only auto-click for apps known to have profile screens
        if (packageName !in appsWithProfileScreens) return

        // Step 1: Wait for the app to load, then press DPAD_CENTER
        // This selects the first/default profile
        handler.postDelayed({
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        }, initialDelayMs)

        // Step 2: Wait a bit more, then press ENTER as backup
        // Some apps need ENTER instead of DPAD_CENTER, or need a confirmation
        handler.postDelayed({
            sendKeyEvent(KeyEvent.KEYCODE_ENTER)
        }, initialDelayMs + 2000L)

        // Step 3: One more DPAD_CENTER after another delay
        // For apps that have a "Continue Watching" or other intermediary screen
        handler.postDelayed({
            sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        }, initialDelayMs + 4000L)
    }

    /**
     * Cancel any pending auto-select key presses.
     * Call this if the user manually dismisses the alarm.
     */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Send a simulated key event using the Android "input" shell command.
     *
     * This works on most Android TV devices without root.
     * The "input keyevent" command simulates a press on the TV remote.
     *
     * If the command fails (device blocks it), we just silently ignore it.
     * The streaming app still opens - user just has to pick their profile.
     */
    private fun sendKeyEvent(keyCode: Int) {
        try {
            // Run on a background thread to avoid blocking the UI
            Thread {
                try {
                    val process = Runtime.getRuntime().exec("input keyevent $keyCode")
                    process.waitFor()
                } catch (e: IOException) {
                    // Shell command not available on this device - that's OK
                    // The app still opened, user just picks profile manually
                } catch (e: InterruptedException) {
                    // Thread was interrupted - that's fine
                } catch (e: Exception) {
                    // Any other error - silently ignore
                }
            }.start()
        } catch (e: Exception) {
            // If we can't even start the thread, just skip it
        }
    }
}
