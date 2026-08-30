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
    val posterUrl: String?,
    val overview: String? = null,
    val rating: Double? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList()
)

object TmdbClient {

    private const val BASE =
        "https://api.themoviedb.org/3"

    private const val IMAGE_BASE =
        "https://image.tmdb.org/t/p/w500"

    // BetterPosters/Btttr uses the IMDb id to generate its poster.
    // Keep this as a poster-only enhancement; TMDB remains the source
    // for title matching and the rest of the metadata.
    private const val BETTER_POSTER_BASE =
        "https://btttr.cc/poster/imdb/poster-default"

    private const val MAX_CAST = 6

    @Volatile
    private var apiKey: String = ""

    private val cache =
        ConcurrentHashMap<String, TmdbMatch>()

    private val NOT_FOUND =
        TmdbMatch(
            title = "",
            year = null,
            imdbId = null,
            posterUrl = null
        )

    fun init(key: String) {
        apiKey = key.trim()
    }

    fun isConfigured(): Boolean =
        apiKey.isNotBlank()

    /**
     * Build a BetterPosters/Btttr poster URL from a valid IMDb id.
     * Returns null when the id is not usable, so callers can fall back
     * to the existing TMDB poster without changing the rest of the flow.
     */
    private fun betterPosterUrl(
        imdbId: String?
    ): String? {
        val id =
            imdbId
                ?.trim()
                ?.takeIf {
                    it.startsWith("tt") &&
                        it.length > 2
                }
                ?: return null

        return "$BETTER_POSTER_BASE/$id.jpg"
    }

    fun searchMovie(
        title: String,
        year: Int?
    ): TmdbMatch? {

        if (!isConfigured() || title.isBlank()) {
            return null
        }

        val cacheKey =
            "movie:${title.lowercase().trim()}:${year ?: ""}"

        cache[cacheKey]?.let {
            return if (it === NOT_FOUND) {
                null
            } else {
                it
            }
        }

        val result =
            runCatching {

                val yearParam =
                    if (
                        year != null &&
                        year > 1900
                    ) {
                        "&primary_release_year=$year"
                    } else {
                        ""
                    }

                val searchUrl =
                    "$BASE/search/movie" +
                        "?api_key=$apiKey" +
                        "&query=${encode(title)}" +
                        yearParam

                val json =
                    getJson(searchUrl)

                var results =
                    json?.optJSONArray("results")

                // Fallback without year if the strict search found nothing.
                if (
                    (results == null ||
                        results.length() == 0) &&
                    yearParam.isNotEmpty()
                ) {

                    val fallbackUrl =
                        "$BASE/search/movie" +
                            "?api_key=$apiKey" +
                            "&query=${encode(title)}"

                    results =
                        getJson(fallbackUrl)
                            ?.optJSONArray("results")
                }

                val best =
                    chooseMovieResult(
                        results,
                        title,
                        year
                    )

                if (best == null) {
                    return@runCatching null
                }

                val tmdbId =
                    best.optInt("id", -1)

                val details =
                    if (tmdbId != -1) {
                        getJson(
                            "$BASE/movie/$tmdbId" +
                                "?api_key=$apiKey" +
                                "&append_to_response=credits,external_ids"
                        )
                    } else {
                        null
                    }

                val posterPath =
                    best.optString(
                        "poster_path",
                        ""
                    ).ifBlank {
                        details?.optString(
                            "poster_path",
                            ""
                        ).orEmpty()
                    }

                val fallbackImagePath =
                    posterPath.ifBlank {
                        details?.optString(
                            "backdrop_path",
                            ""
                        ).orEmpty()
                    }

                val resolvedTitle =
                    best.optString(
                        "title",
                        title
                    ).ifBlank {
                        title
                    }

                val releaseYear =
                    best.optString(
                        "release_date",
                        ""
                    )
                        .take(4)
                        .toIntOrNull()

                val imdbId =
                    details
                        ?.optJSONObject(
                            "external_ids"
                        )
                        ?.optString(
                            "imdb_id",
                            ""
                        )
                        ?.ifBlank {
                            null
                        }

                val tmdbPosterUrl =
                    fallbackImagePath
                        .ifBlank {
                            null
                        }
                        ?.let {
                            "$IMAGE_BASE$it"
                        }

                TmdbMatch(
                    title =
                        resolvedTitle,

                    year =
                        releaseYear
                            ?: year,

                    imdbId =
                        imdbId,

                    // Prefer the BetterPosters image when the matched
                    // title has an IMDb id; otherwise retain TMDB.
                    posterUrl =
                        betterPosterUrl(imdbId)
                            ?: tmdbPosterUrl,

                    overview =
                        (
                            details
                                ?.optString(
                                    "overview"
                                )
                                ?: best.optString(
                                    "overview",
                                    ""
                                )
                            ).ifBlank {
                                null
                            },

                    rating =
                        (
                            details
                                ?: best
                            )
                            .optDouble(
                                "vote_average",
                                0.0
                            )
                            .takeIf {
                                it > 0.0
                            },

                    runtimeMinutes =
                        details
                            ?.optInt(
                                "runtime",
                                0
                            )
                            ?.takeIf {
                                it > 0
                            },

                    genres =
                        extractGenres(
                            details
                        ),

                    cast =
                        extractCast(
                            details
                        )
                )

            }.onFailure {

                FileLogger.error(
                    "TmdbClient.searchMovie failed for '$title' ($year)",
                    it
                )
            }.getOrNull()

        cache[cacheKey] =
            result ?: NOT_FOUND

        return result
    }

