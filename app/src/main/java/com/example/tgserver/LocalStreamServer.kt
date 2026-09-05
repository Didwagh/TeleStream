package com.example.tgserver

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.ByteArrayInputStream
import java.io.File
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
 * GET /video?chat_id=X&message_id=Y   - streams a specific file
 * GET /catalog?channel_id=X           - lists everything in that channel
 * GET /search?channel_id=X&query=Y    - filters the cached catalog
 * GET /warmup?chat_id=X&message_id=Y  - kicks off a low-priority prefetch
 * GET /prefetch?chat_id=X&message_id=Y - legacy head/tail prefetch
 * GET /subtitle?chat_id=X&message_id=Y - fetches a full subtitle file
 *
 * This is the ONLY HTTP server in the app. It is deliberately built on
 * NanoHTTPD + ChunkBridge rather than a hand-rolled socket parser, because
 * ChunkBridge's disk-backed read() (DownloadFile + UpdateFile listener +
 * RandomAccessFile) is the one download path that has actually been proven
 * to deliver bytes reliably. Do not replace /video's byte-delivery with
 * TelegramClient.streamFilePart()'s ReadFilePart-based loop - that path
 * depends on TDLib's readFilePart returning already-cached bytes, is not
 * verified against this project's vendored TdApi build, and is the
 * change that broke streaming. If it's ever revisited, prove it against a
 * real device first with the old path left intact as a fallback.
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
            "/search" -> serveSearch(session)
            "/warmup" -> serveWarmup(session)
            "/prefetch" -> servePrefetch(session)
            "/subtitle" -> serveSubtitle(session)
            else -> {
                FileLogger.log("Unknown route requested: ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
    }

    /**
     * Subtitle files are small (KBs, not GBs) - rather than routing them
     * through ChunkBridge's chunked-streaming machinery (built for
     * multi-GB video), this just blocks until the whole file is
     * downloaded and returns it in one response. Bounded by a 20s
     * timeout, generous for a file this size.
     */
    private fun serveSubtitle(session: IHTTPSession): Response {
        val chatId = session.parameters["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = session.parameters["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }

        return try {
            val bytes = runBlocking {
                withTimeout(20_000) {
                    downloadSubtitleFully(chatId, messageId)
                }
            }
            val name = session.parameters["name"]?.firstOrNull() ?: ""
            val contentType = if (name.endsWith(".vtt", ignoreCase = true)) "text/vtt" else "application/x-subrip"
            newFixedLengthResponse(Response.Status.OK, contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            FileLogger.error("Subtitle fetch failed for chatId=$chatId messageId=$messageId", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "subtitle fetch failed: ${e.message}")
        }
    }

    private suspend fun downloadSubtitleFully(chatId: Long, messageId: Long): ByteArray {
        val file = TelegramFileResolver.resolve(chatId, messageId)
        val client = TelegramClient.rawClient()

        if (file.local.isDownloadingCompleted) {
            return File(file.local.path).readBytes()
        }

        val downloaded = suspendCancellableCoroutine<TdApi.File> { cont ->
            lateinit var listener: (TdApi.File) -> Unit
            listener = { f ->
                if (f.id == file.id && f.local.isDownloadingCompleted && cont.isActive) {
                    TelegramClient.removeFileListener(file.id, listener)
                    cont.resume(f)
                }
            }
            TelegramClient.addFileListener(file.id, listener)
            cont.invokeOnCancellation { TelegramClient.removeFileListener(file.id, listener) }
            client.send(TdApi.DownloadFile(file.id, 32, 0, 0, false)) { }
        }

        return File(downloaded.local.path).readBytes()
    }

    /**
     * GET /warmup?chat_id=X&message_id=Y
     * Called from the CloudStream plugin's load() (movie detail screen) so
     * TDLib has already started fetching the first bytes of the file by the
     * time the user taps Play.
     *
     * This USED to delegate to TelegramClient.warmupFile(), which kicked
     * off its own whole-file DownloadFile call (offset=0, limit=0 - i.e.
     * "download to the end") completely separate from the ChunkBridge that
     * /video actually reads from, then auto-cancelled itself after 15s
     * based on an "active stream" counter that is only ever incremented by
     * the abandoned TelegramClient.streamFilePart() path - NOT by /video's
     * real ChunkBridge-based path. Net effect: it downloaded far more than
     * needed and then cancelled itself basically every time, regardless of
     * whether playback had actually started, because the counter it
     * checked was always 0. That's the "warmup was cancelled" behavior
     * seen in the logs.
     *
     * Fixed by routing through the same getOrResolve()/ChunkBridge that
     * /prefetch and /video use, so the bytes this warms up are the exact
     * same ones /video will ask for - nothing wasted, nothing orphaned.
     *
     * Head-only, deliberately: this fires every time a movie's info page
     * opens while browsing, not just when you actually intend to watch it.
     * The old version also grabbed the last ~2MB (in case the MP4 index
     * sits at the end) on every single one of those - which almost always
     * timed out anyway (see the repeated 30s-timeout entries in the logs)
     * and, on the rare file where it didn't, cached 2MB of a file you may
     * have just been glancing at and never actually opened. /prefetch is
     * head-only too now, for the same reason.
     */
    private fun serveWarmup(session: IHTTPSession): Response {
        val chatId = session.parameters["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = session.parameters["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }
        FileLogger.log("Warmup requested: chatId=$chatId messageId=$messageId")
        startHeadPrefetch(chatId, messageId)
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
    }

    /**
     * GET /search?channel_id=X&query=Y
     * Filters the already-built catalog cache by title or IMDb id. Never
     * blocks on a rebuild for the same reason /catalog doesn't (see below):
     * if nothing is cached yet, answers with an empty list and kicks off a
     * background build so a follow-up call has something to filter.
     */
    private fun serveSearch(session: IHTTPSession): Response {
        val channelId = session.parameters["channel_id"]?.firstOrNull()?.toLongOrNull()
        if (channelId == null) {
            FileLogger.error("Search request missing channel_id")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing channel_id")
        }
        val query = session.parameters["query"]?.firstOrNull()?.lowercase().orEmpty()
        FileLogger.log("Search request: channelId=$channelId query=$query")

        val cached = ChannelCatalogBuilder.peekCache(channelId)
        val items = if (cached != null) {
            cached
        } else {
            if (!ChannelCatalogBuilder.isCurrentlyBuilding()) {
                FileLogger.log("No cache yet for channelId=$channelId - triggering background build (search)")
                serverScope.launch {
                    try {
                        ChannelCatalogBuilder.getCatalog(channelId, forceRefresh = false)
                    } catch (e: Exception) {
                        FileLogger.error("Background catalog build failed (search)", e)
                    }
                }
            }
            emptyList()
        }

        val matched = items.filter {
            it.title.lowercase().contains(query) || (it.imdbId?.lowercase()?.contains(query) == true)
        }
        FileLogger.log("Search matched ${matched.size} item(s) for query='$query'")
        return newFixedLengthResponse(Response.Status.OK, "application/json", ChannelCatalogBuilder.toJson(matched))
    }

    /**
     * GET /prefetch?chat_id=X&message_id=Y
     * Not currently called by the CloudStream plugin (only /warmup is) -
     * kept as a standalone endpoint for manual testing / future use.
     * Head-only, same reasoning as /warmup: a tail-fetch on a multi-GB
     * file that hasn't been touched yet is a slow, unreliable random seek
     * (see the repeated 30s timeouts in the logs this was diagnosed from)
     * for content that may never even get watched. Always returns
     * immediately; never blocks the caller.
     */
    private fun servePrefetch(session: IHTTPSession): Response {
        val chatId = session.parameters["chat_id"]?.firstOrNull()?.toLongOrNull()
        val messageId = session.parameters["message_id"]?.firstOrNull()?.toLongOrNull()
        if (chatId == null || messageId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing chat_id/message_id")
        }

        startHeadPrefetch(chatId, messageId)

        // Ack immediately - the actual downloading happens in the
        // background via the coroutine launched above, not before this
        // response is sent.
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"prefetch_started\"}")
    }

    /**
     * Shared by /warmup and /prefetch: resolves the file (or reuses the
     * ChunkBridge already cached for it) and kicks off a background
     * download of the first ~2MB, via the same ChunkBridge instance
     * /video will read from - so none of this work is wasted or orphaned
     * regardless of which endpoint(s) called it or how many times.
     * ChunkBridge itself now cancels the TDLib download once this small
     * range is actually satisfied, so this can never balloon into
     * downloading the rest of the file in the background - see the
     * cancel-after-satisfied comment in ChunkBridge.ensureDownloaded().
     */
    private fun startHeadPrefetch(chatId: Long, messageId: Long) {
        serverScope.launch {
            try {
                val entry = getOrResolve(chatId, messageId)
                val prefetchSize = 2L * 1024 * 1024
                FileLogger.log("Prefetch (head-only) starting for chatId=$chatId messageId=$messageId (fileSize=${entry.fileSize})")
                entry.bridge.prefetchInBackground(0, minOf(prefetchSize, entry.fileSize))
            } catch (e: Exception) {
                FileLogger.error("Prefetch resolve failed for chatId=$chatId messageId=$messageId", e)
            }
        }
    }

    private fun serveCatalog(session: IHTTPSession): Response {
        val channelId = session.parameters["channel_id"]?.firstOrNull()?.toLongOrNull()
        if (channelId == null) {
            FileLogger.error("Catalog request missing channel_id")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing channel_id")
        }
        val forceRefresh = session.parameters["refresh"]?.firstOrNull() == "1"
        val fullRebuild = session.parameters["full_rebuild"]?.firstOrNull() == "1"
        FileLogger.log("Catalog request: channelId=$channelId forceRefresh=$forceRefresh fullRebuild=$fullRebuild")

        if (forceRefresh || fullRebuild) {
            // Explicit, deliberate action (manual refresh/full-rebuild
            // button or browser test) - acceptable to actually wait here,
            // since the caller asked for a sync on purpose and has its own
            // "loading" feedback while it waits. Note this is normally a
            // fast INCREMENTAL sync now (see ChannelCatalogBuilder) unless
            // full_rebuild=1 was explicitly requested.
            return try {
                val items = runBlocking {
                    ChannelCatalogBuilder.getCatalog(channelId, forceRefresh = true, fullRebuild = fullRebuild)
                }
                FileLogger.log("Catalog synced (blocking, requested): ${items.size} item(s)")
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

        val key = "$chatId:$messageId"
        if (DataUsageTracker.isBlocked() && !cache.containsKey(key)) {
            // Never touched this session, so nothing about it is already
            // downloaded - safe to reject upfront with a clean message
            // instead of letting the failure surface deep inside a
            // streaming response. A file that already has SOME bytes
            // cached this session is deliberately let through here -
            // ChunkBridge itself still blocks any genuinely NEW range on
            // it, but replaying/seeking within what's already downloaded
            // costs no additional data and should keep working.
            FileLogger.log("Video request blocked - data cap reached: chatId=$chatId messageId=$messageId")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "Video data limit reached - raise or reset it in TeleStream's Settings tab"
            )
        }

        val entry = try {
            getOrResolve(chatId, messageId)
        } catch (e: Exception) {
            FileLogger.error("Resolve failed for chatId=$chatId messageId=$messageId", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "resolve failed: ${e.message}")
        }

        val cached = ChannelCatalogBuilder.peekCache(chatId)
        val title = cached?.find { it.messageId == messageId }?.title ?: "Unknown Title"
        StreamingStatsTracker.setActiveStream(chatId, messageId, title, entry.fileSize)

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
            Entry(ChunkBridge(file.id, fileSize, chatId = chatId, messageId = messageId), fileSize)
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