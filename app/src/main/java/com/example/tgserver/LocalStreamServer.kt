package com.example.tgserver

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

private class ChunkBridgeInputStream(
    private val bridge: ChunkBridge,
    start: Long,
    private val length: Long
) : InputStream() {
    private var position = start
    private val endPosExclusive = start + length

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= endPosExclusive) return -1
        val remaining = (endPosExclusive - position).coerceAtMost(len.toLong()).toInt()
        val chunk = runBlocking { bridge.read(position, remaining.toLong()) }
        System.arraycopy(chunk, 0, b, off, chunk.size)
        position += chunk.size
        return chunk.size
    }
}

/**
 * GET /video?chat_id=X&message_id=Y  - streams a specific file
 * GET /catalog?channel_id=X          - lists everything in that channel,
 *                                       built live from TDLib, no Render
 *                                       involved at all
 * One process, so this shares the already-logged-in TelegramClient
 * singleton automatically.
 */
class LocalStreamServer(port: Int) : NanoHTTPD(port) {

    private data class Entry(val bridge: ChunkBridge, val fileSize: Long)

    // ConcurrentHashMap.computeIfAbsent is atomic per-key: if /prefetch and
    // /video race for the same file (exactly what happens now that /prefetch
    // fires as soon as the detail screen opens), only one ChunkBridge ever
    // gets created for it - the second caller gets the same instance instead
    // of silently creating and orphaning a duplicate.
    private val cache = ConcurrentHashMap<String, Entry>()
    private val serverScope = CoroutineScope(Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        FileLogger.log("HTTP request: ${session.method} ${session.uri}?${session.queryParameterString}")
        return when (session.uri) {
            "/video" -> serveVideo(session)
            "/catalog" -> serveCatalog(session)
            "/prefetch" -> servePrefetch(session)
            else -> {
                FileLogger.log("Unknown route requested: ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
    }

    /**
     * GET /prefetch?chat_id=X&message_id=Y
     * Called from the CloudStream plugin's load() (movie detail screen) to
     * get a head start before the user actually taps Play. Grabs the first
     * and last ~2MB in the background - the start for immediate playback,
     * the end in case this file's MP4 index (moov atom) sits at the end
     * rather than the start. Always returns immediately; never blocks the
     * caller, since load() shouldn't be slowed down by this.
     */
    private fun servePrefetch(session: IHTTPSession): Response {
        val chatId = session.parameters["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = session.parameters["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }

        serverScope.launch {
            try {
                val entry = getOrResolve(chatId, messageId)
                val prefetchSize = 2L * 1024 * 1024
                FileLogger.log("Prefetch starting for chatId=$chatId messageId=$messageId (fileSize=${entry.fileSize})")

                entry.bridge.prefetchInBackground(0, minOf(prefetchSize, entry.fileSize))

                if (entry.fileSize > prefetchSize) {
                    val tailStart = maxOf(0, entry.fileSize - prefetchSize)
                    entry.bridge.prefetchInBackground(tailStart, entry.fileSize - tailStart)
                }
            } catch (e: Exception) {
                FileLogger.error("Prefetch resolve failed for chatId=$chatId messageId=$messageId", e)
            }
        }

        // Ack immediately - the actual downloading happens in the
        // background via the coroutine launched above, not before this
        // response is sent.
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"prefetch_started\"}")
    }

    private fun serveCatalog(session: IHTTPSession): Response {
        val channelId = session.parameters["channel_id"]?.firstOrNull()?.toLongOrNull()
        if (channelId == null) {
            FileLogger.error("Catalog request missing channel_id")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing channel_id")
        }
        val forceRefresh = session.parameters["refresh"]?.firstOrNull() == "1"
        FileLogger.log("Catalog request: channelId=$channelId forceRefresh=$forceRefresh")

        if (forceRefresh) {
            // Explicit, deliberate action (manual refresh button or browser
            // test) - acceptable to actually wait here, since the caller
            // asked for a fresh rebuild on purpose and has its own "loading"
            // feedback while it waits.
            return try {
                val items = runBlocking { ChannelCatalogBuilder.getCatalog(channelId, true) }
                FileLogger.log("Catalog rebuilt (blocking, requested): ${items.size} item(s)")
                newFixedLengthResponse(Response.Status.OK, "application/json", ChannelCatalogBuilder.toJson(items))
            } catch (e: Exception) {
                FileLogger.error("Catalog rebuild failed", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "catalog build failed: ${e.message}")
            }
        }

        // Ordinary request (this is what CloudStream calls) - NEVER block
        // on a rebuild here. CloudStream's own HTTP client has a timeout we
        // don't control, and a slow TDLib pagination walk inside this
        // request is exactly what was causing "SocketTimeoutException" on
        // the CloudStream side. Answer instantly from cache, and silently
        // kick off a background rebuild if nothing is cached yet or if the
        // cache looks stale - by the time the person taps play, it'll very
        // likely already be warm.
        val cached = ChannelCatalogBuilder.peekCache(channelId)
        if (cached == null) {
            if (!ChannelCatalogBuilder.isCurrentlyBuilding()) {
                FileLogger.log("No cache yet for channelId=$channelId - triggering background build")
                serverScope.launch {
                    try {
                        ChannelCatalogBuilder.getCatalog(channelId, forceRefresh = false)
                    } catch (e: Exception) {
                        FileLogger.error("Background catalog build failed", e)
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "[]")
        }

        FileLogger.log("Serving cached catalog instantly: ${cached.size} item(s)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", ChannelCatalogBuilder.toJson(cached))
    }

    private fun serveVideo(session: IHTTPSession): Response {
        val params = session.parameters
        val chatId = params["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = params["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            FileLogger.error("Video request missing chat_id/message_id")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }
        FileLogger.log("Video requested: chatId=$chatId messageId=$messageId range=${session.headers["range"]}")

        val entry = try {
            getOrResolve(chatId, messageId)
        } catch (e: Exception) {
            FileLogger.error("Resolve failed for chatId=$chatId messageId=$messageId", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "resolve failed: ${e.message}")
        }

        val rangeHeader = session.headers["range"]
        val (start, endInclusive) = if (rangeHeader != null) {
            parseRange(rangeHeader, entry.fileSize)
        } else {
            0L to (entry.fileSize - 1)
        }
        val length = endInclusive - start + 1

        val stream = ChunkBridgeInputStream(entry.bridge, start, length)
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK

        val resp = newFixedLengthResponse(status, "video/mp4", stream, length)
        resp.addHeader("Accept-Ranges", "bytes")
        if (rangeHeader != null) {
            resp.addHeader("Content-Range", "bytes $start-$endInclusive/${entry.fileSize}")
        }
        return resp
    }

    private fun getOrResolve(chatId: Long, messageId: Long): Entry {
        val key = "$chatId:$messageId"
        return cache.computeIfAbsent(key) {
            val file = runBlocking { TelegramFileResolver.resolve(chatId, messageId) }
            val fileSize = file.size.toLong()
            Entry(ChunkBridge(file.id, fileSize), fileSize)
        }
    }

    private fun parseRange(header: String, fileSize: Long): Pair<Long, Long> {
        val spec = header.removePrefix("bytes=")
        val parts = spec.split("-")
        val start = parts[0].toLong()
        val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else fileSize - 1
        return start to end
    }
}
