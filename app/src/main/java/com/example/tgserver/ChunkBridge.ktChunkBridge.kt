package com.example.tgserver

import kotlinx.coroutines.TimeoutCancellationException
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
 * Current TDLib build uses 64-bit Long for File.size and
 * DownloadFile.offset/.limit (confirmed from real source) - no more
 * 2.14GB cap like the old tdlibx build had.
 */
class ChunkBridge(
    private val fileId: Int,
    private val fileSize: Long,
    private val steadyChunkSize: Long = 4L * 1024 * 1024,
    private val firstChunkSize: Long = 512L * 1024
) {
    private var filePath: String? = null
    private val fetchedRanges = mutableListOf<LongRange>()
    private val fetchedLock = Any()

    @Volatile
    private var hasDownloadedAnything = false

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

    private suspend fun ensureDownloaded(offset: Long, limit: Long) {
        val alreadyHave = synchronized(fetchedLock) {
            fetchedRanges.any { offset >= it.first && offset + limit <= it.last }
        }
        if (alreadyHave) return

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
                            TelegramClient.removeFileListener(fileId, listener)
                            cont.resume(Unit)
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
