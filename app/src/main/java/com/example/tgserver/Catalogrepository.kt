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

    /** Blocking call — always run this from a background thread/coroutine. */
    fun fetchCatalog(baseUrl: String): List<CatalogItem> {
        val url = URL("$baseUrl/catalog")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

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
        return items
    }
}