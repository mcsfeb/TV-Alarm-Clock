package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

class UiController(private val service: AlarmAccessibilityService) {

    companion object {
        private const val TAG = "UiController"
    }

    fun findAndClick(text: String? = null, descriptionContains: String? = null, packageName: String? = null): Boolean {
        val node = findNode(text = text, descriptionContains = descriptionContains, packageName = packageName)
        if (node != null) {
            Log.d(TAG, "Found node to click: ${node.text} / ${node.contentDescription}")
            return performClick(node)
        }
        Log.w(TAG, "Could not find node to click with text='$text' or description='$descriptionContains'")
        return false
    }

    fun clickFocusedElement(): Boolean {
        val focusedNode = findNode(isFocused = true)
        if (focusedNode != null) {
            return performClick(focusedNode)
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val focusedNode = findNode(isFocused = true, isEditable = true)
        if (focusedNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                Log.d(TAG, "Successfully typed '$text' into focused field.")
            } else {
                Log.e(TAG, "Failed to type text into focused field.")
            }
            return success
        }
        Log.w(TAG, "No editable text field is currently focused.")
        return false
    }

    fun sendKey(keyCode: Int) {
        val success = service.performGlobalAction(keyCode)
        Log.d(TAG, "Sent key code $keyCode, success: $success")
    }

    private fun findNode(
        text: String? = null,
        descriptionContains: String? = null,
        packageName: String? = null,
        isFocused: Boolean? = null,
        isEditable: Boolean? = null
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val matches = (
                (text == null || node.text?.toString().equals(text, ignoreCase = true)) &&
                (descriptionContains == null || node.contentDescription?.toString()?.contains(descriptionContains, ignoreCase = true) == true) &&
                (packageName == null || node.packageName?.equals(packageName) == true) &&
                (isFocused == null || node.isFocused) &&
                (isEditable == null || node.isEditable)
            )

            if (matches) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val result = child?.let { search(it) }
                if (result != null) return result
            }
            return null
        }

        return search(root)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) {
                Log.d(TAG, "performAction(ACTION_CLICK) succeeded.")
                return true
            }
        }

        Log.d(TAG, "ACTION_CLICK failed or node not clickable, trying gesture tap.")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return service.dispatchGesture(gesture, null, null)
    }
}
