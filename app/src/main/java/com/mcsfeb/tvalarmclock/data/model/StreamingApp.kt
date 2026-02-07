package com.mcsfeb.tvalarmclock.data.model

/**
 * StreamingApp - Every streaming service we support launching.
 *
 * Each entry has:
 * - displayName: What the user sees (e.g., "Netflix")
 * - packageName: The Android TV app's unique ID (used to launch it)
 * - deepLinkFormat: The URL pattern for opening specific content
 *   Use {id} as a placeholder for the content ID
 * - requiresExtras: Any special intent extras needed (e.g., Netflix needs source=30)
 * - color: Brand color for the UI card
 * - description: Short note about what kind of content IDs to use
 *
 * IMPORTANT: These deep links are unofficial. Streaming apps can change them
 * at any time with an update. Always wrap launches in try-catch!
 */
enum class StreamingApp(
    val displayName: String,
    val packageName: String,
    val deepLinkFormat: String,
    val contentIdLabel: String,
    val description: String,
    val colorHex: Long,
    val requiresSourceExtra: Boolean = false
) {
    NETFLIX(
        displayName = "Netflix",
        packageName = "com.netflix.ninja",
        deepLinkFormat = "http://www.netflix.com/watch/{id}",
        contentIdLabel = "Netflix Title ID",
        description = "Use the number from the Netflix URL (e.g., 80057281 for Stranger Things)",
        colorHex = 0xFFE50914,
        requiresSourceExtra = true  // Netflix requires putExtra("source", "30")
    ),

    YOUTUBE(
        displayName = "YouTube",
        packageName = "com.google.android.youtube",
        deepLinkFormat = "vnd.youtube:{id}",
        contentIdLabel = "YouTube Video ID",
        description = "The letters after watch?v= in a YouTube link (e.g., dQw4w9WgXcQ)",
        colorHex = 0xFFFF0000
    ),

    HULU(
        displayName = "Hulu",
        packageName = "com.hulu.livingroomplus",
        deepLinkFormat = "https://www.hulu.com/watch/{id}",
        contentIdLabel = "Hulu Episode ID",
        description = "The UUID from a Hulu episode URL",
        colorHex = 0xFF1CE783
    ),

    DISNEY_PLUS(
        displayName = "Disney+",
        packageName = "com.disney.disneyplus",
        deepLinkFormat = "https://www.disneyplus.com/video/{id}",
        contentIdLabel = "Disney+ Content ID",
        description = "The content ID from a Disney+ URL",
        colorHex = 0xFF113CCF
    ),

    PRIME_VIDEO(
        displayName = "Prime Video",
        packageName = "com.amazon.amazonvideo.livingroom",
        deepLinkFormat = "https://app.primevideo.com/detail?gti={id}",
        contentIdLabel = "Prime Video ASIN",
        description = "The ASIN code from an Amazon Prime Video URL",
        colorHex = 0xFF00A8E1
    ),

    HBO_MAX(
        displayName = "Max (HBO)",
        packageName = "com.hbo.hbonow",
        deepLinkFormat = "https://play.max.com/episode/{id}",
        contentIdLabel = "Max Episode ID",
        description = "The episode ID from a Max/HBO URL",
        colorHex = 0xFF5822B4
    ),

    SLING_TV(
        displayName = "Sling TV",
        packageName = "com.sling",
        deepLinkFormat = "https://watch.sling.com/watch/channel/{id}",
        contentIdLabel = "Sling Channel ID",
        description = "Channel ID or just launch the app to your last channel",
        colorHex = 0xFF2563EB
    ),

    PEACOCK(
        displayName = "Peacock",
        packageName = "com.peacocktv.peacockandroid",
        deepLinkFormat = "https://www.peacocktv.com/watch/asset/{id}",
        contentIdLabel = "Peacock Asset ID",
        description = "The asset ID from a Peacock URL",
        colorHex = 0xFF000000
    ),

    PARAMOUNT_PLUS(
        displayName = "Paramount+",
        packageName = "com.cbs.ott",
        deepLinkFormat = "https://www.paramountplus.com/shows/video/{id}",
        contentIdLabel = "Paramount+ Video ID",
        description = "The video ID from a Paramount+ URL",
        colorHex = 0xFF0064FF
    ),

    APPLE_TV(
        displayName = "Apple TV+",
        packageName = "com.apple.atve.androidtv.appletv",
        deepLinkFormat = "https://tv.apple.com/show/{id}",
        contentIdLabel = "Apple TV+ Show ID",
        description = "The show path from an Apple TV+ URL",
        colorHex = 0xFF555555
    ),

    TUBI(
        displayName = "Tubi",
        packageName = "com.tubitv",
        deepLinkFormat = "https://tubitv.com/movies/{id}",
        contentIdLabel = "Tubi Content ID",
        description = "The number from a Tubi URL (free streaming!)",
        colorHex = 0xFFFA382F
    ),

    PLUTO_TV(
        displayName = "Pluto TV",
        packageName = "tv.pluto.android",
        deepLinkFormat = "https://pluto.tv/live-tv/{id}",
        contentIdLabel = "Pluto TV Channel ID",
        description = "The channel slug from a Pluto TV URL (free live TV!)",
        colorHex = 0xFF2B2B2B
    ),

    CRUNCHYROLL(
        displayName = "Crunchyroll",
        packageName = "com.crunchyroll.crunchyroid",
        deepLinkFormat = "https://www.crunchyroll.com/watch/{id}",
        contentIdLabel = "Crunchyroll Episode ID",
        description = "The episode path from a Crunchyroll URL (anime streaming)",
        colorHex = 0xFFF47521
    ),

    YOUTUBE_TV(
        displayName = "YouTube TV",
        packageName = "com.google.android.apps.tv.launcherx",
        deepLinkFormat = "https://tv.youtube.com/watch/{id}",
        contentIdLabel = "YouTube TV Channel",
        description = "Live TV channel or recording ID",
        colorHex = 0xFFFF0000
    ),

    FUBO_TV(
        displayName = "fuboTV",
        packageName = "com.fubo.firetv",
        deepLinkFormat = "https://www.fubo.tv/watch/{id}",
        contentIdLabel = "fuboTV Channel ID",
        description = "Channel or content ID for live sports and TV",
        colorHex = 0xFFE3773B
    ),

    DISCOVERY_PLUS(
        displayName = "Discovery+",
        packageName = "com.discovery.discoveryplus.androidtv",
        deepLinkFormat = "https://www.discoveryplus.com/video/{id}",
        contentIdLabel = "Discovery+ Video ID",
        description = "The video ID from a Discovery+ URL",
        colorHex = 0xFF0033CC
    ),

    PLEX(
        displayName = "Plex",
        packageName = "com.plexapp.android",
        deepLinkFormat = "plex://play/?metadataKey={id}",
        contentIdLabel = "Plex Metadata Key",
        description = "The metadata key for your Plex media (e.g., /library/metadata/12345)",
        colorHex = 0xFFE5A00D
    ),

    STARZ(
        displayName = "Starz",
        packageName = "com.bydeluxe.d3.android.program.starz",
        deepLinkFormat = "https://www.starz.com/play/{id}",
        contentIdLabel = "Starz Content ID",
        description = "The content ID from a Starz URL",
        colorHex = 0xFF000000
    );

    companion object {
        /** Get all apps sorted alphabetically by display name */
        fun allSorted(): List<StreamingApp> = entries.sortedBy { it.displayName }

        /** Build the deep link URL by replacing {id} with actual content ID */
        fun buildDeepLink(app: StreamingApp, contentId: String): String {
            return app.deepLinkFormat.replace("{id}", contentId)
        }
    }
}
