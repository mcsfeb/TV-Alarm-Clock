package com.mcsfeb.tvalarmclock.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * AppNavigationGuide - Tested DPAD sequences and deep link strategies for each streaming app.
 *
 * TESTED ON: Onn Google TV (192.168.1.90:5555) - February 2026
 *
 * KEY FINDING: Most Android TV streaming apps (HBO Max, Hulu, Sling) are OPAQUE to
 * accessibility/uiautomator — their UI elements don't expose text or content descriptions.
 * This means adaptive DPAD navigation is unreliable.
 *
 * STRATEGY: Always try deep links first. Use DPAD only for profile bypass and simple fallbacks.
 */
object AppNavigationGuide {

    private const val TAG = "AppNavGuide"

    // =====================================================================
    //  APP PACKAGE NAMES + ACTIVITIES (verified on Onn Google TV)
    // =====================================================================
    object Packages {
        const val SLING         = "com.sling"
        const val DISNEY_PLUS   = "com.disney.disneyplus"
        const val HBO_MAX       = "com.wbd.stream"
        const val HULU          = "com.hulu.livingroomplus"
        const val PARAMOUNT     = "com.cbs.ott"
        const val NETFLIX       = "com.netflix.ninja"
        const val YOUTUBE       = "com.google.android.youtube.tv"
        const val PRIME_VIDEO   = "com.amazon.amazonvideo.livingroom"
        const val TUBI          = "com.tubitv"
    }

    /**
     * Verified main activity names for each app (tested Feb 2026).
     * Using wrong activity names causes "Activity does not exist" errors.
     */
    object Activities {
        const val SLING         = "com.sling/.MainActivity"
        const val DISNEY_PLUS   = "com.disney.disneyplus/com.bamtechmedia.dominguez.main.MainActivity"
        const val HBO_MAX       = "com.wbd.stream/com.wbd.beam.BeamActivity"
        const val HULU          = "com.hulu.livingroomplus/.WKFactivity"
        const val PARAMOUNT     = "com.cbs.ott/com.paramount.android.pplus.features.splash.tv.SplashMediatorActivity"
        const val NETFLIX       = "com.netflix.ninja/.MainActivity"
        const val YOUTUBE       = "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"
    }

    // =====================================================================
    //  PROFILE SCREEN DETECTION
    // =====================================================================
    /**
     * Keywords that indicate a "Who's Watching?" / profile selection screen.
     * Tested on real device.
     */
    val profileKeywords = listOf(
        "who's watching", "who is watching", "watching",
        "choose profile", "select profile", "switch profile",
        "my profile", "add profile", "kids",
        "enter your profile pin", "profile pin"
    )

    /**
     * Resource IDs for profile screens (where available).
     */
    object ProfileResIds {
        // Sling profile screen
        const val SLING_PROFILE_SCREEN = "SwitchUserProfileScreen"
        const val SLING_PROFILE_ICON   = "SwitchUserProfilesIcons-0-userIcon-icon-userIcon-icon"
        const val SLING_DONT_ASK       = "SwitchUserProfileScreen-confirmation-button"

        // Disney+ PIN screen
        const val DISNEY_PIN_PROMPT    = "com.disney.disneyplus:id/enterPinPromptText"
        const val DISNEY_PIN_DIGIT     = "com.disney.disneyplus:id/digitKey"
        const val DISNEY_PIN_CANCEL    = "com.disney.disneyplus:id/cancelKey"
        const val DISNEY_PIN_NAME      = "com.disney.disneyplus:id/enterPinNameText"
    }

    // =====================================================================
    //  PROFILE BYPASS STRATEGIES
    // =====================================================================
    data class ProfileBypass(
        val type: String,        // "simple_click", "pin_entry", "left_click", "none"
        val dpadSequence: List<Int>?,  // KeyEvent codes to send
        val pin: String?,        // For PIN-protected profiles (user must configure)
        val notes: String
    )

