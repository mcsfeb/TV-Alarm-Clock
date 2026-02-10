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
        // --- Netflix Originals ---
        66732 to mapOf(StreamingApp.NETFLIX to "80057281"),    // Stranger Things
        1396 to mapOf(StreamingApp.NETFLIX to "70143836"),     // Breaking Bad
        100088 to mapOf(StreamingApp.NETFLIX to "81091393"),   // The Last of Us (HBO originally, but for mapping)
        93405 to mapOf(StreamingApp.NETFLIX to "80236318"),    // Squid Game
        71446 to mapOf(StreamingApp.NETFLIX to "80100172"),    // Money Heist
        1402 to mapOf(StreamingApp.NETFLIX to "70286137"),     // The Walking Dead
        76479 to mapOf(StreamingApp.NETFLIX to "80186863"),    // The Umbrella Academy
        73021 to mapOf(StreamingApp.NETFLIX to "80192098"),    // Disenchantment
        44217 to mapOf(StreamingApp.NETFLIX to "70143830"),    // Vikings
        95557 to mapOf(StreamingApp.NETFLIX to "81166747"),    // Inventing Anna
        82856 to mapOf(                                         // The Mandalorian
            StreamingApp.DISNEY_PLUS to "mandalorian"
        ),
        84958 to mapOf(                                         // Loki
            StreamingApp.DISNEY_PLUS to "loki"
        ),
        114461 to mapOf(                                        // Ahsoka
            StreamingApp.DISNEY_PLUS to "ahsoka"
        ),
        92749 to mapOf(                                         // Moon Knight
            StreamingApp.DISNEY_PLUS to "moon-knight"
        ),

        // --- Hulu ---
        136315 to mapOf(StreamingApp.HULU to "the-bear"),      // The Bear
        125988 to mapOf(StreamingApp.HULU to "only-murders-in-the-building"), // Only Murders in the Building

        // --- HBO / Max ---
        94997 to mapOf(StreamingApp.HBO_MAX to "house-of-the-dragon"), // House of the Dragon
        1399 to mapOf(StreamingApp.HBO_MAX to "game-of-thrones"),      // Game of Thrones
        87108 to mapOf(StreamingApp.HBO_MAX to "euphoria"),            // Euphoria
        79744 to mapOf(StreamingApp.HBO_MAX to "the-last-of-us"),      // The Last of Us
        153312 to mapOf(StreamingApp.HBO_MAX to "the-penguin"),        // The Penguin

        // --- Prime Video ---
        76479 to mapOf(StreamingApp.PRIME_VIDEO to "B09WV8GCZN"),     // The Boys (also on Netflix above)
        60059 to mapOf(StreamingApp.PRIME_VIDEO to "B08BZNQGB2"),     // The Expanse

        // --- YouTube (popular channels/videos by creator) ---
        // YouTube uses video IDs, not TMDB IDs, so this mapping is less useful.
        // Users will typically search YouTube directly or paste video IDs.

        // --- Popular Movies ---
        550 to mapOf(                                           // Fight Club
            StreamingApp.PRIME_VIDEO to "B00A3Y4LDI"
        ),
        680 to mapOf(                                           // Pulp Fiction
            StreamingApp.PRIME_VIDEO to "B00BT7QDJI"
        ),
        238 to mapOf(                                           // The Godfather
            StreamingApp.PRIME_VIDEO to "B001GJ3CPC",
            StreamingApp.PARAMOUNT_PLUS to "the-godfather"
        ),
        155 to mapOf(                                           // The Dark Knight
            StreamingApp.HBO_MAX to "the-dark-knight"
        )
    )
}
