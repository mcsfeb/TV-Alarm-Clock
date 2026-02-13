package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AlarmAccessibilityService - The backbone of the "Smart Assistant" feature.
 *
 * This service is the bridge between our app and other streaming apps. It grants
 * us the ability to read the screen and perform actions, enabling powerful UI automation.
 *
 * It now holds an instance of the UiController, which is the class that does the actual work
 * of finding and clicking buttons, typing text, etc.
 *
 * SETUP:
 * Must be enabled by the user once in Settings > Accessibility > TV Alarm Clock.
 */
class AlarmAccessibilityService : AccessibilityService() {

    lateinit var uiController: UiController
        private set

    companion object {
        private const val TAG = "AlarmA11yService"
        var instance: AlarmAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        uiController = UiController(this)
        Log.d(TAG, "Service connected and UiController initialized.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The UiController may need to react to events in the future,
        // but for now, we operate on-demand.
    }

    override fun onInterrupt() {
        // This is called when the service is interrupted, e.g., by another service.
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Service destroyed.")
    }
}
