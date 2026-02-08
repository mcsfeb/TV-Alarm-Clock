package com.mcsfeb.tvalarmclock.data.model

/**
 * LiveChannel - A TV channel you can tune to on a live TV streaming service.
 *
 * These are pre-built so the user can just scroll through and pick "ESPN"
 * instead of having to know some weird channel ID.
 *
 * Different apps use different IDs for the same channel, so each channel
 * stores a map of app -> ID. For example, ESPN might be channel ID "espn"
 * on Sling but a different ID on YouTube TV.
 */
data class LiveChannel(
    val name: String,
    val category: ChannelCategory,
    val channelIds: Map<StreamingApp, String>  // Which apps carry this channel + their IDs
)

/**
 * ChannelCategory - Groups channels so the user can browse by type.
 */
enum class ChannelCategory(val displayName: String) {
    NEWS("News"),
    SPORTS("Sports"),
    ENTERTAINMENT("Entertainment"),
    KIDS("Kids & Family"),
    MOVIES("Movies"),
    LIFESTYLE("Lifestyle & Reality"),
    MUSIC("Music"),
    SPANISH("Spanish Language"),
    OTHER("Other")
}

/**
 * ChannelGuide - Pre-built database of popular live TV channels.
 *
 * The channel IDs here are best-effort. Live TV apps don't publish official
 * channel IDs, so these come from community research. If a channel doesn't
 * work, the app falls back to just opening the streaming app's home screen.
 *
 * When a user picks a channel, we look up the ID for their chosen app
 * and deep-link directly to that channel.
 */
object ChannelGuide {

