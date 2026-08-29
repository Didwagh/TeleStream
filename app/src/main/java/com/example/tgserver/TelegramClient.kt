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

    // Tracks the single active file being downloaded/streamed to prevent background data drain
    @Volatile private var activeDownloadingFileId: Int = 0

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

    fun warmupFile(chatId: Long, messageId: Long) {
        val c = rawClient()
        c.send(TdApi.GetMessage(chatId, messageId)) { msgRes ->
            if (msgRes is TdApi.Message) {
                val file = when (val content = msgRes.content) {
                    is TdApi.MessageVideo -> content.video.video
                    is TdApi.MessageDocument -> content.document.document
                    else -> null
                }
                if (file != null && !file.local.isDownloadingCompleted) {
                    val prevFileId = activeDownloadingFileId
                    if (prevFileId != 0 && prevFileId != file.id) {
                        c.send(TdApi.CancelDownloadFile(prevFileId, false)) {}
                        FileLogger.log("Stopped background download for previous fileId=$prevFileId to save data")
                    }

                    activeDownloadingFileId = file.id
                    c.send(TdApi.DownloadFile(file.id, 32, 0, 0, false)) {
                        FileLogger.log("Warmup active for fileId=${file.id} (chatId=$chatId, msgId=$messageId)")
                    }
                }
            }
        }
    }

    /**
     * Streams targeted byte ranges on-demand using TDLib's internal ReadFilePart engine.
     * This avoids all sparse file OS issues and flawlessly handles MP4 tail probes.
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

        // Cancel previous background streams to dedicate bandwidth to the active stream
        if (activeDownloadingFileId != 0 && activeDownloadingFileId != fileId) {
            c.send(TdApi.CancelDownloadFile(activeDownloadingFileId, false)) {}
        }
        activeDownloadingFileId = fileId

        // Force TDLib to prioritize downloading from the EXACT requested offset
        c.send(TdApi.DownloadFile(fileId, 32, startOffset, 0, false)) {}

        var currentOffset = startOffset
        var remaining = length
        val chunkSize = 256 * 1024L // 256 KB chunks

        var idleWaitMs = 0
        while (remaining > 0) {
            val countToRead = minOf(chunkSize, remaining).toLong()

            // Ask TDLib for the byte chunk. It only returns data if it's actually downloaded.
            val partData = suspendCancellableCoroutine<ByteArray?> { cont ->
                c.send(TdApi.ReadFilePart(fileId, currentOffset, countToRead)) { res ->
                    if (res is TdApi.FilePart && res.data.isNotEmpty()) {
                        cont.resume(res.data)
                    } else {
                        cont.resume(null)
                    }
                }
            }

            if (partData != null && partData.isNotEmpty()) {
                try {
                    outputStream.write(partData)
                    outputStream.flush()
                } catch (e: Exception) {
                    // ExoPlayer/VLC closed the connection (e.g., they finished reading the header and disconnected)
                    break
                }
                
                currentOffset += partData.size
                remaining -= partData.size
                idleWaitMs = 0
            } else {
                // The chunk isn't downloaded yet. Wait 50ms and try again.
                kotlinx.coroutines.delay(50)
                idleWaitMs += 50

                // Ping TDLib every 3 seconds to ensure the download from currentOffset hasn't stalled
                if (idleWaitMs % 3000 == 0) {
                    c.send(TdApi.DownloadFile(fileId, 32, currentOffset, 0, false)) {}
                }

                // Total timeout of 30 seconds
                if (idleWaitMs > 30_000) {
                    FileLogger.error("Stream idle timeout at offset $currentOffset after 30s")
                    break
                }
            }
        }
    }
}