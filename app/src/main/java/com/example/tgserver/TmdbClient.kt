package com.example.tgserver

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TmdbMatch(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val posterUrl: String?
)

object TmdbClient {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    @Volatile private var apiKey: String = ""
    private val cache = ConcurrentHashMap<String, TmdbMatch?>()

    fun init(key: String) {
        apiKey = key.trim()
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun searchMovie(title: String, year: Int?): TmdbMatch? {
        if (!isConfigured() || title.isBlank()) return null
        val cacheKey = "movie:${title.lowercase()}:${year ?: ""}"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]

        val result = runCatching {
            val yearParam = if (year != null) "&primary_release_year=$year" else ""
            val searchUrl = "$BASE/search/movie?api_key=$apiKey&query=${encode(title)}$yearParam"
            val results = getJson(searchUrl)?.optJSONArray("results")
            if (results == null || results.length() == 0) return@runCatching null

            val best = results.getJSONObject(0)
            val tmdbId = best.optInt("id", -1)
            val posterPath = best.optString("poster_path", "")
            val resolvedTitle = best.optString("title", title).ifBlank { title }
            val releaseYear = best.optString("release_date", "").take(4).toIntOrNull()

            val imdbId = if (tmdbId != -1) {
                getJson("$BASE/movie/$tmdbId/external_ids?api_key=$apiKey")
                    ?.optString("imdb_id", "")?.ifBlank { null }
            } else null

            TmdbMatch(
                title = resolvedTitle,
                year = releaseYear ?: year,
                imdbId = imdbId,
                posterUrl = posterPath.ifBlank { null }?.let { "$IMAGE_BASE$it" }
            )
        }.onFailure {
            FileLogger.error("TmdbClient.searchMovie failed for '$title' ($year)", it)
        }.getOrNull()

        cache[cacheKey] = result
        return result
    }

    fun searchTv(title: String): TmdbMatch? {
        if (!isConfigured() || title.isBlank()) return null
        val cacheKey = "tv:${title.lowercase()}"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]

        val result = runCatching {
            val searchUrl = "$BASE/search/tv?api_key=$apiKey&query=${encode(title)}"
            val results = getJson(searchUrl)?.optJSONArray("results")
            if (results == null || results.length() == 0) return@runCatching null

            val best = results.getJSONObject(0)
            val tmdbId = best.optInt("id", -1)
            val posterPath = best.optString("poster_path", "")
            val resolvedTitle = best.optString("name", title).ifBlank { title }
            val releaseYear = best.optString("first_air_date", "").take(4).toIntOrNull()

            val imdbId = if (tmdbId != -1) {
                getJson("$BASE/tv/$tmdbId/external_ids?api_key=$apiKey")
                    ?.optString("imdb_id", "")?.ifBlank { null }
            } else null

            TmdbMatch(
                title = resolvedTitle,
                year = releaseYear,
                imdbId = imdbId,
                posterUrl = posterPath.ifBlank { null }?.let { "$IMAGE_BASE$it" }
            )
        }.onFailure {
            FileLogger.error("TmdbClient.searchTv failed for '$title'", it)
        }.getOrNull()

        cache[cacheKey] = result
        return result
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                FileLogger.error("TmdbClient HTTP $code for $url: $text")
                null
            } else {
                JSONObject(text)
            }
        } catch (e: Exception) {
            FileLogger.error("TmdbClient request failed for $url", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}