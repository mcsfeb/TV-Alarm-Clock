package com.mcsfeb.tvalarmclock.player

import com.mcsfeb.tvalarmclock.data.model.StreamingApp

/**
 * UrlParser - Extracts content IDs from streaming service URLs.
 *
 * Instead of making the user figure out what a "content ID" is,
 * they just paste the URL from their browser and we extract the ID.
 *
 * Examples:
 *   "https://www.netflix.com/title/80057281" → app=NETFLIX, id="80057281"
 *   "https://www.youtube.com/watch?v=dQw4w9WgXcQ" → app=YOUTUBE, id="dQw4w9WgXcQ"
 *   "https://www.hulu.com/watch/8c2b6d15-ecdb-4c15-a0d3-6c7d07b0f323" → app=HULU, id="8c2b..."
 *   "https://www.disneyplus.com/video/some-id-here" → app=DISNEY_PLUS, id="some-id-here"
 */
object UrlParser {

    /**
     * Parse a URL and return the streaming app + content ID.
     * Returns null if we can't figure out what service the URL is from.
     */
    fun parse(url: String): ParseResult? {
        val trimmed = url.trim()

        return when {
            // Netflix: netflix.com/title/12345 or netflix.com/watch/12345
            trimmed.contains("netflix.com") -> parseNetflix(trimmed)

            // YouTube: youtube.com/watch?v=XXX or youtu.be/XXX
            trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> parseYouTube(trimmed)

            // Hulu: hulu.com/watch/UUID or hulu.com/series/slug
            trimmed.contains("hulu.com") -> parseHulu(trimmed)

            // Disney+: disneyplus.com/video/ID
            trimmed.contains("disneyplus.com") -> parseDisneyPlus(trimmed)

            // Prime Video: primevideo.com/detail/ID or amazon.com/gp/video/detail/ID
            trimmed.contains("primevideo.com") || trimmed.contains("amazon.com/gp/video") ->
                parsePrimeVideo(trimmed)

            // Max/HBO: play.max.com or play.hbomax.com
            trimmed.contains("max.com") || trimmed.contains("hbomax.com") -> parseHboMax(trimmed)

            // Paramount+: paramountplus.com/shows/video/ID
            trimmed.contains("paramountplus.com") -> parseParamountPlus(trimmed)

            // Peacock: peacocktv.com/watch/asset/ID
            trimmed.contains("peacocktv.com") -> parsePeacock(trimmed)

            // Crunchyroll: crunchyroll.com/watch/ID
            trimmed.contains("crunchyroll.com") -> parseCrunchyroll(trimmed)

            // Tubi: tubitv.com/movies/12345 or tubitv.com/tv-shows/12345
            trimmed.contains("tubitv.com") -> parseTubi(trimmed)

            // Pluto TV: pluto.tv/live-tv/ID
            trimmed.contains("pluto.tv") -> parsePlutoTv(trimmed)

            else -> null
        }
    }

    /**
     * Detect which streaming app a URL belongs to (without extracting the ID).
     */
    fun detectApp(url: String): StreamingApp? {
        return parse(url)?.app
    }

    // --- Individual parsers ---

