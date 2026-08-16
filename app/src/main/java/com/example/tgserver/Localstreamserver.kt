package com.example.tgserver

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.InputStream

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
 * GET /video?chat_id=X&message_id=Y
 * Resolves and caches a ChunkBridge per file the first time it's requested,
 * then serves range requests against it. One process, so this shares the
 * already-logged-in TelegramClient singleton automatically.
 */
class LocalStreamServer(port: Int) : NanoHTTPD(port) {

    private data class Entry(val bridge: ChunkBridge, val fileSize: Long)

    private val cache = HashMap<String, Entry>()
    private val cacheLock = Any()

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/video") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        val params = session.parameters
        val chatId = params["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = params["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }

        val entry = try {
            getOrResolve(chatId, messageId)
        } catch (e: Exception) {
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
        synchronized(cacheLock) { cache[key]?.let { return it } }

        val file = runBlocking { TelegramFileResolver.resolve(chatId, messageId) }
        val fileSize = file.size.toLong()
        val entry = Entry(ChunkBridge(file.id, fileSize), fileSize)

        synchronized(cacheLock) { cache[key] = entry }
        return entry
    }

    private fun parseRange(header: String, fileSize: Long): Pair<Long, Long> {
        val spec = header.removePrefix("bytes=")
        val parts = spec.split("-")
        val start = parts[0].toLong()
        val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else fileSize - 1
        return start to end
    }
}