    val channels: List<LiveChannel> = listOf(
        // ===== NEWS =====
        LiveChannel(
            name = "CNN",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "cnn",
                StreamingApp.YOUTUBE_TV to "cnn",
                StreamingApp.FUBO_TV to "cnn",
                StreamingApp.PLUTO_TV to "cnn"
            )
        ),
        LiveChannel(
            name = "Fox News",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "foxnews",
                StreamingApp.YOUTUBE_TV to "fox-news",
                StreamingApp.FUBO_TV to "fox-news"
            )
        ),
        LiveChannel(
            name = "MSNBC",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "msnbc",
                StreamingApp.YOUTUBE_TV to "msnbc",
                StreamingApp.FUBO_TV to "msnbc"
            )
        ),
        LiveChannel(
            name = "BBC News",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "bbc-world-news",
                StreamingApp.PLUTO_TV to "bbc-news"
            )
        ),
        LiveChannel(
            name = "CNBC",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "cnbc",
                StreamingApp.YOUTUBE_TV to "cnbc",
                StreamingApp.FUBO_TV to "cnbc"
            )
        ),
        LiveChannel(
            name = "HLN",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "hln",
                StreamingApp.YOUTUBE_TV to "hln"
            )
        ),
        LiveChannel(
            name = "NewsNation",
            category = ChannelCategory.NEWS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "newsnation",
                StreamingApp.FUBO_TV to "newsnation"
            )
        ),

        // ===== SPORTS =====
        LiveChannel(
            name = "ESPN",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "espn",
                StreamingApp.YOUTUBE_TV to "espn",
                StreamingApp.FUBO_TV to "espn"
            )
        ),
        LiveChannel(
            name = "ESPN2",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "espn2",
                StreamingApp.YOUTUBE_TV to "espn2",
                StreamingApp.FUBO_TV to "espn2"
            )
        ),
        LiveChannel(
            name = "NFL Network",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "nfl-network",
                StreamingApp.YOUTUBE_TV to "nfl-network",
                StreamingApp.FUBO_TV to "nfl-network"
            )
        ),
        LiveChannel(
            name = "FS1 (Fox Sports 1)",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "fs1",
                StreamingApp.YOUTUBE_TV to "fox-sports-1",
                StreamingApp.FUBO_TV to "fs1"
            )
        ),
        LiveChannel(
            name = "TNT Sports",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "tnt",
                StreamingApp.YOUTUBE_TV to "tnt"
            )
        ),
        LiveChannel(
            name = "NBA TV",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "nba-tv",
                StreamingApp.YOUTUBE_TV to "nba-tv"
            )
        ),
        LiveChannel(
            name = "MLB Network",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "mlb-network",
                StreamingApp.YOUTUBE_TV to "mlb-network",
                StreamingApp.FUBO_TV to "mlb-network"
            )
        ),
        LiveChannel(
            name = "Golf Channel",
            category = ChannelCategory.SPORTS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "golf-channel",
                StreamingApp.YOUTUBE_TV to "golf-channel",
                StreamingApp.FUBO_TV to "golf-channel"
            )
        ),

        // ===== ENTERTAINMENT =====
        LiveChannel(
            name = "AMC",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "amc",
                StreamingApp.YOUTUBE_TV to "amc",
                StreamingApp.FUBO_TV to "amc"
            )
        ),
        LiveChannel(
            name = "TBS",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "tbs",
                StreamingApp.YOUTUBE_TV to "tbs"
            )
        ),
        LiveChannel(
            name = "USA Network",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "usa",
                StreamingApp.YOUTUBE_TV to "usa-network",
                StreamingApp.FUBO_TV to "usa-network"
            )
        ),
        LiveChannel(
            name = "FX",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "fx",
                StreamingApp.YOUTUBE_TV to "fx",
                StreamingApp.FUBO_TV to "fx"
            )
        ),
        LiveChannel(
            name = "Comedy Central",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "comedy-central",
                StreamingApp.YOUTUBE_TV to "comedy-central",
                StreamingApp.PLUTO_TV to "comedy-central"
            )
        ),
        LiveChannel(
            name = "Bravo",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "bravo",
                StreamingApp.YOUTUBE_TV to "bravo",
                StreamingApp.FUBO_TV to "bravo"
            )
        ),
        LiveChannel(
            name = "E!",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "e-entertainment",
                StreamingApp.YOUTUBE_TV to "e",
                StreamingApp.FUBO_TV to "e"
            )
        ),
        LiveChannel(
            name = "Syfy",
            category = ChannelCategory.ENTERTAINMENT,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "syfy",
                StreamingApp.YOUTUBE_TV to "syfy",
                StreamingApp.FUBO_TV to "syfy"
            )
        ),

        // ===== KIDS =====
        LiveChannel(
            name = "Cartoon Network",
            category = ChannelCategory.KIDS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "cartoon-network",
                StreamingApp.YOUTUBE_TV to "cartoon-network"
            )
        ),
        LiveChannel(
            name = "Nickelodeon",
            category = ChannelCategory.KIDS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "nickelodeon",
                StreamingApp.YOUTUBE_TV to "nickelodeon",
                StreamingApp.FUBO_TV to "nickelodeon"
            )
        ),
        LiveChannel(
            name = "Disney Channel",
            category = ChannelCategory.KIDS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "disney-channel",
                StreamingApp.YOUTUBE_TV to "disney-channel",
                StreamingApp.FUBO_TV to "disney-channel"
            )
        ),
        LiveChannel(
            name = "Disney Junior",
            category = ChannelCategory.KIDS,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "disney-junior",
                StreamingApp.YOUTUBE_TV to "disney-junior"
            )
        ),

        // ===== MOVIES =====
        LiveChannel(
            name = "TCM (Turner Classic Movies)",
            category = ChannelCategory.MOVIES,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "tcm",
                StreamingApp.YOUTUBE_TV to "tcm"
            )
        ),

        // ===== LIFESTYLE =====
        LiveChannel(
            name = "HGTV",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "hgtv",
                StreamingApp.YOUTUBE_TV to "hgtv",
                StreamingApp.FUBO_TV to "hgtv"
            )
        ),
        LiveChannel(
            name = "Food Network",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "food-network",
                StreamingApp.YOUTUBE_TV to "food-network",
                StreamingApp.FUBO_TV to "food-network"
            )
        ),
        LiveChannel(
            name = "TLC",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "tlc",
                StreamingApp.YOUTUBE_TV to "tlc",
                StreamingApp.FUBO_TV to "tlc"
            )
        ),
        LiveChannel(
            name = "Discovery Channel",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "discovery",
                StreamingApp.YOUTUBE_TV to "discovery",
                StreamingApp.FUBO_TV to "discovery"
            )
        ),
        LiveChannel(
            name = "History Channel",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "history",
                StreamingApp.YOUTUBE_TV to "history",
                StreamingApp.FUBO_TV to "history"
            )
        ),
        LiveChannel(
            name = "A&E",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "aande",
                StreamingApp.YOUTUBE_TV to "ae",
                StreamingApp.FUBO_TV to "ae"
            )
        ),
        LiveChannel(
            name = "Lifetime",
            category = ChannelCategory.LIFESTYLE,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "lifetime",
                StreamingApp.YOUTUBE_TV to "lifetime",
                StreamingApp.FUBO_TV to "lifetime"
            )
        ),

        // ===== MUSIC =====
        LiveChannel(
            name = "MTV",
            category = ChannelCategory.MUSIC,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "mtv",
                StreamingApp.YOUTUBE_TV to "mtv",
                StreamingApp.FUBO_TV to "mtv"
            )
        ),
        LiveChannel(
            name = "VH1",
            category = ChannelCategory.MUSIC,
            channelIds = mapOf(
                StreamingApp.SLING_TV to "vh1",
                StreamingApp.YOUTUBE_TV to "vh1"
            )
        )
    )

    /** Get all channels available on a specific streaming app */
    fun getChannelsForApp(app: StreamingApp): List<LiveChannel> {
        return channels.filter { it.channelIds.containsKey(app) }
    }

    /** Get channels in a specific category for a specific app */
    fun getChannelsForAppByCategory(
        app: StreamingApp,
        category: ChannelCategory
    ): List<LiveChannel> {
        return channels.filter {
            it.channelIds.containsKey(app) && it.category == category
        }
    }

    /** Get all categories that have channels for a specific app */
    fun getCategoriesForApp(app: StreamingApp): List<ChannelCategory> {
        return channels
            .filter { it.channelIds.containsKey(app) }
            .map { it.category }
            .distinct()
            .sortedBy { it.ordinal }
    }

    /** Get the channel ID for a specific app */
    fun getChannelId(channel: LiveChannel, app: StreamingApp): String? {
        return channel.channelIds[app]
    }

    /** Which apps are "live TV" apps that have channel guides */
    val liveTvApps: Set<StreamingApp> = setOf(
        StreamingApp.SLING_TV,
        StreamingApp.YOUTUBE_TV,
        StreamingApp.FUBO_TV,
        StreamingApp.PLUTO_TV
    )

    /** Check if an app is a live TV service */
    fun isLiveTvApp(app: StreamingApp): Boolean = app in liveTvApps
}
