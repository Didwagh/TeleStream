package com.example.tgserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.RandomAccessFile
import kotlin.coroutines.resume

/**
 * One ChunkBridge per active file. read(position, length) always returns
 * exactly `length` bytes (or throws).
 *
 * Fast-start design: the very FIRST download for a given file uses a small
 * chunk (firstChunkSize) so playback can begin almost immediately, instead
 * of waiting for a full-size chunk. Every download after that uses the
 * larger steadyChunkSize, which is more efficient for sustained sequential
 * playback (fewer, bigger TDLib requests). This applies regardless of
 * WHERE the first request lands - so it helps whether the player reads
 * from the start of the file first, or jumps to the end first to read an
 * MP4 index (moov atom) before finding the first playable frame.
 *
 * Background readahead: after serving a chunk, the NEXT sequential chunk
 * is kicked off in the background (not awaited) so it's likely already
 * downloaded by the time playback reaches it - this smooths out TV/slower
 * players without blindly padding every request, which would waste
 * bandwidth on content that might never actually get watched.
 *
 * Current TDLib build uses 64-bit Long for File.size and
 * DownloadFile.offset/.limit (confirmed from real source) - no more
 * 2.14GB cap like the old tdlibx build had.
 */
class ChunkBridge(
    private val fileId: Int,
    private val fileSize: Long,
    private val steadyChunkSize: Long = 3L * 1024 * 1024,
    private val firstChunkSize: Long = 512L * 1024
) {
    companion object {
        // Caps how many speculative (warmup/prefetch) downloads run at
        // once, across ALL files - CloudStream can fire /warmup for
        // several catalog items in a burst just from you viewing their
        // info pages (see the logs this was diagnosed from: 3-4 warmups
        // within milliseconds of each other, for files you never actually
        // played). Real playback reads are never gated by this - only the
        // speculative "just in case" downloads queue behind it, which
        // matters most on a low-end device with limited RAM/storage/CPU.
        private val speculativeSlots = kotlinx.coroutines.sync.Semaphore(2)
    }

    private var filePath: String? = null
    private val fetchedRanges = mutableListOf<LongRange>()
    private val fetchedLock = Any()
    private val readaheadScope = CoroutineScope(Dispatchers.IO)

    // Tracks how much of THIS file we've already charged against the data
    // cap, based on TDLib's own authoritative downloadedPrefixSize - not
    // the requested `limit` of any single call. Multiple concurrent
    // ensureDownloaded() calls (warmup + prefetch + readahead can all be
    // in flight for the same file at once) each used to count their own
    // full `limit` as "used", even when most or all of those bytes were
    // already on disk from another call moments earlier. That's what was
    // blowing through a 1GB cap in ~2 seconds - it was mostly the SAME
    // bytes being counted 2-3x over, not a real 1GB transfer. Diffing
    // against this shared, monotonically-updated baseline means only
    // genuinely new bytes ever get charged, no matter how many concurrent
    // callers are waiting on the same file.
    private val usageLock = Any()
    @Volatile
    private var lastAccountedBytes: Long = 0L

    @Volatile
    private var hasDownloadedAnything = false

    // End offset (exclusive) of the last chunk this ChunkBridge served,
    // used to tell genuine sequential playback apart from a seek/probe -
    // see the readahead-gating comment in read() below.
    @Volatile
    private var lastChunkEnd: Long = -1L

    suspend fun read(position: Long, length: Long): ByteArray {
        val effectiveChunkSize = if (hasDownloadedAnything) steadyChunkSize else firstChunkSize

        val chunkStart = (position / effectiveChunkSize) * effectiveChunkSize
        val chunkEndExclusive = minOf(
            ((position + length + effectiveChunkSize - 1) / effectiveChunkSize) * effectiveChunkSize,
            fileSize
        )
        val chunkLen = chunkEndExclusive - chunkStart

        ensureDownloaded(chunkStart, chunkLen)
        hasDownloadedAnything = true

        // Fire-and-forget: start pulling the next chunk in the background
        // now that this one is ready, so sequential playback rarely has to
        // wait on a chunk boundary. Never awaited, never blocks this call.
        //
        // Only do this when this read picks up exactly where the last one
        // left off (or this is the very first read for this file) - i.e.
        // it actually looks like sequential playback. A read that jumps to
        // a new position - a real seek, or one of several probe reads a
        // player fires at scattered offsets before real playback even
        // starts (very visible in the logs this was diagnosed from: a
        // burst of unrelated Range requests within milliseconds of each
        // other, across a multi-GB file) - is not a signal that the NEXT
        // chunk after this one is about to be needed too. Readahead used
        // to fire unconditionally on every single read regardless of
        // whether it was actually sequential, so each of those scattered
        // probe/seek reads kicked off its own speculative 3-4MB download
        // that usually never got revisited - that's what was silently
        // filling up the on-disk cache from a single small test file.
        val isSequentialContinuation = lastChunkEnd < 0 || chunkStart == lastChunkEnd
        if (chunkEndExclusive < fileSize && isSequentialContinuation) {
            val nextLen = minOf(steadyChunkSize, fileSize - chunkEndExclusive)
            readaheadScope.launch {
                try {
                    ensureDownloaded(chunkEndExclusive, nextLen)
                } catch (e: Exception) {
                    FileLogger.error("Background readahead failed at offset=$chunkEndExclusive", e)
                }
            }
        }
        lastChunkEnd = chunkEndExclusive

        val path = filePath ?: error("No local file path after download completed")
        val raf = RandomAccessFile(path, "r")
        try {
            raf.seek(position)
            val buf = ByteArray(length.toInt())
            raf.readFully(buf)
            return buf
        } finally {
            raf.close()
        }
    }

    /**
     * Kicks off a download for a specific range without waiting for it or
     * returning any bytes - used by /warmup and /prefetch to get a head
     * start before the player even requests anything.
     *
     * Gated by speculativeSlots: this is a "might be needed soon" bet, not
     * a confirmed need, so it queues behind a small cap instead of running
     * unbounded - CloudStream can fire several of these in a burst just
     * from you scrolling past catalog items.
     */
    fun prefetchInBackground(offset: Long, length: Long) {
        readaheadScope.launch {
            speculativeSlots.acquire()
            try {
                ensureDownloaded(offset, length)
                FileLogger.log("Prefetch complete for fileId=$fileId offset=$offset length=$length")
            } catch (e: Exception) {
                FileLogger.error("Prefetch failed for fileId=$fileId offset=$offset", e)
            } finally {
                speculativeSlots.release()
            }
        }
    }

    private suspend fun ensureDownloaded(offset: Long, limit: Long) {
        val alreadyHave = synchronized(fetchedLock) {
            fetchedRanges.any { offset >= it.first && offset + limit <= it.last }
        }
        if (alreadyHave) return

        if (DataUsageTracker.isBlocked()) {
            throw RuntimeException(
                "Video data limit reached - raise or reset it in TeleStream's Settings tab"
            )
        }

        val client = TelegramClient.rawClient()

        try {
            withTimeout(30_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    lateinit var listener: (TdApi.File) -> Unit
                    listener = { f ->
                        filePath = f.local.path
                        val downloadedTo = f.local.downloadOffset + f.local.downloadedPrefixSize
                        val covered = downloadedTo >= (offset + limit) || f.local.isDownloadingCompleted
                        if (covered && cont.isActive) {
                            synchronized(fetchedLock) { fetchedRanges.add(offset..(offset + limit)) }

                            // Charge only the genuinely NEW bytes TDLib
                            // now reports as downloaded, not this call's
                            // requested `limit` - see field comment above.
                            val totalDownloaded = f.local.downloadedPrefixSize.toLong()
                            synchronized(usageLock) {
                                val newBytes = totalDownloaded - lastAccountedBytes
                                if (newBytes > 0) {
                                    DataUsageTracker.addVideoBytes(newBytes)
                                    lastAccountedBytes = totalDownloaded
                                }
                            }

                            TelegramClient.removeFileListener(fileId, listener)
                            cont.resume(Unit)

                            // Tell TDLib to actually stop here. Despite
                            // the `limit` parameter on DownloadFile,
                            // observed behavior is that TDLib keeps
                            // pulling a file in the background well past
                            // the requested range once a download has
                            // been kicked off for it - which is how
                            // touching a handful of multi-GB files via
                            // /warmup (never even pressing Play) ran up
                            // several GB of storage. Explicitly cancelling
                            // once OUR specific request is satisfied is
                            // what actually enforces the small-chunk
                            // contract; the next read()/readahead/prefetch
                            // call simply issues a fresh DownloadFile for
                            // whatever it needs next, same as always.
                            if (!f.local.isDownloadingCompleted) {
                                client.send(TdApi.CancelDownloadFile(fileId, false)) { }
                            }
                        }
                    }
                    TelegramClient.addFileListener(fileId, listener)
                    cont.invokeOnCancellation { TelegramClient.removeFileListener(fileId, listener) }

                    val request = TdApi.DownloadFile().apply {
                        this.fileId = this@ChunkBridge.fileId
                        priority = 32
                        this.offset = offset
                        this.limit = limit
                        synchronous = false
                    }
                    client.send(request) { }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("Timed out waiting for Telegram at offset=$offset limit=$limit", e)
        }
    }
}
