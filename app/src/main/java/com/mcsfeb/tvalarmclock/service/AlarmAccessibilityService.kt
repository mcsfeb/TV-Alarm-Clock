package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AlarmAccessibilityService - Enables auto-clicking past profile screens.
 *
 * WHY WE NEED THIS:
 * When the alarm opens a streaming app (Netflix, HBO Max, etc.), many apps
 * show a "Who's Watching?" profile picker. The user is asleep, so nobody
 * can click the profile. The show never starts.
 *
 * Regular Android apps can't click buttons in OTHER apps — that's a
 * security restriction. But AccessibilityServices CAN, because they're
 * designed to help users interact with their device.
 *
 * WHAT IT DOES:
 * - Stays dormant most of the time (doesn't watch or log anything)
 * - When ProfileAutoSelector asks, it finds the currently focused item
 *   on screen and clicks it (to select the profile)
 * - That's it — no data collection, no screen reading, no logging
 *
 * HOW IT CLICKS:
 * On Android TV, the D-pad always has one item "focused" (highlighted).
 * When a streaming app opens to the profile picker, the first profile
 * is already focused/highlighted. We just need to "click" it. We do this by:
 * 1. Finding the focused node in the accessibility tree
 * 2. Performing ACTION_CLICK on it
 * 3. If no focused node, find any clickable item and click it
 *
 * SETUP:
 * The user needs to enable this service once in:
 * Settings > Accessibility > TV Alarm Clock
 * After that, profile auto-click works automatically.
 */
class AlarmAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process any events — this service is purely on-demand
    }

    override fun onInterrupt() {
        // Nothing to clean up
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Click whatever is currently focused on screen.
     *
     * On Android TV, the D-pad remote always keeps one item focused
     * (highlighted with a border/glow). When a streaming app opens to
     * its profile picker, the first profile is usually already focused.
     * Calling this method clicks it — same as pressing the center button
     * on the TV remote.
     *
     * Strategy (in order):
     * 1. Find the focused node and perform ACTION_CLICK on it
     * 2. Walk up the tree to find a clickable parent
     * 3. Find any clickable visible item and click it
     */
    fun clickFocusedItem() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Strategy 1: Find the focused node and click it
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

            if (focusedNode != null) {
                if (focusedNode.isClickable) {
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    focusedNode.recycle()
                    rootNode.recycle()
                    return
                }

                // Strategy 2: Walk up to find a clickable parent
                var parent = focusedNode.parent
                var depth = 0
                while (parent != null && depth < 5) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        focusedNode.recycle()
                        rootNode.recycle()
                        return
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                    depth++
                }
                parent?.recycle()
                focusedNode.recycle()
            }

            // Strategy 3: Find any clickable/focusable item and click it
            val firstClickable = findFirstClickable(rootNode)
            if (firstClickable != null) {
                firstClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                firstClickable.recycle()
            }

            rootNode.recycle()
        } catch (e: Exception) {
            // Never crash — the alarm still works, user just picks profile manually
        }
    }

    /**
     * Called by ProfileAutoSelector — just clicks the focused item.
     * The keyCode parameter is kept for API compatibility but we always
     * use the accessibility tree approach (no shell commands, no key injection).
     */
    fun sendKey(@Suppress("UNUSED_PARAMETER") keyCode: Int) {
        clickFocusedItem()
    }

    /**
     * Find the first clickable node in the accessibility tree.
     * Used as a fallback when we can't find a focused node.
     */
    private fun findFirstClickable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isClickable && root.isVisibleToUser) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFirstClickable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    companion object {
        var instance: AlarmAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
