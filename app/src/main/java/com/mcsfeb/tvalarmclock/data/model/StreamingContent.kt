package com.mcsfeb.tvalarmclock.data.model

/**
 * StreamingContent - A specific piece of content the user wants to wake up to.
 *
 * This stores everything needed to launch a specific show/episode/video
 * at alarm time without any user interaction.
 *
 * Examples:
 * - Netflix, Stranger Things S2E1 → contentId="80057281"
 * - YouTube, a specific video → contentId="dQw4w9WgXcQ"
 * - Sling TV, just open the app → contentId="" (app-only mode)
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
    /** Deep link directly to specific content (video, episode, etc.) */
    DEEP_LINK,

    /** Just open the app (for live TV services like Sling, or "resume watching") */
    APP_ONLY
}
