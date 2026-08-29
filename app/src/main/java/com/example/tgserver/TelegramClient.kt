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

        // 1. Explicitly load native JNI library safely
        try {
            System.loadLibrary("tdjni")
            FileLogger.log("Successfully loaded libtdjni.so")
        } catch (t: Throwable) {
            FileLogger.error("Failed to load libtdjni.so", t)
        }

        val dbDir = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
        val filesDir = File(context.cacheDir, "tdlib_files").apply { mkdirs() }.absolutePath

        // 2. Fully populate ALL required string and boolean fields to prevent native SIGSEGV
        tdlibParams = TdApi.SetTdlibParameters().apply {
            this.databaseDirectory = dbDir
            this.filesDirectory = filesDir
            this.useMessageDatabase = true
            this.useSecretChats = false
            this.useFileDatabase = true
            this.useChatInfoDatabase = true
            this.apiId = apiId
            this.apiHash = apiHash
            this.systemLanguageCode = "en"
            this.deviceModel = if (Build.MODEL.isNullOrBlank()) "Android" else Build.MODEL
            this.systemVersion = if (Build.VERSION.RELEASE.isNullOrBlank()) "10.0" else Build.VERSION.RELEASE
            this.applicationVersion = "1.0"
            this.enableStorageOptimizer = true
            this.ignoreFileNames = false
        }

        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Throwable) {
            FileLogger.error("SetLogVerbosityLevel error", e)
        }

        // 3. Create TDLib client with full error handlers
        client = Client.create({ update ->
            handleUpdate(update)
        }, { updateError ->
            FileLogger.error("TDLib update error: ${updateError?.message}")
        }, { defaultError ->
            FileLogger.error("TDLib default exception: ${defaultError?.message}")
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
                when (val auth = update.authorizationState) {
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
                    is TdApi.AuthorizationStateWaitEncryptionKey -> {
                        sendSafe(TdApi.CheckDatabaseEncryptionKey()) { res ->
                            if (res is TdApi.Error) {
                                FileLogger.error("CheckDatabaseEncryptionKey error: ${res.message}")
                                authState.value = AuthState.Error(res.message)
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

    private fun sendSafe(query: TdApi.Function, handler: Client.ResultHandler) {
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

    suspend fun streamFilePart(
        chatId: Long,
        messageId: Long,
        startOffset: Long,
        length: Long,
        outputStream: OutputStream
    ) {
        val (tdFile, _) = getMessageFile(chatId, messageId)
        val c = rawClient()

        if (!tdFile.local.isDownloadingCompleted) {
            suspendCancellableCoroutine<Unit> { cont ->
                c.send(TdApi.DownloadFile(tdFile.id, 32, 0, 0, true)) { res ->
                    cont.resume(Unit)
                }
            }
        }

        var localPath = tdFile.local.path
        var waited = 0
        while (localPath.isBlank() && waited < 10) {
            kotlinx.coroutines.delay(500)
            val updated = suspendCancellableCoroutine<TdApi.File> { cont ->
                c.send(TdApi.GetFile(tdFile.id)) { res ->
                    if (res is TdApi.File) cont.resume(res)
                    else cont.resume(tdFile)
                }
            }
            localPath = updated.local.path
            waited++
        }

        val file = File(localPath)
        if (!file.exists()) {
            throw IllegalStateException("File download not started yet or local path empty")
        }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) {
                    kotlinx.coroutines.delay(200)
                    continue
                }
                outputStream.write(buffer, 0, read)
                outputStream.flush()
                remaining -= read
            }
        }
    }
}