    fun searchTv(
        title: String,
        year: Int? = null
    ): TmdbMatch? {

        if (!isConfigured() || title.isBlank()) {
            return null
        }

        val cacheKey =
            "tv:${title.lowercase().trim()}:${year ?: ""}"

        cache[cacheKey]?.let {
            return if (it === NOT_FOUND) {
                null
            } else {
                it
            }
        }

        val result =
            runCatching {

                val yearParam =
                    if (
                        year != null &&
                        year > 1900
                    ) {
                        "&first_air_date_year=$year"
                    } else {
                        ""
                    }

                val searchUrl =
                    "$BASE/search/tv" +
                        "?api_key=$apiKey" +
                        "&query=${encode(title)}" +
                        yearParam

                var results =
                    getJson(searchUrl)
                        ?.optJSONArray("results")

                // Important fallback:
                // a filename year can refer to a release/package year
                // that differs from TMDB's first-air year.
                if (
                    (results == null ||
                        results.length() == 0) &&
                    yearParam.isNotEmpty()
                ) {

                    val fallbackUrl =
                        "$BASE/search/tv" +
                            "?api_key=$apiKey" +
                            "&query=${encode(title)}"

                    results =
                        getJson(fallbackUrl)
                            ?.optJSONArray("results")
                }

                val best =
                    chooseTvResult(
                        results,
                        title,
                        year
                    )

                if (best == null) {
                    return@runCatching null
                }

                val tmdbId =
                    best.optInt("id", -1)

                val details =
                    if (tmdbId != -1) {

                        getJson(
                            "$BASE/tv/$tmdbId" +
                                "?api_key=$apiKey" +
                                "&append_to_response=credits,external_ids"
                        )

                    } else {
                        null
                    }

                val posterPath =
                    best.optString(
                        "poster_path",
                        ""
                    ).ifBlank {
                        details?.optString(
                            "poster_path",
                            ""
                        ).orEmpty()
                    }

                // This is a graceful last-resort image.
                // Normally poster_path will be used.
                val fallbackImagePath =
                    posterPath.ifBlank {

                        details?.optString(
                            "backdrop_path",
                            ""
                        ).orEmpty()
                    }

                val resolvedTitle =
                    best.optString(
                        "name",
                        title
                    ).ifBlank {
                        title
                    }

                val releaseYear =
                    best.optString(
                        "first_air_date",
                        ""
                    )
                        .take(4)
                        .toIntOrNull()

                val runtime =
                    details
                        ?.optJSONArray(
                            "episode_run_time"
                        )
                        ?.takeIf {
                            it.length() > 0
                        }
                        ?.optInt(
                            0,
                            0
                        )
                        ?.takeIf {
                            it > 0
                        }

                val imdbId =
                    details
                        ?.optJSONObject(
                            "external_ids"
                        )
                        ?.optString(
                            "imdb_id",
                            ""
                        )
                        ?.ifBlank {
                            null
                        }

                val tmdbPosterUrl =
                    fallbackImagePath
                        .ifBlank {
                            null
                        }
                        ?.let {
                            "$IMAGE_BASE$it"
                        }

                TmdbMatch(
                    title =
                        resolvedTitle,

                    year =
                        releaseYear
                            ?: year,

                    imdbId =
                        imdbId,

                    // Prefer BetterPosters when an IMDb id exists,
                    // otherwise use the existing TMDB image.
                    posterUrl =
                        betterPosterUrl(imdbId)
                            ?: tmdbPosterUrl,

                    overview =
                        (
                            details
                                ?.optString(
                                    "overview"
                                )
                                ?: best.optString(
                                    "overview",
                                    ""
                                )
                            ).ifBlank {
                                null
                            },

                    rating =
                        (
                            details
                                ?: best
                            )
                            .optDouble(
                                "vote_average",
                                0.0
                            )
                            .takeIf {
                                it > 0.0
                            },

                    runtimeMinutes =
                        runtime,

                    genres =
                        extractGenres(
                            details
                        ),

                    cast =
                        extractCast(
                            details
                        )
                )

            }.onFailure {

                FileLogger.error(
                    "TmdbClient.searchTv failed for '$title' ($year)",
                    it
                )
            }.getOrNull()

        cache[cacheKey] =
            result ?: NOT_FOUND

        return result
    }

    // ------------------------------------------------------------
    // TMDB result selection
    // ------------------------------------------------------------

