package com.mcsfeb.tvalarmclock.data.model

/**
 * StreamingContent - What the alarm should launch on the TV.
 *
 * Three ways to wake up:
 *
 * 1. JUST OPEN THE APP (LaunchMode.APP_ONLY)
 *    Opens the streaming app to its home screen.
 *
 * 2. SEARCH FOR A SHOW (LaunchMode.SEARCH)
 *    Opens the streaming app and searches for the show by name.
 *    This is the most reliable way to get to specific content because
 *    we don't need proprietary content IDs.
 *    Example: Open HBO Max → search "Friends" → show appears
 *
 * 3. DEEP LINK TO SPECIFIC CONTENT (LaunchMode.DEEP_LINK)
 *    Opens the streaming app directly to a specific content ID.
 *    Only works if we have the app's internal ID.
 *    Example: Open Netflix → go to title 80057281 (Stranger Things)
 *    
 * Examples:
 * - Sling TV, just open → launchMode=APP_ONLY
 * - HBO Max, Friends S1E1 → launchMode=SEARCH, searchQuery="Friends", seasonNumber=1, episodeNumber=1
 * - Netflix, Stranger Things → launchMode=DEEP_LINK, contentId="80057281"
 * - Sling TV, ESPN → launchMode=DEEP_LINK, contentId="espn"
 */
data class StreamingContent(
    val app: StreamingApp,
    val contentId: String,
    val title: String,           // User-friendly name: "Friends S1E1"
    val launchMode: LaunchMode,
    val searchQuery: String = "", // Show name for SEARCH mode (e.g., "Friends")
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
)

/**
 * LaunchMode - How to launch the streaming app at alarm time.
 */
enum class LaunchMode {
    /** Deep link directly to specific content (video, episode, channel, etc.) */
    DEEP_LINK,

    /** Just open the app (for live TV, resume watching, etc.) */
    APP_ONLY,

    /** Open the app and search for the show by name */
    SEARCH
}
