package com.example.tgserver

import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.td.libcore.telegram.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TelegramFileResolver {

    suspend fun resolve(chatId: Long, messageId: Long): TdApi.File {
        val client = TelegramClient.rawClient()

        val message = suspendCancellableCoroutine<TdApi.Message> { cont ->
            client.send(TdApi.GetMessage(chatId, messageId)) { result ->
                if (result is TdApi.Message) cont.resume(result)
                else cont.resumeWithException(RuntimeException("GetMessage failed: $result"))
            }
        }

        val fileId = when (val content = message.content) {
            is TdApi.MessageVideo -> content.video.video.id
            is TdApi.MessageDocument -> content.document.document.id
            else -> throw IllegalStateException("Message has no streamable video/document")
        }

        return suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetFile(fileId)) { result ->
                if (result is TdApi.File) cont.resume(result)
                else cont.resumeWithException(RuntimeException("GetFile failed: $result"))
            }
        }
    }
}