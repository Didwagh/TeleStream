package com.example.tgserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChannelPart(
    val originalName: String,
    val size: Long,
    val chatId: Long,
    val messageId: Long
)

data class ChannelItem(
    val title: String,
    val totalSize: Long,
    val parts: List<ChannelPart>
)

/**
 * Builds the catalog directly from Telegram using the same already-logged-in
 * TDLib session used for streaming - no separate Pyrogram/Render session
 * involved, so there's no possibility of the two seeing a different view
 * of the channel (which is what caused the earlier 404 on GetMessage).
 *
 * Mirrors the grouping logic your Python backend used: group split files
 * by base filename (stripping .partNNN / .00N / .rNN / .mp4 / .mkv
 * suffixes), sum their sizes, title-case the result.
 */
object ChannelCatalogBuilder {

    private val splitFileSuffix = Regex("""\.(part\d+|00\d+|r\d+|mp4|mkv)$""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")

    private var cache: List<ChannelItem>? = null
    private var cachedChatId: Long? = null
    private val buildLock = Mutex()
    @Volatile private var isBuilding = false

    /**
     * Returns instantly, NEVER triggers a build, NEVER blocks. This is what
     * /catalog should call for ordinary requests (i.e. from CloudStream) -
     * a slow rebuild must never happen inside a request CloudStream's own
     * HTTP client is waiting on, since that client has its own timeout we
     * don't control. Returns null if nothing has been built yet for this
     * chatId.
     */
    fun peekCache(chatId: Long): List<ChannelItem>? {
        return if (cachedChatId == chatId) cache else null
    }

    fun isCurrentlyBuilding(): Boolean = isBuilding

    /**
     * The actual (slow) build, guarded by a lock so concurrent callers
     * (e.g. the auto-refresh on server start firing at the same moment as
     * a manual refresh tap) don't race each other and both hammer TDLib at
     * once - whoever asks first builds, everyone else just waits for that
     * same result instead of starting a second redundant build.
     */
    suspend fun getCatalog(chatId: Long, forceRefresh: Boolean = false): List<ChannelItem> {
        if (!forceRefresh && cachedChatId == chatId && cache != null) {
            return cache!!
        }
        return buildLock.withLock {
            // Re-check after acquiring the lock - another caller may have
            // just finished building while we were waiting for the lock.
            if (!forceRefresh && cachedChatId == chatId && cache != null) {
                return@withLock cache!!
            }
            isBuilding = true
            try {
                val items = build(chatId)
                cache = items
                cachedChatId = chatId
                items
            } finally {
                isBuilding = false
            }
        }
    }

    fun toJson(items: List<ChannelItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("title", item.title)
            obj.put("total_size", item.totalSize)
            val partsArr = JSONArray()
            item.parts.forEach { p ->
                val partObj = JSONObject()
                partObj.put("original_name", p.originalName)
                partObj.put("size", p.size)
                partObj.put("chat_id", p.chatId)
                partObj.put("message_id", p.messageId)
                partsArr.put(partObj)
            }
            obj.put("parts", partsArr)
            arr.put(obj)
        }
        return arr.toString()
    }

    private suspend fun build(chatId: Long): List<ChannelItem> {
        FileLogger.log("ChannelCatalogBuilder: starting build for chatId=$chatId")
        val client = TelegramClient.rawClient()

        // Ensure TDLib has the chat loaded before requesting its history.
        suspendCancellableCoroutine<Unit> { cont ->
            client.send(TdApi.GetChat(chatId)) { cont.resume(Unit) }
        }

        val grouped = LinkedHashMap<String, MutableList<ChannelPart>>()
        var fromMessageId = 0L
        var fetched = 0
        val maxMessages = 1000

        while (fetched < maxMessages) {
            val batchSize = minOf(100, maxMessages - fetched)
            val messages = suspendCancellableCoroutine<TdApi.Messages> { cont ->
                client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, batchSize, false)) { result ->
                    if (result is TdApi.Messages) cont.resume(result)
                    else cont.resumeWithException(RuntimeException("GetChatHistory failed: $result"))
                }
            }

            val batch = messages.messages?.filterNotNull() ?: emptyList()
            FileLogger.log("ChannelCatalogBuilder: page fetched ${batch.size} message(s), running total will be ${fetched + batch.size}")
            if (batch.isEmpty()) break

            for (message in batch) {
                val (fileName, fileSize) = when (val content = message.content) {
                    is TdApi.MessageVideo -> (content.video.fileName.ifBlank { "unknown.mp4" }) to content.video.video.size
                    is TdApi.MessageDocument -> (content.document.fileName.ifBlank { "unknown" }) to content.document.document.size
                    else -> continue
                }

                val baseName = whitespace.replace(splitFileSuffix.replace(fileName, ""), "").lowercase()
                grouped.getOrPut(baseName) { mutableListOf() }.add(
                    ChannelPart(fileName, fileSize, chatId, message.id)
                )
            }

            fetched += batch.size
            fromMessageId = batch.last().id
            // NOTE: do NOT stop just because this batch was smaller than
            // requested - right after login TDLib may not have finished
            // syncing the chat's full history yet, and a small early batch
            // does not mean "reached the start of the chat." The only
            // trustworthy stop signal is a genuinely empty batch (checked
            // at the top of the loop). Stopping early here was exactly why
            // a fresh login needed two refreshes to see the full catalog.
        }

        val result = grouped.map { (baseName, parts) ->
            val sortedParts = parts.sortedBy { it.originalName }
            ChannelItem(
                title = baseName.replace('.', ' ').replaceFirstChar { it.uppercase() },
                totalSize = sortedParts.sumOf { it.size },
                parts = sortedParts
            )
        }
        FileLogger.log("ChannelCatalogBuilder: build complete, ${result.size} item(s) from ${fetched} message(s) scanned")
        return result
    }
}
