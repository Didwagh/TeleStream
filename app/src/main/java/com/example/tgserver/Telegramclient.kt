package com.example.tgserver

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File

sealed class AuthState {
    object Idle : AuthState()
    object WaitPhone : AuthState()
    object WaitCode : AuthState()
    object WaitPassword : AuthState()
    object Ready : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Singleton TDLib wrapper. Lives for the app process's lifetime.
 * Login happens once in MainActivity; StreamService (same process)
 * reuses this same object with no extra wiring needed.
 */
object TelegramClient {

    val authState = MutableStateFlow<AuthState>(AuthState.Idle)

    private var client: Client? = null

    private val fileListeners = HashMap<Int, MutableList<(TdApi.File) -> Unit>>()
    private val fileListenersLock = Any()

    fun init(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return

        val dbDir = File(context.filesDir, "tdlib").absolutePath

        client = Client.create({ update -> handleUpdate(update) }, null, null)

        val parameters = TdApi.TdlibParameters().apply {
            databaseDirectory = dbDir
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = "en"
            deviceModel = "Android"
            systemVersion = Build.VERSION.RELEASE ?: "Unknown"
            applicationVersion = "0.1"
            enableStorageOptimizer = true
        }

        client?.send(TdApi.SetTdlibParameters(parameters)) { }
    }

    fun submitPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { }
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { }
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { }
    }

    fun isReady(): Boolean = authState.value is AuthState.Ready

    fun rawClient(): Client = client ?: error("TelegramClient.init() not called yet")

    fun addFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        synchronized(fileListenersLock) {
            fileListeners.getOrPut(fileId) { mutableListOf() }.add(listener)
        }
    }

    fun removeFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        synchronized(fileListenersLock) {
            fileListeners[fileId]?.remove(listener)
        }
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(update.authorizationState)
            is TdApi.UpdateFile -> dispatchFileUpdate(update.file)
            else -> { /* ignore everything else */ }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> authState.value = AuthState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> authState.value = AuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> authState.value = AuthState.WaitPassword
            is TdApi.AuthorizationStateReady -> authState.value = AuthState.Ready
            is TdApi.AuthorizationStateClosed -> authState.value = AuthState.Error("Session closed")
            else -> { /* WaitTdlibParameters handled internally by TDLib after SetTdlibParameters */ }
        }
    }

    private fun dispatchFileUpdate(file: TdApi.File) {
        val listeners = synchronized(fileListenersLock) {
            fileListeners[file.id]?.toList() ?: emptyList()
        }
        listeners.forEach { it(file) }
    }
}