    private fun parseNetflix(url: String): ParseResult? {
        // Patterns: /title/12345, /watch/12345
        val regex = Regex("""netflix\.com/(?:title|watch)/(\d+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.NETFLIX, "", "Netflix")
        return ParseResult(StreamingApp.NETFLIX, match.groupValues[1], "Netflix content ${match.groupValues[1]}")
    }

    private fun parseYouTube(url: String): ParseResult? {
        // Patterns: watch?v=XXX, youtu.be/XXX, /shorts/XXX
        val patterns = listOf(
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),           // youtube.com/watch?v=XXX
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),        // youtu.be/XXX
            Regex("""/shorts/([a-zA-Z0-9_-]{11})"""),           // youtube.com/shorts/XXX
            Regex("""/embed/([a-zA-Z0-9_-]{11})"""),            // youtube.com/embed/XXX
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return ParseResult(StreamingApp.YOUTUBE, match.groupValues[1], "YouTube video ${match.groupValues[1]}")
            }
        }
        return ParseResult(StreamingApp.YOUTUBE, "", "YouTube")
    }

    private fun parseHulu(url: String): ParseResult? {
        // Pattern: /watch/UUID
        val regex = Regex("""hulu\.com/watch/([a-f0-9-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.HULU, "", "Hulu")
        return ParseResult(StreamingApp.HULU, match.groupValues[1], "Hulu episode ${match.groupValues[1].take(8)}...")
    }

    private fun parseDisneyPlus(url: String): ParseResult? {
        // Pattern: /video/ID
        val regex = Regex("""disneyplus\.com/video/([a-zA-Z0-9-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.DISNEY_PLUS, "", "Disney+")
        return ParseResult(StreamingApp.DISNEY_PLUS, match.groupValues[1], "Disney+ content ${match.groupValues[1].take(8)}...")
    }

    private fun parsePrimeVideo(url: String): ParseResult? {
        // Pattern: /detail/ASIN or ?gti=ASIN
        val patterns = listOf(
            Regex("""detail/([A-Z0-9]+)"""),
            Regex("""[?&]gti=([A-Z0-9]+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return ParseResult(StreamingApp.PRIME_VIDEO, match.groupValues[1], "Prime Video ${match.groupValues[1]}")
            }
        }
        return ParseResult(StreamingApp.PRIME_VIDEO, "", "Prime Video")
    }

    private fun parseHboMax(url: String): ParseResult? {
        // Pattern: /episode/ID or /movie/ID
        val regex = Regex("""(?:max|hbomax)\.com/(?:episode|movie)/([a-zA-Z0-9-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.HBO_MAX, "", "Max")
        return ParseResult(StreamingApp.HBO_MAX, match.groupValues[1], "Max content ${match.groupValues[1].take(8)}...")
    }

    private fun parseParamountPlus(url: String): ParseResult? {
        val regex = Regex("""paramountplus\.com/shows/video/([a-zA-Z0-9_-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.PARAMOUNT_PLUS, "", "Paramount+")
        return ParseResult(StreamingApp.PARAMOUNT_PLUS, match.groupValues[1], "Paramount+ video ${match.groupValues[1].take(8)}...")
    }

    private fun parsePeacock(url: String): ParseResult? {
        val regex = Regex("""peacocktv\.com/watch/asset/([a-zA-Z0-9-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.PEACOCK, "", "Peacock")
        return ParseResult(StreamingApp.PEACOCK, match.groupValues[1], "Peacock content ${match.groupValues[1].take(8)}...")
    }

    private fun parseCrunchyroll(url: String): ParseResult? {
        val regex = Regex("""crunchyroll\.com/watch/([a-zA-Z0-9]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.CRUNCHYROLL, "", "Crunchyroll")
        return ParseResult(StreamingApp.CRUNCHYROLL, match.groupValues[1], "Crunchyroll episode ${match.groupValues[1]}")
    }

    private fun parseTubi(url: String): ParseResult? {
        val regex = Regex("""tubitv\.com/(?:movies|tv-shows)/(\d+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.TUBI, "", "Tubi")
        return ParseResult(StreamingApp.TUBI, match.groupValues[1], "Tubi content ${match.groupValues[1]}")
    }

    private fun parsePlutoTv(url: String): ParseResult? {
        val regex = Regex("""pluto\.tv/live-tv/([a-zA-Z0-9-]+)""")
        val match = regex.find(url) ?: return ParseResult(StreamingApp.PLUTO_TV, "", "Pluto TV")
        return ParseResult(StreamingApp.PLUTO_TV, match.groupValues[1], "Pluto TV channel ${match.groupValues[1]}")
    }

    /**
     * ParseResult - What we extracted from a URL.
     */
    data class ParseResult(
        val app: StreamingApp,
        val contentId: String,
        val friendlyName: String
    )
}
