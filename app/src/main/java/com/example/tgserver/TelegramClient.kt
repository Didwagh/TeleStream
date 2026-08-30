package com.example.tgserver

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AuthState {
    object Idle : AuthState()
    object WaitPhone : AuthState()
    object WaitCode : AuthState()
    object WaitPassword : AuthState()
    object Ready : AuthState()
    data class Error(val message: String) : AuthState()
}

object TelegramClient {

    private var client: Client? = null
    val authState = MutableStateFlow<AuthState>(AuthState.Idle)
    private var tdlibParams: TdApi.SetTdlibParameters? = null

    // Tracks active streams per fileId to pause downloads when the video is closed
    private val activeStreamCounts = ConcurrentHashMap<Int, Int>()

    // Listeners registered per specific fileId
    private val fileIdListeners = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.File) -> Unit>>()
    private val fileIdUpdateListeners = ConcurrentHashMap<Int, CopyOnWriteArrayList<(TdApi.UpdateFile) -> Unit>>()
    private val globalFileListeners = CopyOnWriteArrayList<(TdApi.UpdateFile) -> Unit>()

    @JvmName("addFileListenerWithFile")
    fun addFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        fileIdListeners.getOrPut(fileId) { CopyOnWriteArrayList() }.add(listener)
    }

    @JvmName("addFileListenerWithUpdate")
    fun addFileListener(fileId: Int, listener: (TdApi.UpdateFile) -> Unit) {
        fileIdUpdateListeners.getOrPut(fileId) { CopyOnWriteArrayList() }.add(listener)
    }

    fun addFileListener(listener: (TdApi.UpdateFile) -> Unit) {
        globalFileListeners.add(listener)
    }

    @JvmName("removeFileListenerWithFile")
    fun removeFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        fileIdListeners[fileId]?.remove(listener)
    }

    @JvmName("removeFileListenerWithUpdate")
    fun removeFileListener(fileId: Int, listener: (TdApi.UpdateFile) -> Unit) {
        fileIdUpdateListeners[fileId]?.remove(listener)
    }

    fun removeFileListener(listener: (TdApi.UpdateFile) -> Unit) {
        globalFileListeners.remove(listener)
    }

    fun rawClient(): Client = client ?: throw IllegalStateException("TelegramClient not initialized")

    fun init(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return

        runCatching {
            System.loadLibrary("tdjni")
            FileLogger.log("libtdjni.so loaded")
        }.onFailure {
            FileLogger.error("Failed to load libtdjni.so", it)
        }

        val dbDir = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
        val filesDir = File(context.cacheDir, "tdlib_files").apply { mkdirs() }.absolutePath

        tdlibParams = TdApi.SetTdlibParameters().apply {
            this.databaseDirectory = dbDir
            this.filesDirectory = filesDir
            this.useMessageDatabase = true
            this.useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            this.systemLanguageCode = "en"
            this.deviceModel = if (Build.MODEL.isNullOrBlank()) "Android" else Build.MODEL
            this.systemVersion = if (Build.VERSION.RELEASE.isNullOrBlank()) "10.0" else Build.VERSION.RELEASE
            this.applicationVersion = "1.0"
        }

        runCatching {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        }.onFailure {
            FileLogger.error("SetLogVerbosityLevel error", it)
        }

        client = Client.create({ update ->
            handleUpdate(update)
        }, { updateError ->
            FileLogger.error("TDLib update error: ${updateError?.message}")
        }, { defaultError ->
            FileLogger.error("TDLib default error: ${defaultError?.message}")
        })
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateFile) {
            val file = update.file
            val fileId = file.id

            fileIdListeners[fileId]?.forEach { listener ->
                try { listener.invoke(file) } catch (e: Exception) { FileLogger.error("Error in fileIdListener", e) }
            }
            fileIdUpdateListeners[fileId]?.forEach { listener ->
                try { listener.invoke(update) } catch (e: Exception) { FileLogger.error("Error in fileIdUpdateListener", e) }
            }
            globalFileListeners.forEach { listener ->
                try { listener.invoke(update) } catch (e: Exception) { FileLogger.error("Error in globalFileListener", e) }
            }
        }

        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                when (update.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> {
                        tdlibParams?.let { params ->
                            sendSafe(params) { res ->
                                if (res is TdApi.Error) {
                                    FileLogger.error("SetTdlibParameters error: ${res.message}")
                                    authState.value = AuthState.Error(res.message)
                                }
                            }
                        }
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        authState.value = AuthState.WaitPhone
                    }
                    is TdApi.AuthorizationStateWaitCode -> {
                        authState.value = AuthState.WaitCode
                    }
                    is TdApi.AuthorizationStateWaitPassword -> {
                        authState.value = AuthState.WaitPassword
                    }
                    is TdApi.AuthorizationStateReady -> {
                        authState.value = AuthState.Ready
                        FileLogger.log("TDLib AuthorizationStateReady")
                    }
                    is TdApi.AuthorizationStateLoggingOut,
                    is TdApi.AuthorizationStateClosed -> {
                        authState.value = AuthState.Idle
                    }
                }
            }
        }
    }

    private fun sendSafe(query: TdApi.Function<*>, handler: Client.ResultHandler) {
        val c = client
        if (c != null) {
            c.send(query, handler)
        } else {
            Thread {
                var retries = 0
                while (client == null && retries < 20) {
                    Thread.sleep(50)
                    retries++
                }
                client?.send(query, handler)
            }.start()
        }
    }

    fun submitPhone(phone: String) {
        sendSafe(TdApi.SetAuthenticationPhoneNumber(phone, null)) { res ->
            if (res is TdApi.Error) authState.value = AuthState.Error(res.message)
        }
    }

    fun submitCode(code: String) {
        sendSafe(TdApi.CheckAuthenticationCode(code)) { res ->
            if (res is TdApi.Error) authState.value = AuthState.Error(res.message)
        }
    }

    fun submitPassword(password: String) {
        sendSafe(TdApi.CheckAuthenticationPassword(password)) { res ->
            if (res is TdApi.Error) authState.value = AuthState.Error(res.message)
        }
    }

    suspend fun getMessageFile(chatId: Long, messageId: Long): Pair<TdApi.File, String> {
        val c = rawClient()
        val msg = suspendCancellableCoroutine<TdApi.Message> { cont ->
            c.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) cont.resume(result)
                else cont.resumeWithException(RuntimeException("GetMessage failed: $result"))
            }
        }

        return when (val content = msg.content) {
            is TdApi.MessageVideo -> content.video.video to content.video.fileName.ifBlank { "video.mp4" }
            is TdApi.MessageDocument -> content.document.document to content.document.fileName.ifBlank { "document.mp4" }
            else -> throw IllegalArgumentException("Message is not a video or document")
        }
    }

    // NOTE: there used to be a warmupFile(chatId, messageId) here, called
    // from LocalStreamServer's /warmup route. It issued its own
    // whole-file TdApi.DownloadFile(offset=0, limit=0) completely
    // separate from the ChunkBridge that /video actually reads from, then
    // auto-cancelled itself after 15s by checking activeStreamCounts -
    // which is only ever incremented by the abandoned streamFilePart()
    // path below, never by /video's real ChunkBridge path. So it always
    // downloaded far more than needed and then almost always cancelled
    // itself regardless of whether playback had started. Removed - see
    // LocalStreamServer.serveWarmup()/startHeadTailPrefetch(), which now
    // warms the SAME ChunkBridge /video reads from instead.

    /**
     * Streams targeted byte ranges on-demand natively using TDLib.
     * Uses reflection to bypass Kotlin package naming collisions and length=0 to return chunks instantly.
     */
    suspend fun streamFilePart(
        chatId: Long,
        messageId: Long,
        startOffset: Long,
        length: Long,
        outputStream: OutputStream
    ) {
        val (tdFile, _) = getMessageFile(chatId, messageId)
        val c = rawClient()
        val fileId = tdFile.id
        val totalSize = tdFile.size.toLong()

        // Track that a player has opened this socket
        activeStreamCounts.compute(fileId) { _, current -> (current ?: 0) + 1 }

        try {
            // Force TDLib to fetch the exact requested offset immediately
            c.send(TdApi.DownloadFile(fileId, 32, startOffset, 0, false)) {}

            var currentOffset = startOffset
            var remaining = length
            var idleWaitMs = 0

            while (remaining > 0 && currentOffset < totalSize) {
                
                // Passing '0' tells TDLib: "Return ANY bytes currently available at currentOffset immediately"
                val partData = suspendCancellableCoroutine<ByteArray?> { cont ->
                    c.send(TdApi.ReadFilePart(fileId, currentOffset, 0)) { res ->
                        try {
                            // Reflection perfectly avoids the "Unresolved reference: FilePart" compiler error
                            if (res != null && res.javaClass.simpleName == "FilePart") {
                                val data = res.javaClass.getField("data").get(res) as ByteArray
                                cont.resume(data)
                            } else {
                                cont.resume(null)
                            }
                        } catch (e: Exception) {
                            cont.resume(null)
                        }
                    }
                }

                if (partData != null && partData.isNotEmpty()) {
                    // Cap the write size so we don't accidentally send more bytes than requested
                    val bytesToWrite = minOf(partData.size.toLong(), remaining).toInt()
                    try {
                        outputStream.write(partData, 0, bytesToWrite)
                        outputStream.flush()
                    } catch (e: Exception) {
                        // ExoPlayer/VLC closed the socket
                        break
                    }
                    
                    currentOffset += bytesToWrite
                    remaining -= bytesToWrite
                    idleWaitMs = 0
                } else {
                    // Wait briefly for TDLib to fetch the chunk from Telegram servers
                    kotlinx.coroutines.delay(100)
                    idleWaitMs += 100

                    // Ping TDLib periodically to keep the connection prioritized
                    if (idleWaitMs % 2000 == 0) {
                        c.send(TdApi.DownloadFile(fileId, 32, currentOffset, 0, false)) {}
                    }

                    if (idleWaitMs > 30_000) {
                        FileLogger.error("Stream timeout at offset $currentOffset after 30s")
                        break
                    }
                }
            }
        } finally {
            // Player disconnected -> decrement the tracker cleanly
            activeStreamCounts.compute(fileId) { _, current ->
                val newCount = (current ?: 1) - 1
                if (newCount <= 0) {
                    c.send(TdApi.CancelDownloadFile(fileId, false)) {}
                    FileLogger.log("Stopped downloading fileId=$fileId to save mobile data")
                    null 
                } else {
                    newCount
                }
            }
        }
    }
}