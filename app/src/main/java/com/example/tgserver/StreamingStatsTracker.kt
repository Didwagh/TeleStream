package com.example.tgserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamStats(
    val chatId: Long = 0,
    val messageId: Long = 0,
    val title: String = "",
    val fileSize: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSecond: Long = 0
)

object StreamingStatsTracker {
    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private var lastBytesRead = 0L
    private var lastTime = System.currentTimeMillis()

    fun setActiveStream(chatId: Long, messageId: Long, title: String, fileSize: Long) {
        if (_stats.value.chatId != chatId || _stats.value.messageId != messageId) {
            _stats.update {
                it.copy(
                    chatId = chatId,
                    messageId = messageId,
                    title = title,
                    fileSize = fileSize,
                    downloadedBytes = 0,
                    speedBytesPerSecond = 0
                )
            }
            lastBytesRead = 0L
            lastTime = System.currentTimeMillis()
        }
    }

    fun reportDownloadedBytes(chatId: Long, messageId: Long, totalDownloaded: Long) {
        if (_stats.value.chatId != chatId || _stats.value.messageId != messageId) return

        val now = System.currentTimeMillis()
        _stats.update { current ->
            var newSpeed = current.speedBytesPerSecond
            // Update speed at most once every second to avoid jitter
            if (now - lastTime >= 1000) {
                val timeDiff = (now - lastTime) / 1000.0
                val bytesDiff = totalDownloaded - lastBytesRead
                if (bytesDiff >= 0) {
                    newSpeed = (bytesDiff / timeDiff).toLong()
                } else {
                    newSpeed = 0
                }
                lastTime = now
                lastBytesRead = totalDownloaded
            }

            current.copy(
                downloadedBytes = totalDownloaded,
                speedBytesPerSecond = newSpeed
            )
        }
    }
}
