package com.example.tgserver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
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

    fun rawClient(): Client = client ?: throw IllegalStateException("TelegramClient not initialized")

    fun init(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return

        val dbDir = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
        val filesDir = File(context.cacheDir, "tdlib_files").apply { mkdirs() }.absolutePath

        tdlibParams = TdApi.SetTdlibParameters().apply {
            databaseDirectory = dbDir
            filesDirectory = filesDir
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = "en"
            deviceModel = "Android"
            applicationVersion = "1.0"
        }

        Client.setLogVerbosityLevel(1)

        client = Client.create({ update ->
            handleUpdate(update)
        }, null, null)
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                when (update.authorizationState) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> {
                        tdlibParams?.let { client?.send(it, {}) }
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

    fun submitPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { res ->
            if (res is TdApi.Error) authState.value = AuthState.Error(res.message)
        }
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { res ->
            if (res is TdApi.Error) authState.value = AuthState.Error(res.message)
        }
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { res ->
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