package com.example.tgserver

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
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

    // Listeners registered per specific fileId (used by ChunkBridge.kt)
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

    /**
     * Warmed up for the active file. Cancels any old lingering background downloads
     * so mobile data is not wasted on unplayed files.
     */
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
                    // Cancel previous background download if it's a different file
                    val prevFileId = activeDownloadingFileId
                    if (prevFileId != 0 && prevFileId != file.id) {
                        c.send(TdApi.CancelDownloadFile(prevFileId, false)) {}
                        FileLogger.log("Stopped background download for previous fileId=$prevFileId to save data")
                    }

                    activeDownloadingFileId = file.id
                    // Start download from 0 with max priority 32
                    c.send(TdApi.DownloadFile(file.id, 32, 0, 0, false)) {
                        FileLogger.log("Warmup active for fileId=${file.id} (chatId=$chatId, msgId=$messageId)")
                    }
                }
            }
        }
    }

    /**
     * Streams targeted byte ranges on-demand.
     * Starts playback immediately as soon as 512 KB is downloaded.
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

        // 1. Stop background download of any other movie to give 100% bandwidth to this stream
        if (activeDownloadingFileId != 0 && activeDownloadingFileId != fileId) {
            c.send(TdApi.CancelDownloadFile(activeDownloadingFileId, false)) {}
        }
        activeDownloadingFileId = fileId

        // 2. ALWAYS start from 0 with priority 32 so TDLib assigns the local file path immediately
        if (!tdFile.local.isDownloadingCompleted) {
            c.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) {}
        }

        // 3. Resolve the local file path
        var localPath = tdFile.local.path
        var waited = 0
        while (localPath.isBlank() && waited < 60) {
            kotlinx.coroutines.delay(100)
            val updated = suspendCancellableCoroutine<TdApi.File> { cont ->
                c.send(TdApi.GetFile(fileId)) { res ->
                    if (res is TdApi.File) cont.resume(res)
                    else cont.resume(tdFile)
                }
            }
            localPath = updated.local.path
            waited++
        }

        if (localPath.isBlank()) {
            FileLogger.error("Failed to allocate local path for fileId=$fileId")
            return
        }

        val file = File(localPath)

        // 4. TAIL-PROBE BYPASS: If player asks for the last 2 MB of an unfinished file,
        // return immediately so ExoPlayer/VLC proceeds directly to streaming from byte 0.
        if (startOffset > 0 && startOffset >= (totalSize - 3 * 1024 * 1024L)) {
            val currentDiskLen = file.length()
            if (currentDiskLen < startOffset && !tdFile.local.isDownloadingCompleted) {
                FileLogger.log("Tail probe at offset $startOffset bypassed (disk size: $currentDiskLen) for instant start")
                return
            }
        }

        var currentOffset = startOffset
        var remaining = length
        val buffer = ByteArray(256 * 1024) // 256 KB chunks

        RandomAccessFile(file, "r").use { raf ->
            var idleWaitMs = 0
            while (remaining > 0) {
                val fileLength = file.length()
                if (fileLength > currentOffset) {
                    raf.seek(currentOffset)
                    val toRead = minOf(buffer.size.toLong(), remaining, fileLength - currentOffset).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                        outputStream.flush()
                        currentOffset += read
                        remaining -= read
                        idleWaitMs = 0
                        continue
                    }
                }

                // If download finished and we reached EOF, finish cleanly
                if (tdFile.local.isDownloadingCompleted && fileLength <= currentOffset) {
                    break
                }

                // Wait 20 ms for next packets from TDLib
                kotlinx.coroutines.delay(20)
                idleWaitMs += 20

                // Ping TDLib download if idle for 4 seconds
                if (idleWaitMs % 4000 == 0 && !tdFile.local.isDownloadingCompleted) {
                    c.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) {}
                }

                // 60-second total timeout
                if (idleWaitMs > 60_000) {
                    FileLogger.error("Stream idle timeout at offset $currentOffset after 60s")
                    break
                }
            }
        }
    }
}