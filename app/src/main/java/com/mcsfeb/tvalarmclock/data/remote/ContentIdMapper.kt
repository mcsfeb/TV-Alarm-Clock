package com.mcsfeb.tvalarmclock.data.remote

import com.mcsfeb.tvalarmclock.data.model.MediaType
import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * ContentIdMapper - Maps TMDB IDs to streaming app deep link IDs.
 *
 * THE PROBLEM:
 * TMDB tells us "Stranger Things is on Netflix" but doesn't give us
 * Netflix's internal ID (80057281) that we need for the deep link.
 *
 * THE SOLUTION:
 * 1. For popular shows, we maintain a curated map of TMDB ID → app content ID
 * 2. For shows we don't have mapped, try IMDB IDs (some apps accept them)
 * 3. For everything else, fall back to search-based launching
 *
 * This curated list grows over time. The most common shows are included here.
 * In the future, this could be moved to a cloud database that updates independently.
 */
object ContentIdMapper {

    /**
     * Apps that accept IMDB IDs (like "tt4574334") in their deep links.
     * Prime Video and some others can resolve IMDB IDs to content.
     */
    private val appsAcceptingImdbIds = setOf(
        StreamingApp.PRIME_VIDEO,
        StreamingApp.APPLE_TV
    )

    /**
     * Try to get the streaming app content ID for a TMDB show/movie.
     *
     * Returns the content ID string needed for deep linking, or null if we
     * don't have a mapping for this content on this app.
     */
    fun getContentId(tmdbId: Int, app: StreamingApp): String? {
        return popularContentMap[tmdbId]?.get(app)
    }

    /**
     * Enhanced content ID lookup that also tries TMDB external IDs.
     *
     * LOOKUP ORDER:
     * 1. Curated mapping (most reliable, verified content IDs)
     * 2. IMDB ID for apps that accept it (Prime Video, Apple TV+)
     * 3. null (caller should use SEARCH mode)
     *
     * NOTE: This makes a network call to TMDB for external IDs, so call from IO thread.
     */
    fun getContentIdWithFallback(
        tmdbId: Int,
        app: StreamingApp,
        mediaType: MediaType
    ): String? {
        // Try curated mapping first
        val curated = popularContentMap[tmdbId]?.get(app)
        if (curated != null) return curated

        // Try IMDB ID for apps that support it
        if (app in appsAcceptingImdbIds) {
            val externalIds = TmdbApi.getExternalIds(tmdbId, mediaType)
            val imdbId = externalIds["imdb"]
            if (imdbId != null && imdbId.startsWith("tt")) {
                return imdbId
            }
        }

        return null
    }

    /**
     * Check if we have a known content ID for a specific TMDB show on any app.
     */
    fun getAvailableApps(tmdbId: Int): Map<StreamingApp, String> {
        return popularContentMap[tmdbId] ?: emptyMap()
    }

