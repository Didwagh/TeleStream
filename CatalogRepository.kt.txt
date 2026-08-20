package com.example.tgserver

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class PartItem(
    val originalName: String,
    val size: Long,
    val chatId: Long,
    val messageId: Long
)

data class CatalogItem(
    val title: String,
    val totalSize: Long,
    val parts: List<PartItem>
)

object CatalogRepository {

    /**
     * Blocking call - always run this from a background thread/coroutine.
     * baseUrl should be the companion app's own local server
     * (http://127.0.0.1:38471) - this is what MainActivity's "Fetch
     * Catalog" button calls now, not Render.
     */
    fun fetchCatalog(baseUrl: String, channelId: Long, forceRefresh: Boolean = false): List<CatalogItem> {
        val refreshParam = if (forceRefresh) "&refresh=1" else ""
        val fullUrl = "$baseUrl/catalog?channel_id=$channelId$refreshParam"
        FileLogger.log("CatalogRepository fetching: $fullUrl")

        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000 // catalog build can take a while on first call

        val responseCode = conn.responseCode
        FileLogger.log("CatalogRepository response code: $responseCode")

        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()

        if (responseCode !in 200..299) {
            FileLogger.error("CatalogRepository got non-2xx response: $responseCode body=$text")
            throw RuntimeException("Catalog fetch failed ($responseCode): $text")
        }

        val arr = JSONArray(text)
        val items = mutableListOf<CatalogItem>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val partsArr = obj.getJSONArray("parts")
            val parts = mutableListOf<PartItem>()

            for (j in 0 until partsArr.length()) {
                val p = partsArr.getJSONObject(j)
                parts.add(
                    PartItem(
                        originalName = p.optString("original_name", "unknown"),
                        size = p.optLong("size", 0),
                        chatId = p.optLong("chat_id", 0),
                        messageId = p.optLong("message_id", 0)
                    )
                )
            }

            items.add(
                CatalogItem(
                    title = obj.optString("title", "Unknown"),
                    totalSize = obj.optLong("total_size", 0),
                    parts = parts
                )
            )
        }
        FileLogger.log("CatalogRepository parsed ${items.size} item(s)")
        return items
    }
}
