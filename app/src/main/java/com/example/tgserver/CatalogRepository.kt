package com.example.tgserver

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class PartItem(
    val originalName: String,
    val size: Long,
    val chatId: Long,
    val messageId: Long,
    val label: String
)

data class CatalogItem(
    val type: String,
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val poster: String?,
    val totalSize: Long,
    val parts: List<PartItem>,
    val episodeCount: Int
)

object CatalogRepository {

    fun fetchCatalog(
        baseUrl: String,
        channelId: Long,
        forceRefresh: Boolean = false,
        // Ignore the persisted/incremental cache and walk the whole
        // channel from scratch, re-spending TMDB/Gemini calls on
        // everything. Use sparingly - see ChannelCatalogBuilder's kdoc.
        fullRebuild: Boolean = false
    ): List<CatalogItem> {
        val refreshParam = if (forceRefresh) "&refresh=1" else ""
        val fullRebuildParam = if (fullRebuild) "&full_rebuild=1" else ""
        val fullUrl = "$baseUrl/catalog?channel_id=$channelId$refreshParam$fullRebuildParam"
        FileLogger.log("CatalogRepository fetching: $fullUrl")

        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()

        if (responseCode !in 200..299) {
            throw RuntimeException("Catalog fetch failed ($responseCode): $text")
        }

        val arr = JSONArray(text)
        val items = mutableListOf<CatalogItem>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val partsArr = obj.optJSONArray("parts")
            val parts = mutableListOf<PartItem>()
            if (partsArr != null) {
                for (j in 0 until partsArr.length()) {
                    val p = partsArr.getJSONObject(j)
                    parts.add(
                        PartItem(
                            originalName = p.optString("original_name", "unknown"),
                            size = p.optLong("size", 0),
                            chatId = p.optLong("chat_id", 0),
                            messageId = p.optLong("message_id", 0),
                            label = p.optString("label", "")
                        )
                    )
                }
            }

            val episodesArr = obj.optJSONArray("episodes")
            val episodeCount = episodesArr?.length() ?: 0

            items.add(
                CatalogItem(
                    type = obj.optString("type", "movie"),
                    title = obj.optString("title", "Unknown"),
                    year = if (obj.isNull("year")) null else obj.optInt("year"),
                    imdbId = if (obj.isNull("imdb_id")) null else obj.optString("imdb_id"),
                    poster = if (obj.isNull("poster")) null else obj.optString("poster"),
                    totalSize = obj.optLong("total_size", 0),
                    parts = parts,
                    episodeCount = episodeCount
                )
            )
        }
        return items
    }
}