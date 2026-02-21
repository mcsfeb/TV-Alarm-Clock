package com.mcsfeb.tvalarmclock.data.model

import com.mcsfeb.tvalarmclock.data.config.DeepLinkConfig
import com.mcsfeb.tvalarmclock.data.config.DeepLinkResolver

/**
 * StreamingApp - Every streaming service we support launching.
 */
enum class StreamingApp(
    val displayName: String,
    val packageName: String,
    val altPackageNames: List<String> = emptyList(),
    val deepLinkFormat: String,
    val contentIdLabel: String,
    val description: String,
    val colorHex: Long,
    val requiresSourceExtra: Boolean = false
) {
    NETFLIX(
        displayName = "Netflix",
        packageName = "com.netflix.ninja",
        deepLinkFormat = "nflx://www.netflix.com/watch/{id}",
        contentIdLabel = "Netflix Title ID",
        description = "Use the number from the Netflix URL (e.g., 80057281 for Stranger Things)",
        colorHex = 0xFFE50914,
        requiresSourceExtra = true
    ),

    YOUTUBE(
        displayName = "YouTube",
        packageName = "com.google.android.youtube.tv",
        altPackageNames = listOf("com.google.android.youtube"),
        deepLinkFormat = "https://www.youtube.com/watch?v={id}",
        contentIdLabel = "YouTube Video ID",
        description = "The letters after watch?v= in a YouTube link (e.g., dQw4w9WgXcQ)",
        colorHex = 0xFFFF0000
    ),

    HULU(
        displayName = "Hulu",
        packageName = "com.hulu.livingroomplus",
        altPackageNames = listOf("com.hulu.plus"),
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
        altPackageNames = listOf("com.amazon.avod", "com.amazon.avod.thirdpartyclient"),
        deepLinkFormat = "https://app.primevideo.com/detail?gti={id}",
        contentIdLabel = "Prime Video ASIN",
        description = "The ASIN code from an Amazon Prime Video URL (e.g., B078GY1HJV)",
        colorHex = 0xFF00A8E1
    ),

    HBO_MAX(
        displayName = "Max (HBO)",
        packageName = "com.wbd.stream",
        altPackageNames = listOf("com.hbo.hbonow", "com.hbo.max.android.tv"),
        deepLinkFormat = "https://play.max.com/video/watch/{id}",
        contentIdLabel = "Max Episode/Movie ID",
        description = "The episode or movie ID from a Max/HBO URL",
        colorHex = 0xFF5822B4
    ),

    SLING_TV(
        displayName = "Sling TV",
        packageName = "com.sling",
        deepLinkFormat = "slingtv://watch.sling.com/watch/live?channelName={id}",
        contentIdLabel = "Sling Channel Name",
        description = "Channel name like 'Fox News', 'CNN', 'ESPN' (use the display name)",
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
        deepLinkFormat = "https://www.paramountplus.com/watch/{id}",
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
        altPackageNames = listOf("com.tubitv.ott"),
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
        packageName = "com.google.android.youtube.tvunplugged",
        altPackageNames = listOf("com.google.android.apps.tv.launcherx"),
        deepLinkFormat = "https://tv.youtube.com/watch/{id}",
        contentIdLabel = "YouTube TV Channel",
        description = "Live TV channel or recording ID",
        colorHex = 0xFFFF0000
    ),

    FUBO_TV(
        displayName = "fuboTV",
        packageName = "com.fubo.firetv",
        altPackageNames = listOf("com.fubo.tv", "com.fubo.android.tv"),
        deepLinkFormat = "https://www.fubo.tv/watch/{id}",
        contentIdLabel = "fuboTV Channel ID",
        description = "Channel or content ID for live sports and TV",
        colorHex = 0xFFE3773B
    ),

    DISCOVERY_PLUS(
        displayName = "Discovery+",
        packageName = "com.discovery.discoveryplus.androidtv",
        altPackageNames = listOf("com.wbd.discoveryplus"),
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
        fun allSorted(): List<StreamingApp> = entries.sortedBy { it.displayName }

        fun getDeepLinkFormats(app: StreamingApp): List<String> {
            val result = mutableListOf<String>()
            result += DeepLinkResolver.getVerifiedFormats(app)
            result += DeepLinkConfig.getDeepLinkFormats(app.name)
            result += app.deepLinkFormat
            return result.distinct()
        }

        fun getIntentExtras(app: StreamingApp): Map<String, String> = DeepLinkConfig.getIntentExtras(app.name)
        fun getIntentClassName(app: StreamingApp): String? = DeepLinkConfig.getIntentClassName(app.name)
        fun needsForceStop(app: StreamingApp): Boolean = DeepLinkConfig.needsForceStop(app.name)
        fun getAltPackageNames(app: StreamingApp): List<String> = DeepLinkConfig.getAppConfig(app.name)?.altPackageNames ?: app.altPackageNames
        fun getPackageName(app: StreamingApp): String = DeepLinkConfig.getAppConfig(app.name)?.packageName ?: app.packageName

        /** Given a package name, find all alternatives associated with the same app. */
        fun getAltPackageNamesForPackage(pkg: String): List<String> {
            val app = entries.find { it.packageName == pkg || it.altPackageNames.contains(pkg) }
            return app?.altPackageNames ?: emptyList()
        }
    }
}
