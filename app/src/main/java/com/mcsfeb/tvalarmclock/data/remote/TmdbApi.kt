package com.mcsfeb.tvalarmclock.data.remote

import com.mcsfeb.tvalarmclock.data.model.ContentInfo
import com.mcsfeb.tvalarmclock.data.model.EpisodeInfo
import com.mcsfeb.tvalarmclock.data.model.MediaType
import com.mcsfeb.tvalarmclock.data.model.SeasonInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TmdbApi - Searches for shows and movies using The Movie Database (TMDB).
 *
 * TMDB is a free, community-built database of movies and TV shows.
 * We use it so the user can search "Stranger Things" and get real results
 * instead of having to know some cryptic content ID.
 *
 * API docs: https://developer.themoviedb.org/docs
 *
 * The API key below is a free-tier key for this app.
 * Rate limit: ~40 requests per 10 seconds (plenty for our use).
 */
object TmdbApi {

    // TMDB API v3 key (free tier, read-only access to public movie/TV data)
    // This key only provides read access to publicly available movie/show info
    private const val API_KEY = "edb8fc7519ecffc0d801075e9e7c68ec"
    private const val BASE_URL = "https://api.themoviedb.org/3"
    const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w200"

    /**
     * Search for TV shows and movies by name.
     *
     * Example: searchContent("Stranger Things") returns a list of matching shows/movies
     * with their TMDB IDs, titles, descriptions, and poster images.
     */
    fun searchContent(query: String): List<ContentInfo> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/multi?api_key=$API_KEY&query=$encodedQuery&include_adult=false"

        return try {
            val json = fetchJson(url)
            val results = json.getJSONArray("results")
            val contentList = mutableListOf<ContentInfo>()

            for (i in 0 until minOf(results.length(), 10)) {
                val item = results.getJSONObject(i)
                val type = item.optString("media_type", "")

                // Only include TV shows and movies (skip "person" results)
                if (type == "tv" || type == "movie") {
                    contentList.add(
                        ContentInfo(
                            title = item.optString(
                                if (type == "tv") "name" else "title",
                                "Unknown"
                            ),
                            description = item.optString("overview", ""),
                            year = (item.optString(
                                if (type == "tv") "first_air_date" else "release_date",
                                ""
                            )).take(4),  // Just the year
                            tmdbId = item.getInt("id"),
                            posterPath = if (item.isNull("poster_path")) null else item.optString("poster_path", ""),
                            mediaType = if (type == "tv") MediaType.TV_SHOW else MediaType.MOVIE
                        )
                    )
                }
            }
            contentList
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get the seasons of a TV show (how many seasons, how many episodes each).
     */
    fun getSeasons(tmdbId: Int): List<SeasonInfo> {
        val url = "$BASE_URL/tv/$tmdbId?api_key=$API_KEY"

        return try {
            val json = fetchJson(url)
            val seasons = json.getJSONArray("seasons")
            val seasonList = mutableListOf<SeasonInfo>()

            for (i in 0 until seasons.length()) {
                val season = seasons.getJSONObject(i)
                val seasonNum = season.getInt("season_number")
                if (seasonNum == 0) continue  // Skip "Specials" season

                seasonList.add(
                    SeasonInfo(
                        seasonNumber = seasonNum,
                        name = season.optString("name", "Season $seasonNum"),
                        episodeCount = season.optInt("episode_count", 0),
                        episodes = emptyList()  // Episodes loaded on demand
                    )
                )
            }
            seasonList
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get the episodes of a specific season.
     */
    fun getEpisodes(tmdbId: Int, seasonNumber: Int): List<EpisodeInfo> {
        val url = "$BASE_URL/tv/$tmdbId/season/$seasonNumber?api_key=$API_KEY"

        return try {
            val json = fetchJson(url)
            val episodes = json.getJSONArray("episodes")
            val episodeList = mutableListOf<EpisodeInfo>()

            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                episodeList.add(
                    EpisodeInfo(
                        seasonNumber = seasonNumber,
                        episodeNumber = ep.getInt("episode_number"),
                        name = ep.optString("name", "Episode ${ep.getInt("episode_number")}"),
                        overview = ep.optString("overview", ""),
                        airDate = if (ep.isNull("air_date")) null else ep.optString("air_date", "")
                    )
                )
            }
            episodeList
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get streaming providers for a show/movie (which apps have it).
     *
     * TMDB provides "watch providers" data via JustWatch.
     * This tells us if a show is on Netflix, Hulu, Disney+, etc.
     *
     * NOTE: This tells us WHERE a show is available, but NOT the specific
     * content ID needed for deep linking. For content IDs, we use our
     * own curated ContentIdMapper.
     */
    fun getWatchProviders(tmdbId: Int, mediaType: MediaType): List<String> {
        val type = if (mediaType == MediaType.TV_SHOW) "tv" else "movie"
        val url = "$BASE_URL/$type/$tmdbId/watch/providers?api_key=$API_KEY"

        return try {
            val json = fetchJson(url)
            val results = json.getJSONObject("results")

            // Get US providers
            if (!results.has("US")) return emptyList()
            val us = results.getJSONObject("US")

            val providers = mutableListOf<String>()
            // "flatrate" = subscription streaming (Netflix, Hulu, etc.)
            if (us.has("flatrate")) {
                val flatrate = us.getJSONArray("flatrate")
                for (i in 0 until flatrate.length()) {
                    providers.add(flatrate.getJSONObject(i).getString("provider_name"))
                }
            }
            providers
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Simple HTTP GET that returns a JSONObject.
     * We use basic HttpURLConnection to avoid adding OkHttp/Retrofit dependencies.
     */
    private fun fetchJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            val response = connection.inputStream.bufferedReader().readText()
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
