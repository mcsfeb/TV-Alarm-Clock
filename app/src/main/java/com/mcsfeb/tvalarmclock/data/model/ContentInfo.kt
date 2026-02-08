package com.mcsfeb.tvalarmclock.data.model

/**
 * ContentInfo - A show, movie, or video that the user can pick to play at alarm time.
 *
 * This is used for on-demand content (Netflix, Hulu, Disney+, etc.)
 * where the user searches for a show and picks a specific episode.
 *
 * The contentId maps to what the streaming app needs for its deep link.
 * For example, Netflix uses numeric IDs like "80057281" for Stranger Things.
 */
data class ContentInfo(
    val title: String,           // "Stranger Things"
    val description: String,     // "A group of kids uncover supernatural mysteries..."
    val year: String,            // "2016"
    val tmdbId: Int,             // TMDB database ID (used to look up streaming info)
    val posterPath: String?,     // Poster image URL from TMDB
    val mediaType: MediaType     // TV show or Movie
)

/**
 * EpisodeInfo - A specific episode of a TV show.
 */
data class EpisodeInfo(
    val seasonNumber: Int,       // Season 2
    val episodeNumber: Int,      // Episode 1
    val name: String,            // "MADMAX"
    val overview: String,        // "Episode description..."
    val airDate: String?         // "2017-10-27"
)

/**
 * SeasonInfo - A season of a TV show with its episodes.
 */
data class SeasonInfo(
    val seasonNumber: Int,
    val name: String,            // "Season 2"
    val episodeCount: Int,
    val episodes: List<EpisodeInfo>
)

enum class MediaType {
    TV_SHOW,
    MOVIE
}

/**
 * StreamingAvailability - Which streaming apps have a specific piece of content.
 *
 * This maps a TMDB show/movie to its content IDs on different streaming apps.
 * For example, "Stranger Things" on Netflix has ID "80057281".
 *
 * NOTE: We maintain a curated mapping for popular shows. For unknown content,
 * the user can still manually enter the content ID with our helpful tips.
 */
data class StreamingAvailability(
    val tmdbId: Int,
    val contentIds: Map<StreamingApp, String>  // App -> content ID for deep linking
)
