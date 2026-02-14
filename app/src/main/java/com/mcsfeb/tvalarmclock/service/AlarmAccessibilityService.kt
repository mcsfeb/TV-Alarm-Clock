package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AlarmAccessibilityService - Monitors foreground apps and enables UI automation.
 */
class AlarmAccessibilityService : AccessibilityService() {

    lateinit var uiController: UiController
        private set

    var currentPackage: String? = null
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
        Log.i(TAG, "Alarm Accessibility Service connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // TYPE_WINDOW_STATE_CHANGED is the most reliable way to track app changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != "com.android.systemui") {
                Log.d(TAG, "Window state changed: $pkg")
                currentPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service destroyed.")
    }
}
