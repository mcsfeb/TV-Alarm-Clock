package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * UiController - Advanced interaction helper for Accessibility Service.
 *
 * TV-FIRST DESIGN: On Android TV, apps navigate via DPAD key events, NOT touch.
 * Accessibility actions (ACTION_CLICK, focusSearch) often fail on other apps'
 * UI trees because those apps don't expose their focus model to external services.
 *
 * Therefore, this controller uses real DPAD key events (via `input keyevent`)
 * as the PRIMARY interaction method, with accessibility actions as a bonus attempt.
 */
class UiController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "UiController"

        // DPAD direction constants (matching AccessibilityNodeInfo.focusSearch values)
        const val FOCUS_LEFT = 17
        const val FOCUS_UP = 33
        const val FOCUS_RIGHT = 66
        const val FOCUS_DOWN = 130

        // Safety limit to prevent infinite loops/freezes on complex UIs
        private const val MAX_SCAN_NODES = 150
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
            try {
                Log.d(TAG, "Clicking node: [Text: ${node.text}, Desc: ${node.contentDescription}]")
                performClick(node)
            } finally {
                node.recycle()
            }
        } else {
            Log.w(TAG, "Node not found for click: text=$text, desc=$description")
            false
        }
    }

    /**
     * Click whatever is currently focused. Uses DPAD_CENTER key event as the
     * primary method (works on all TV apps), with accessibility ACTION_CLICK
     * as a bonus attempt first.
     */
    fun clickFocused(): Boolean {
        // Bonus: Try accessibility click on the focused node (works sometimes)
        var node = findNodeInAllWindows { it.isFocused }
        if (node == null) {
             node = findNodeInAllWindows { it.isAccessibilityFocused }
        }

        if (node != null) {
            try {
                Log.d(TAG, "Found focused node: ${node.className}. Trying accessibility click first.")
                if (performClick(node)) return true
            } finally {
                node.recycle()
            }
        }

        // Primary: Send real DPAD_CENTER key event (this is what TV apps actually respond to)
        Log.d(TAG, "Sending DPAD_CENTER key event (TV primary method)")
        return sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    
    /**
     * Move DPAD focus in the given direction. Uses real key events as the
     * primary method because focusSearch() doesn't work on other apps' UI trees.
     */
    fun moveFocus(direction: Int): Boolean {
        val keyCode = when (direction) {
            FOCUS_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            FOCUS_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            FOCUS_UP -> KeyEvent.KEYCODE_DPAD_UP
            FOCUS_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> {
                Log.w(TAG, "Unknown focus direction: $direction")
                return false
            }
        }
        Log.d(TAG, "Moving focus via DPAD key event: $keyCode")
        return sendKeyEvent(keyCode)
    }

    fun sendKeyEvent(keyCode: Int): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            Log.d(TAG, "Sent key event: $keyCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key event $keyCode: ${e.message}")
            false
        }
    }
    
    private fun clickScreenCenter(): Boolean {
        val displayMetrics = service.resources.displayMetrics
        val x = displayMetrics.widthPixels / 2f
        val y = displayMetrics.heightPixels / 2f
        return performGestureClick(x, y)
    }

    fun typeText(text: String): Boolean {
        val node = findNodeInAllWindows { it.isFocused && it.isEditable } ?: findNodeInAllWindows { it.isEditable }
        
        if (node != null) {
            try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Type '$text' success: $success")
                return success
            } finally {
                node.recycle()
            }
        } else {
            Log.w(TAG, "No editable field found to type text.")
            false
        }
        return false
    }

    private fun findNodeInAllWindows(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val windows = service.windows
        for (i in windows.indices.reversed()) {
            val root = windows[i].root ?: continue
            val result = findInsideNode(root, predicate)
            if (result != null) {
                if (result != root) {
                    root.recycle()
                }
                return result
            }
            root.recycle()
        }
        
        val activeRoot = service.rootInActiveWindow ?: return null
        val result = findInsideNode(activeRoot, predicate)
        if (result != null) {
            if (result != activeRoot) activeRoot.recycle()
            return result
        }
        activeRoot.recycle()
        return null
    }

    private fun findInsideNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            visited++
            
            // Safety Check
            if (visited > MAX_SCAN_NODES) {
                Log.w(TAG, "Scan limit exceeded ($MAX_SCAN_NODES). Aborting.")
                if (node != root) node.recycle()
                while (!queue.isEmpty()) {
                    val n = queue.poll()
                    if (n != root) n?.recycle()
                }
                return null
            }
            
            if (predicate(node)) {
                // Found match. Recycle remaining queue items.
                while (!queue.isEmpty()) {
                    val n = queue.poll()
                    if (n != root) n?.recycle()
                }
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            
            if (node != root) {
                node.recycle()
            }
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        
        var parent = node.parent
        while (parent != null) {
             try {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                
                val oldParent = parent
                parent = parent.parent
                oldParent.recycle() 
            } catch (e: Exception) {
                return false
            }
        }
        
        // Fallback to gesture
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        return performGestureClick(x, y)
    }
    
    private fun performGestureClick(x: Float, y: Float): Boolean {
        Log.d(TAG, "Performing gesture tap at ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
