package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UiController - Advanced interaction helper for Accessibility Service.
 * Upgraded with multi-window scanning and parent-click fallbacks.
 */
class UiController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "UiController"
    }

    fun findAndClick(
        text: String? = null,
        description: String? = null,
        resId: String? = null,
        packageName: String? = null
    ): Boolean {
        val node = findNodeInAllWindows { node ->
            val textMatch = text == null || node.text?.toString()?.contains(text, ignoreCase = true) == true
            val descMatch = description == null || node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
            val resIdMatch = resId == null || node.viewIdResourceName == resId
            val pkgMatch = packageName == null || node.packageName == packageName
            textMatch && descMatch && resIdMatch && pkgMatch
        }

        return if (node != null) {
            Log.d(TAG, "Clicking node: [Text: ${node.text}, Desc: ${node.contentDescription}]")
            performClick(node)
        } else {
            Log.w(TAG, "Node not found for click: text=$text, desc=$description")
            false
        }
    }

    fun clickFocused(): Boolean {
        // Look in ALL windows for focus, not just the active one
        val node = findNodeInAllWindows { it.isFocused }
            ?: findNodeInAllWindows { it.isAccessibilityFocused }
        return if (node != null) {
            Log.d(TAG, "Clicking focused node: ${node.className}")
            performClick(node)
        } else {
            // If no node is focused in the UI tree, simulate a DPAD_CENTER tap
            // via gesture at screen center. This bypasses apps that hide focus.
            Log.w(TAG, "No focus found in UI tree. Simulating DPAD_CENTER via center-screen tap.")
            val displayMetrics = service.resources.displayMetrics
            val cx = displayMetrics.widthPixels / 2f
            val cy = displayMetrics.heightPixels / 2f
            val path = android.graphics.Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
    }

    fun typeText(text: String): Boolean {
        val node = findNodeInAllWindows { it.isFocused && it.isEditable } ?: findNodeInAllWindows { it.isEditable }
        return if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Type '$text' success: $success")
            success
        } else {
            Log.w(TAG, "No editable field found to type text.")
            false
        }
    }

    private fun findNodeInAllWindows(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Scan windows in reverse (top-most first)
        val windows = service.windows
        for (i in windows.indices.reversed()) {
            val root = windows[i].root ?: continue
            val result = findInsideNode(root, predicate)
            if (result != null) return result
        }
        // Fallback to active window
        return service.rootInActiveWindow?.let { findInsideNode(it, predicate) }
    }

    private fun findInsideNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = mutableListOf(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0) ?: continue
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        
        // Fallback 1: Click parent
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        
        // Fallback 2: Gesture Tap
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        
        Log.d(TAG, "Standard click failed. Performing gesture tap at ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return service.dispatchGesture(gesture, null, null)
    }
}
