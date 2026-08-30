package com.example.tgserver

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Optional, OPT-IN fallback classifier for the handful of filenames the
 * deterministic parser (TitleParser) + TMDB genuinely can't resolve on
 * their own - e.g. a title TMDB can't fuzzy-match, or one that reads like
 * a movie but is actually a whole series/season packaged as a single file
 * with no S0xE0x marker and no "combined" tag to hint at it either.
 *
 * This is deliberately NOT on the main path: it is only ever called from
 * ChannelCatalogBuilder AFTER a leaf has already failed a normal
 * TmdbClient.searchMovie() lookup, and only if a Gemini API key has been
 * configured (blank/unset = fully disabled, zero calls, zero cost). That
 * keeps API usage bounded to genuinely hard cases instead of running on
 * every file in the channel.
 */
object GeminiClient {

    enum class MediaType { MOVIE, SERIES }

    data class Classification(
        val type: MediaType,
        val title: String,
        val year: Int?
    )

    // Update this if/when a newer Gemini model becomes the recommended
    // default - kept as a single constant so it's a one-line change.
    private const val MODEL = "gemini-2.0-flash"

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    @Volatile
    private var apiKey: String = ""

    fun init(key: String) {
        apiKey = key.trim()
    }

    fun isConfigured(): Boolean =
        apiKey.isNotBlank()

    /**
     * Asks Gemini to identify the real title/year/media-type behind a raw,
     * messy release filename. Returns null on any failure (network, bad
     * response, not configured) - callers should treat null exactly like
     * "no extra information available" and fall back to their existing
     * behavior, never block on this.
     */
    fun classify(rawFilename: String): Classification? {

        if (!isConfigured() || rawFilename.isBlank()) {
            return null
        }

        return runCatching {

            val prompt =
                "You identify the real movie or TV series behind a " +
                    "messy release filename from a piracy-style file " +
                    "share. Respond with ONLY a compact JSON object, no " +
                    "markdown, no code fences, no extra text, in exactly " +
                    "this shape: " +
                    "{\"type\":\"movie\"|\"series\",\"title\":\"<clean official title>\"," +
                    "\"year\":<4-digit year or null>}. " +
                    "If the filename indicates a whole TV series or season " +
                    "bundled into a single file (no per-episode numbering), " +
                    "classify it as \"series\". " +
                    "Filename: $rawFilename"

            val body =
                JSONObject().apply {
                    put(
                        "contents",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "parts",
                                    org.json.JSONArray().put(
                                        JSONObject().put("text", prompt)
                                    )
                                )
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("temperature", 0.0)
                            put("responseMimeType", "application/json")
                        }
                    )
                }

            val responseText =
                postJson(
                    "$ENDPOINT?key=$apiKey",
                    body.toString()
                ) ?: return@runCatching null

            val outer = JSONObject(responseText)

            val candidateText =
                outer.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?: return@runCatching null

            val parsed = JSONObject(candidateText.trim())

            val typeStr =
                parsed.optString("type", "movie")

            val title =
                parsed.optString("title", "").trim()

            if (title.isBlank()) {
                return@runCatching null
            }

            val year =
                if (parsed.isNull("year")) {
                    null
                } else {
                    parsed.optInt("year", -1).takeIf { it > 1900 }
                }

            Classification(
                type =
                    if (typeStr.equals("series", ignoreCase = true)) {
                        MediaType.SERIES
                    } else {
                        MediaType.MOVIE
                    },
                title = title,
                year = year
            )

        }.onFailure {
            FileLogger.error(
                "GeminiClient.classify failed for '$rawFilename'",
                it
            )
        }.getOrNull()
    }

    private fun postJson(url: String, jsonBody: String): String? {

        val conn =
            URL(url).openConnection() as HttpURLConnection

        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }

            if (code !in 200..299) {
                FileLogger.error("GeminiClient HTTP $code: $text")
                null
            } else {
                text
            }

        } catch (e: Exception) {
            FileLogger.error("GeminiClient request failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}