    /**
     * Curated mapping: TMDB ID → (StreamingApp → content ID for deep link)
     *
     * To add a new show:
     * 1. Search TMDB for the show, note its ID
     * 2. Find the content ID in the streaming app's URL
     * 3. Add the mapping below
     *
     * TMDB IDs are stable and don't change.
     */
    private val popularContentMap: Map<Int, Map<StreamingApp, String>> = mapOf(
        // --- Netflix ---
        // Netflix content IDs: numeric title ID from the URL at netflix.com/title/{id}
        66732 to mapOf(StreamingApp.NETFLIX to "80057281"),    // Stranger Things
        1396 to mapOf(StreamingApp.NETFLIX to "70143836"),     // Breaking Bad
        93405 to mapOf(StreamingApp.NETFLIX to "80236318"),    // Squid Game
        71446 to mapOf(StreamingApp.NETFLIX to "80100172"),    // Money Heist (La Casa de Papel)
        1402 to mapOf(StreamingApp.NETFLIX to "70286137"),     // The Walking Dead
        75006 to mapOf(StreamingApp.NETFLIX to "80186863"),    // The Umbrella Academy (TMDB: 75006)
        73021 to mapOf(StreamingApp.NETFLIX to "80192098"),    // Disenchantment
        44217 to mapOf(StreamingApp.NETFLIX to "70143830"),    // Vikings
        95557 to mapOf(StreamingApp.NETFLIX to "81166747"),    // Inventing Anna

        // --- Disney+ ---
        // Disney+ content IDs: alphanumeric ID from the URL at disneyplus.com/series/{slug}/{id}
        // NOTE: Deep links are unreliable on the Disney+ TV app; ContentLaunchService always
        // does a normal launch + profile select for Disney+ regardless of content ID.
        // These IDs are stored but not actively used for deep linking.
        // To find real IDs: open disneyplus.com, browse to show, copy the ID from the URL.
        // Example URL: https://www.disneyplus.com/series/the-mandalorian/sWp2UaK7QLNM
        //                                                                  ^^^^^^^^^^^^^ this is the ID

        // --- Hulu ---
        // Hulu content IDs MUST be UUIDs (e.g., "b835adf4-b0f0-4dc6-86d4-b4a1e53de19f").
        // Slug strings like "the-bear" are NOT valid; Hulu will show an error page.
        // To find a real UUID: browse to the episode on hulu.com, copy the UUID from the URL.
        // Example URL: https://www.hulu.com/watch/b835adf4-b0f0-4dc6-86d4-b4a1e53de19f
        //                                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ UUID goes here
        // No entries here until real UUIDs are added from the Hulu website.

        // --- HBO / Max ---
        // Max content IDs MUST be UUIDs. Slugs like "house-of-the-dragon" are NOT valid;
        // ContentLauncher detects non-UUID IDs and falls back to normal app launch.
        // To find a real UUID: browse to the episode on max.com, copy the UUID from the URL.
        // Example URL: https://play.max.com/video/watch/0e33e070-5a45-425a-b655-9d7a7a6658ba/...
        //                                                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ UUID
        // Verified UUID (Friends S1E1): 0e33e070-5a45-425a-b655-9d7a7a6658ba
        // No slug entries — slugs fall back to APP_ONLY; add real UUIDs from max.com URLs.

        // --- Prime Video ---
        // Prime Video content IDs are ASINs (Amazon Standard Identification Numbers).
        // To find: browse to the show/movie on Amazon, copy the ASIN from the URL.
        // Example URL: https://www.amazon.com/gp/video/detail/B09WV8GCZN/
        //                                                     ^^^^^^^^^^^ ASIN
        76479 to mapOf(StreamingApp.PRIME_VIDEO to "B09WV8GCZN"),     // The Boys
        60059 to mapOf(StreamingApp.PRIME_VIDEO to "B08BZNQGB2"),     // The Expanse

        // --- Paramount+ ---
        // Paramount+ content IDs: alphanumeric ID from the URL at paramountplus.com/shows/{slug}/episodes/{id}/
        // To find: browse to the episode on paramountplus.com, copy the ID from the URL.
        // Example: https://www.paramountplus.com/shows/star-trek-strange-new-worlds/episodes/CpcWiNoSvbk5bKEHHX1MDA/
        //                                                                                      ^^^^^^^^^^^^^^^^^^^^^^^^ ID

        // --- Tubi ---
        // Tubi content IDs: numeric ID from the URL at tubitv.com/movies/{id} or tubitv.com/series/{id}
        // To find: browse to the show on tubitv.com, copy the numeric ID from the URL.
        // Example: https://tubitv.com/series/300007157/avatar-the-last-airbender
        //                                  ^^^^^^^^^ numeric ID

        // --- YouTube (popular channels/videos by creator) ---
        // YouTube uses video IDs (11-character string), not TMDB IDs.
        // Users should manually enter video IDs or use the Manual Entry picker.
        // Example: https://www.youtube.com/watch?v=dQw4w9WgXcQ → video ID is "dQw4w9WgXcQ"

        // --- Popular Movies ---
        // Prime Video ASINs are stable and verified:
        550 to mapOf(                                           // Fight Club
            StreamingApp.PRIME_VIDEO to "B00A3Y4LDI"
        ),
        680 to mapOf(                                           // Pulp Fiction
            StreamingApp.PRIME_VIDEO to "B00BT7QDJI"
        ),
        238 to mapOf(                                           // The Godfather
            StreamingApp.PRIME_VIDEO to "B001GJ3CPC"
            // Paramount+ removed: slug IDs like "the-godfather" are not valid.
            // Add real P+ ID from paramountplus.com URL when available.
        )
        // The Dark Knight (TMDB 155): HBO Max requires a UUID, not a slug.
        // To add: find the UUID from play.max.com URL and add:
        //   155 to mapOf(StreamingApp.HBO_MAX to "<uuid-here>")
    )
}