    val profileBypassStrategies = mapOf(
        Packages.SLING to ProfileBypass(
            type = "simple_click",
            dpadSequence = listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER  // Click first profile
            ),
            pin = null,
            notes = "Sling shows 'Who's Watching?' with profiles. First profile is auto-focused. " +
                    "Single CENTER click selects it. Has 'Don't Ask Again' button at bottom."
        ),
        Packages.DISNEY_PLUS to ProfileBypass(
            type = "pin_entry",
            dpadSequence = null, // PIN must be entered via digit keys
            pin = null, // User must configure this in alarm settings
            notes = "Disney+ requires a 4-digit PIN for the profile. PIN pad is a 3x3+1 grid. " +
                    "Keys use resource-id 'com.disney.disneyplus:id/digitKey'. " +
                    "Focus starts on digit '1'. Navigate with DPAD to reach each digit."
        ),
        Packages.HBO_MAX to ProfileBypass(
            type = "simple_click",
            dpadSequence = listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER  // Select first profile
            ),
            pin = null,
            notes = "HBO Max UI is WebView-based and completely opaque to accessibility. " +
                    "Profile screen (if shown) needs a simple CENTER click. " +
                    "The entire window reports as a single [0,0][1920,1080] element."
        ),
        Packages.HULU to ProfileBypass(
            type = "simple_click",
            dpadSequence = listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER
            ),
            pin = null,
            notes = "Hulu TV is completely opaque to uiautomator — zero text/desc/focus info. " +
                    "Best strategy: click CENTER for profile, then rely on deep links."
        ),
        Packages.PARAMOUNT to ProfileBypass(
            type = "simple_click",
            dpadSequence = listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER
            ),
            pin = null,
            notes = "Paramount+ may show profile selection. Simple CENTER to select first. " +
                    "App sometimes exits to launcher — may need retry logic."
        ),
        Packages.NETFLIX to ProfileBypass(
            type = "simple_click",
            dpadSequence = listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER
            ),
            pin = null,
            notes = "Netflix shows profile picker. First profile is typically selected. " +
                    "CENTER click confirms. Deep link with source=30 bypasses to content."
        )
    )

    // =====================================================================
    //  DEEP LINK STRATEGIES (primary method - most reliable)
    // =====================================================================

    /**
     * Create a deep link intent for the given app and content.
     *
     * @param packageName The streaming app package
     * @param contentId The content identifier (varies by app)
     * @param contentType "movie", "episode", "show", "live", "channel"
     * @param extras Additional parameters needed by specific apps
     */
    fun createDeepLinkIntent(
        packageName: String,
        contentId: String,
        contentType: String = "video",
        extras: Map<String, String> = emptyMap()
    ): Intent? {
        return when (packageName) {
            Packages.NETFLIX -> {
                // Netflix: http://www.netflix.com/watch/{titleId} + source=30
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("http://www.netflix.com/watch/$contentId")
                    `package` = Packages.NETFLIX
                    putExtra("source", "30")  // REQUIRED or lands on home screen
                }
            }

            Packages.DISNEY_PLUS -> {
                // Disney+: disneyplus://playback/{contentId} OR https://www.disneyplus.com/video/{contentId}
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.disneyplus.com/video/$contentId")
                    `package` = Packages.DISNEY_PLUS
                }
            }

            Packages.HBO_MAX -> {
                // HBO Max: https://play.max.com/video/watch/{contentId}
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.max.com/video/watch/$contentId")
                    `package` = Packages.HBO_MAX
                }
            }

            Packages.HULU -> {
                // Hulu: https://www.hulu.com/watch/{episodeId}
                // Also supports: hulu://watch/{episodeId}
                // AND supports android.intent.action.SEARCH!
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.hulu.com/watch/$contentId")
                    `package` = Packages.HULU
                }
            }

            Packages.PARAMOUNT -> {
                // Paramount+: https://www.paramountplus.com/video/{contentId}
                // Also: pplus://video/{contentId}
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.paramountplus.com/video/$contentId")
                    `package` = Packages.PARAMOUNT
                }
            }

            Packages.SLING -> {
                // Sling: slingtv://channel/{channelId} or slingtv://deeplink?{params}
                // Sling is primarily live TV — deep links target channels, not specific VOD
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("slingtv://deeplink?type=${contentType}&id=$contentId")
                    `package` = Packages.SLING
                }
            }

            Packages.YOUTUBE -> {
                // YouTube: vnd.youtube:{videoId}
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("vnd.youtube:$contentId")
                    `package` = Packages.YOUTUBE
                }
            }

            Packages.PRIME_VIDEO -> {
                // Prime Video: https://app.primevideo.com/detail?gti={contentId}
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://app.primevideo.com/detail?gti=$contentId")
                    `package` = Packages.PRIME_VIDEO
                }
            }

            else -> {
                Log.w(TAG, "No deep link template for package: $packageName")
                null
            }
        }
    }

    /**
     * Create a search intent for Hulu (the only app that supports android.intent.action.SEARCH).
     * Tested: launches com.hulu.livingroomplus/.WKFactivity with search results.
     */
    fun createHuluSearchIntent(query: String): Intent {
        return Intent(Intent.ACTION_SEARCH).apply {
            component = ComponentName.unflattenFromString(Activities.HULU)
            putExtra("query", query)
        }
    }

    /**
     * Create a Hulu PLAY_CONTENT intent (Hulu-specific action).
     * Action: hulu.intent.action.PLAY_CONTENT
     */
    fun createHuluPlayIntent(contentId: String): Intent {
        return Intent("hulu.intent.action.PLAY_CONTENT").apply {
            component = ComponentName.unflattenFromString(Activities.HULU)
            data = Uri.parse("https://www.hulu.com/watch/$contentId")
        }
    }

    /**
     * Create a simple launch intent for an app (fallback when no deep link is available).
     */
    fun createLaunchIntent(context: Context, packageName: String): Intent? {
        return context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: context.packageManager.getLaunchIntentForPackage(packageName)
    }

    // =====================================================================
    //  SLING TV SPECIFIC: Channel-based navigation
    // =====================================================================
    /**
     * Sling is primarily a LIVE TV service. Content is organized by channels.
     * The home screen has:
     *   - Left sidebar (bounds x=0-96): Navigation rail (hidden/collapsed)
     *   - home-screen-ribbon: Main content area (SPOTLIGHT, Recommended, Sports)
     *   - miniplayer-container: PIP at bottom-right when browsing
     *
     * Navigation structure (React Native - opaque to accessibility):
     *   - Content tiles start at x=182
     *   - Profiles: SwitchUserProfilesIcons-{0,1,2,3}
     *   - Home ribbons: home-ribbon-{0,1,2}
     *   - Sports tabs: NCAAM, NCAAW, NBA, EPL, MLB
     */

    // =====================================================================
    //  DISNEY+ SPECIFIC: PIN bypass
    // =====================================================================
    /**
     * Disney+ PIN pad layout (tested on device):
     *
     * The PIN pad is a 3-column grid centered on screen:
     *   Row 1: [1] [2] [3]   bounds y=575-665
     *   Row 2: [4] [5] [6]   bounds y=667-757
     *   Row 3: [7] [8] [9]   bounds y=759-849
     *   Row 4: [CANCEL] [0] [DELETE]  bounds y=851-941
     *
     * Column positions:
     *   Left:   x=649-855   (digits 1,4,7,CANCEL)
     *   Center: x=857-1063  (digits 2,5,8,0)
     *   Right:  x=1065-1271 (digits 3,6,9,DELETE)
     *
     * Focus starts on digit "1" (top-left).
     * Navigate with DPAD_RIGHT/DOWN to reach desired digit, press CENTER.
     */
    data class PinDigitPosition(val row: Int, val col: Int)

    private val pinDigitPositions = mapOf(
        '1' to PinDigitPosition(0, 0),
        '2' to PinDigitPosition(0, 1),
        '3' to PinDigitPosition(0, 2),
        '4' to PinDigitPosition(1, 0),
        '5' to PinDigitPosition(1, 1),
        '6' to PinDigitPosition(1, 2),
        '7' to PinDigitPosition(2, 0),
        '8' to PinDigitPosition(2, 1),
        '9' to PinDigitPosition(2, 2),
        '0' to PinDigitPosition(3, 1)
    )

    /**
     * Generate the DPAD sequence to enter a Disney+ PIN.
     * Returns a list of KeyEvent codes.
     *
     * Strategy: For each digit, navigate from current position to target position
     * using DPAD directions, then press CENTER.
     */
    fun generateDisneyPinSequence(pin: String): List<Int> {
        if (pin.length != 4) {
            Log.e(TAG, "Disney+ PIN must be exactly 4 digits")
            return emptyList()
        }

        val sequence = mutableListOf<Int>()
        var currentRow = 0
        var currentCol = 0  // Start at digit "1" (row=0, col=0)

        for (digit in pin) {
            val target = pinDigitPositions[digit] ?: continue

            // Move vertically
            val rowDiff = target.row - currentRow
            if (rowDiff > 0) {
                repeat(rowDiff) { sequence.add(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
            } else if (rowDiff < 0) {
                repeat(-rowDiff) { sequence.add(android.view.KeyEvent.KEYCODE_DPAD_UP) }
            }

            // Move horizontally
            val colDiff = target.col - currentCol
            if (colDiff > 0) {
                repeat(colDiff) { sequence.add(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
            } else if (colDiff < 0) {
                repeat(-colDiff) { sequence.add(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
            }

            // Press CENTER to enter the digit
            sequence.add(android.view.KeyEvent.KEYCODE_DPAD_CENTER)

            currentRow = target.row
            currentCol = target.col
        }

        return sequence
    }

    // =====================================================================
    //  UI OPACITY CLASSIFICATION
    // =====================================================================
    enum class UiTransparency {
        TRANSPARENT,  // App exposes text, desc, and resource IDs (Netflix, Disney+)
        PARTIAL,      // App exposes some IDs but focus text is empty (Sling)
        OPAQUE        // App is invisible to accessibility (HBO Max, Hulu)
    }

    val appTransparency = mapOf(
        Packages.SLING       to UiTransparency.PARTIAL,
        Packages.DISNEY_PLUS to UiTransparency.TRANSPARENT,
        Packages.HBO_MAX     to UiTransparency.OPAQUE,
        Packages.HULU        to UiTransparency.OPAQUE,
        Packages.PARAMOUNT   to UiTransparency.PARTIAL,
        Packages.NETFLIX     to UiTransparency.TRANSPARENT,
        Packages.YOUTUBE     to UiTransparency.TRANSPARENT,
        Packages.PRIME_VIDEO to UiTransparency.PARTIAL
    )
}
