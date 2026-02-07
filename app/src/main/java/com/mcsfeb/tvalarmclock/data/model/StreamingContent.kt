package com.mcsfeb.tvalarmclock.data.model

/**
 * StreamingContent - What the alarm should launch on the TV.
 *
 * Three ways to wake up:
 *
 * 1. JUST OPEN THE APP (LaunchMode.APP_ONLY)
 *    Opens the streaming app to its home screen. Good for:
 *    - Live TV apps like Sling TV (resumes your last channel)
 *    - Any app where you just want to pick up where you left off
 *
 * 2. PLAY SPECIFIC CONTENT (LaunchMode.DEEP_LINK)
 *    Opens the streaming app directly to a specific show/episode/channel.
 *    The user fills in a content ID (we explain where to find it).
 *    Good for: "Play Stranger Things S2E1 on Netflix at 7am"
 *
 * Examples:
 * - Sling TV, just open → launchMode=APP_ONLY, contentId=""
 * - YouTube, specific video → launchMode=DEEP_LINK, contentId="dQw4w9WgXcQ"
 * - Netflix, Stranger Things → launchMode=DEEP_LINK, contentId="80057281"
 */
data class StreamingContent(
    val app: StreamingApp,
    val contentId: String,
    val title: String,           // User-friendly name: "Stranger Things S2E1"
    val launchMode: LaunchMode
)

/**
 * LaunchMode - How to launch the streaming app at alarm time.
 */
enum class LaunchMode {
    /** Deep link directly to specific content (video, episode, channel, etc.) */
    DEEP_LINK,

    /** Just open the app (for live TV, resume watching, etc.) */
    APP_ONLY
}
