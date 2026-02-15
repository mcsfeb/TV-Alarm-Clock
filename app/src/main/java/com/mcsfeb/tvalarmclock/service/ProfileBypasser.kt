package com.mcsfeb.tvalarmclock.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.ArrayDeque

/**
 * ProfileBypasser - Detects and bypasses profile selection screens.
 * 
 * Heavily optimized to prevent memory leaks by recycling AccessibilityNodeInfo objects.
 */
class ProfileBypasser(
    private val service: AccessibilityService,
    private val uiController: UiController
) {
    private val TAG = "ProfileBypasser"
    private val prefs: SharedPreferences = service.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)

    // Keywords that strongly suggest we are on a profile screen
    private val profileKeywords = listOf(
        "who's watching", "who is watching", "choose your profile", "profiles", 
        "continue watching as", "select profile", "switch profile", 
        "add profile", "edit profile", "manage profiles",
        "kids", "default", "primary", "owner"
    )

    // Keywords that suggest we are NOT on a profile screen (Home screen indicators)
    private val homeKeywords = listOf(
        "home", "movies", "shows", "live tv", "my stuff", "new & popular", "watchlist"
    )

    fun isProfileScreen(packageName: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            // 1. Negative Check: If we see "Home" or "Live TV", we are likely past the profile screen.
            // This prevents false positives on the home screen which might contain "Profile" in the corner.
            if (findNodeWithText(root, homeKeywords, maxDepth = 2) != null) {
                Log.d(TAG, "Home screen keywords detected. Not a profile screen.")
                return false
            }

            // 2. Positive Text Check
            val foundText = findNodeWithText(root, profileKeywords)
            if (foundText != null) {
                Log.d(TAG, "Profile screen text detected: '${foundText.text}'")
                if (foundText != root) foundText.recycle()
                return true
            }

            // 3. App-Specific Structural Check (when text is missing/graphical)
            if (isAppSpecificStructure(packageName, root)) {
                 Log.d(TAG, "App-specific profile structure detected for $packageName")
                 return true
            }
            
            return false
        } finally {
            root.recycle()
        }
    }

    suspend fun selectDefaultProfile(packageName: String): Boolean {
        Log.i(TAG, "Attempting to select default profile for $packageName")

        // === PRIMARY METHOD: DPAD key events (TV apps respond to these) ===
        // Most profile screens pre-focus the first/default profile on load.
        // Double-tap DPAD_CENTER: first press focuses, second press selects.
        Log.d(TAG, "DPAD Phase 1: Double-tap DPAD_CENTER")
        uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        delay(600)
        uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        if (confirmProfileGone(packageName)) return true

        // Navigate to first profile and select
        Log.d(TAG, "DPAD Phase 2: LEFT x3 -> CENTER")
        repeat(3) {
            uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
            delay(250)
        }
        uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        if (confirmProfileGone(packageName)) return true

        // === SECONDARY METHOD: Accessibility actions (bonus, sometimes works) ===
        repeat(3) { attempt ->
            if (attempt > 0) delay(1000)

            val root = service.rootInActiveWindow ?: return@repeat

            try {
                // 1. App Specific Logic (Clicking images/cards)
                if (clickAppSpecificProfile(packageName, root)) {
                    Log.i(TAG, "App specific click succeeded for $packageName")
                    if (confirmProfileGone(packageName)) return true
                }

                // 2. Text Based Logic (Clicking text names)
                if (clickTextBasedProfile(root)) {
                    Log.i(TAG, "Text based click succeeded")
                    if (confirmProfileGone(packageName)) return true
                }

                // 3. Generic Node Logic (Finding center-most large item)
                if (clickGenericProfile(root)) {
                    Log.i(TAG, "Generic node click succeeded")
                    if (confirmProfileGone(packageName)) return true
                }

            } finally {
                root.recycle()
            }
        }

        // === LAST RESORT: Try DOWN then CENTER (profiles below a header) ===
        Log.w(TAG, "All methods failed. Final attempt: DOWN -> CENTER")
        uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
        delay(300)
        uiController.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER)
        return confirmProfileGone(packageName)
    }

    private suspend fun confirmProfileGone(packageName: String): Boolean {
        delay(2000) // Wait for UI transition
        val stillProfile = isProfileScreen(packageName)
        if (!stillProfile) {
            Log.i(TAG, "Confirmed: Profile screen is gone.")
            return true
        }
        Log.w(TAG, "Profile screen still present after click.")
        return false
    }

    // --- Detection Logic ---

    private fun isAppSpecificStructure(packageName: String, root: AccessibilityNodeInfo): Boolean {
        val lowerPkg = packageName.lowercase()
        
        // Paramount+ / CBS: Look for a RecyclerView with large items in the center
        if (lowerPkg.contains("paramount") || lowerPkg.contains("cbs")) {
             val recyclerViews = findAllNodes(root) { 
                it.className?.contains("RecyclerView") == true || 
                it.className?.contains("HorizontalGridView") == true 
            }
            try {
                // If we see a RecyclerView with children, and NO side menu (which would be another view group),
                // it's likely the profile picker. Profile pickers are usually clean.
                // Paramount+ profile screen: One horizontal list, maybe a logo above.
                if (recyclerViews.size == 1 && recyclerViews[0].childCount in 1..8) {
                    return true
                }
            } finally {
                recyclerViews.forEach { if (it != root) it.recycle() }
            }
        }
        
        // Max / HBO: Look for FrameLayouts/ViewGroups that look like avatars
        if (lowerPkg.contains("wbd") || lowerPkg.contains("hbo") || lowerPkg.contains("max")) {
             val potentialProfiles = findAllNodes(root) { 
                it.isVisibleToUser &&
                (it.className?.contains("FrameLayout") == true || it.className?.contains("ViewGroup") == true) &&
                it.isClickable
            }
            try {
                // Filter for "Avatar sized" items
                val avatars = potentialProfiles.filter { 
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.width() > 150 && r.height() > 150 && r.width() < 600
                }
                // If we have 2-6 avatars and they are aligned, it's a profile screen
                if (avatars.size in 2..6) {
                    return true
                }
            } finally {
                potentialProfiles.forEach { if (it != root) it.recycle() }
            }
        }

        return false
    }

    // --- Clicking Logic ---

    private fun clickAppSpecificProfile(packageName: String, root: AccessibilityNodeInfo): Boolean {
        val lowerPkg = packageName.lowercase()
        
        if (lowerPkg.contains("paramount") || lowerPkg.contains("cbs")) {
            val recyclerViews = findAllNodes(root) { 
                it.className?.contains("RecyclerView") == true || 
                it.className?.contains("HorizontalGridView") == true 
            }
            
            try {
                for (rv in recyclerViews) {
                    if (rv.childCount > 0) {
                        val firstChild = rv.getChild(0)
                        if (firstChild != null) {
                            // Look for clickable image inside the first item (Profile Image)
                            val clickTarget = findNode(firstChild) { 
                                it.isClickable && (it.className == "android.widget.ImageView" || it.className?.contains("Image") == true)
                            } ?: firstChild 

                            val success = performClick(clickTarget)
                            
                            if (clickTarget != firstChild) clickTarget.recycle()
                            firstChild.recycle()

                            if (success) return true
                        }
                    }
                }
            } finally {
                recyclerViews.forEach { if (it != root) it.recycle() }
            }
        }
        
        if (lowerPkg.contains("wbd") || lowerPkg.contains("hbo") || lowerPkg.contains("max")) {
            val profiles = findAllNodes(root) { 
                it.isClickable && it.isVisibleToUser &&
                (it.className?.contains("FrameLayout") == true || it.className?.contains("ViewGroup") == true) &&
                it.childCount > 0 
            }
            
            try {
                val validProfiles = profiles.filter { 
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.width() > 150 && r.height() > 150 // Strict size check for Max
                }

                // Sort by left-to-right (typically profiles are horizontal)
                val sorted = validProfiles.sortedBy { 
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.left
                }

                if (sorted.isNotEmpty()) {
                    Log.d(TAG, "Found ${sorted.size} Max profiles. Clicking first.")
                    return performClick(sorted[0])
                }
            } finally {
                profiles.forEach { if (it != root) it.recycle() }
            }
        }

        return false
    }

    private fun clickTextBasedProfile(root: AccessibilityNodeInfo): Boolean {
        val primary = findNodeWithText(root, listOf("default", "primary", "owner", "kids"))
        if (primary != null) {
             val success = performClick(primary)
             if (primary != root) primary.recycle()
             if (success) return true
        }
        return false
    }

    private fun clickGenericProfile(root: AccessibilityNodeInfo): Boolean {
         val candidates = findAllNodes(root) { 
            it.isClickable && it.isVisibleToUser &&
            (it.className == "android.widget.ImageView" || it.className?.contains("Card") == true)
        }
        
        try {
            val validCandidates = candidates.filter {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.width() > 100 && r.height() > 100 && 
                (it.text == null || !it.text.toString().contains("add", true))
            }

            // Find center-most item
            val displayCenterX = service.resources.displayMetrics.widthPixels / 2
            val displayCenterY = service.resources.displayMetrics.heightPixels / 2
            
            val best = validCandidates.minByOrNull { 
                val r = Rect()
                it.getBoundsInScreen(r)
                val cx = r.centerX()
                val cy = r.centerY()
                // Distance to center
                Math.hypot((cx - displayCenterX).toDouble(), (cy - displayCenterY).toDouble())
            }
            
            if (best != null) {
                return performClick(best)
            }
        } finally {
             candidates.forEach { if (it != root) it.recycle() }
        }
        return false
    }

    // --- Helper Methods ---

    private fun findNodeWithText(root: AccessibilityNodeInfo, keywords: List<String>, maxDepth: Int = -1): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val depthQueue = ArrayDeque<Int>()
        queue.add(root)
        depthQueue.add(0)
        
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            val depth = depthQueue.poll() ?: 0
            
            if (maxDepth != -1 && depth > maxDepth) {
                if (node != root) node.recycle()
                continue
            }

            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            
            if (keywords.any { text.contains(it) || desc.contains(it) }) {
                // Cleanup
                while (!queue.isEmpty()) {
                    val n = queue.poll()
                    if (n != root && n != node) n?.recycle()
                }
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { 
                    queue.add(it)
                    depthQueue.add(depth + 1)
                }
            }
            
            if (node != root) node.recycle()
        }
        return null
    }
    
    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            
            if (predicate(node)) {
                while (!queue.isEmpty()) {
                    val n = queue.poll()
                    if (n != root && n != node) n?.recycle()
                }
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            
            if (node != root) node.recycle()
        }
        return null
    }

    private fun findAllNodes(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            
            if (predicate(node)) {
                result.add(node)
            } else {
                if (node != root) node.recycle()
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent
        if (parent != null) {
            try {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } finally {
                parent.recycle()
            }
        }
        return false
    }
}