    private fun chooseMovieResult(
        results: org.json.JSONArray?,
        title: String,
        year: Int?
    ): JSONObject? {

        if (
            results == null ||
            results.length() == 0
        ) {
            return null
        }

        val normalizedQuery =
            normalizeTitle(title)

        var best: JSONObject? = null

        var bestScore = Int.MIN_VALUE

        for (i in 0 until results.length()) {

            val item =
                results.optJSONObject(i)
                    ?: continue

            val candidateTitle =
                item.optString(
                    "title",
                    ""
                )

            val candidateNormalized =
                normalizeTitle(
                    candidateTitle
                )

            var score = 0

            if (
                candidateNormalized ==
                normalizedQuery
            ) {
                score += 100
            }

            if (
                candidateNormalized
                    .contains(
                        normalizedQuery
                    ) ||
                normalizedQuery.contains(
                    candidateNormalized
                )
            ) {
                score += 35
            }

            if (
                item.optString(
                    "poster_path",
                    ""
                ).isNotBlank()
            ) {
                score += 20
            }

            val candidateYear =
                item.optString(
                    "release_date",
                    ""
                )
                    .take(4)
                    .toIntOrNull()

            if (
                year != null &&
                candidateYear == year
            ) {
                score += 40
            }

            score +=
                item.optInt(
                    "vote_count",
                    0
                )
                    .coerceAtMost(
                        50_000
                    ) / 1000

            if (score > bestScore) {

                bestScore = score
                best = item
            }
        }

        return best
    }

    private fun chooseTvResult(
        results: org.json.JSONArray?,
        title: String,
        year: Int?
    ): JSONObject? {

        if (
            results == null ||
            results.length() == 0
        ) {
            return null
        }

        val normalizedQuery =
            normalizeTitle(title)

        var best: JSONObject? = null

        var bestScore = Int.MIN_VALUE

        for (i in 0 until results.length()) {

            val item =
                results.optJSONObject(i)
                    ?: continue

            val candidateTitle =
                item.optString(
                    "name",
                    ""
                )

            val candidateNormalized =
                normalizeTitle(
                    candidateTitle
                )

            var score = 0

            // Exact title is the strongest signal.
            if (
                candidateNormalized ==
                normalizedQuery
            ) {
                score += 120
            }

            // Partial title similarity.
            if (
                candidateNormalized
                    .contains(
                        normalizedQuery
                    ) ||
                normalizedQuery.contains(
                    candidateNormalized
                )
            ) {
                score += 40
            }

            // Prefer anything that can give us an image.
            if (
                item.optString(
                    "poster_path",
                    ""
                ).isNotBlank()
            ) {
                score += 30
            }

            val candidateYear =
                item.optString(
                    "first_air_date",
                    ""
                )
                    .take(4)
                    .toIntOrNull()

            if (
                year != null &&
                candidateYear == year
            ) {
                score += 50
            }

            // Mild popularity tie-breaker.
            score +=
                item.optInt(
                    "vote_count",
                    0
                )
                    .coerceAtMost(
                        50_000
                    ) / 1000

            if (score > bestScore) {

                bestScore = score
                best = item
            }
        }

        return best
    }

    private fun normalizeTitle(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()
            .replace(
                Regex("\\s+"),
                " "
            )
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private fun extractGenres(
        details: JSONObject?
    ): List<String> {

        val arr =
            details?.optJSONArray(
                "genres"
            )
                ?: return emptyList()

        return (0 until arr.length())
            .mapNotNull { i ->

                arr.optJSONObject(i)
                    ?.optString("name")
                    ?.ifBlank {
                        null
                    }
            }
    }

    private fun extractCast(
        details: JSONObject?
    ): List<String> {

        val arr =
            details
                ?.optJSONObject("credits")
                ?.optJSONArray("cast")
                ?: return emptyList()

        val limit =
            minOf(
                arr.length(),
                MAX_CAST
            )

        return (0 until limit)
            .mapNotNull { i ->

                arr.optJSONObject(i)
                    ?.optString("name")
                    ?.ifBlank {
                        null
                    }
            }
    }

    private fun encode(
        s: String
    ): String =
        URLEncoder.encode(
            s,
            "UTF-8"
        )

    private fun getJson(
        url: String
    ): JSONObject? {

        val conn =
            URL(url)
                .openConnection()
                as HttpURLConnection

        conn.requestMethod =
            "GET"

        conn.connectTimeout =
            10_000

        conn.readTimeout =
            10_000

        return try {

            val code =
                conn.responseCode

            val stream =
                if (
                    code in 200..299
                ) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }

            val text =
                stream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            if (
                code !in 200..299
            ) {

                FileLogger.error(
                    "TmdbClient HTTP $code for $url: $text"
                )

                null

            } else {

                JSONObject(text)
            }

        } catch (e: Exception) {

            FileLogger.error(
                "TmdbClient request failed for $url",
                e
            )

            null

        } finally {

            conn.disconnect()
        }
    }
}