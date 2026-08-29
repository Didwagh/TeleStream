package com.example.tgserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class StreamService : Service() {

    companion object {
        const val PORT = 38471
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "TeleStreamServiceChannel")
            .setContentTitle("TeleStream Companion")
            .setContentText("Local server running on port $PORT")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
        isRunning = true
        startHttpServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "TeleStreamServiceChannel",
                "TeleStream Background Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startHttpServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                FileLogger.log("StreamService: HTTP Server listening on port $PORT")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    serviceScope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                FileLogger.error("StreamService server exception", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val firstLine = input.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val pathWithQuery = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String?
            while (input.readLine().also { line = it } != null && line!!.isNotBlank()) {
                val colon = line!!.indexOf(":")
                if (colon != -1) {
                    headers[line!!.substring(0, colon).trim().lowercase()] = line!!.substring(colon + 1).trim()
                }
            }

            val out = s.getOutputStream()
            val uriPath = pathWithQuery.substringBefore("?")
            val queryString = pathWithQuery.substringAfter("?", "")
            val queryParams = parseQueryParams(queryString)

            when (uriPath) {
                "/catalog" -> handleCatalog(queryParams, out)
                "/search" -> handleSearch(queryParams, out)
                "/video" -> handleVideo(queryParams, headers, out)
                else -> {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    out.write(notFound.toByteArray())
                }
            }
        }
    }

    private suspend fun handleCatalog(params: Map<String, String>, out: OutputStream) {
        val chatId = params["channel_id"]?.toLongOrNull() ?: 0L
        val refresh = params["refresh"] == "1"
        try {
            val catalog = if (refresh) {
                ChannelCatalogBuilder.getCatalog(chatId, forceRefresh = true)
            } else {
                ChannelCatalogBuilder.peekCache(chatId) ?: ChannelCatalogBuilder.getCatalog(chatId, forceRefresh = false)
            }
            val json = ChannelCatalogBuilder.toJson(catalog)
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: ${json.toByteArray().size}\r\n\r\n$json"
            out.write(response.toByteArray())
            out.flush()
        } catch (e: Exception) {
            FileLogger.error("Error in /catalog", e)
            val err = "HTTP/1.1 500 Internal Error\r\n\r\n${e.message}"
            out.write(err.toByteArray())
        }
    }

    private suspend fun handleSearch(params: Map<String, String>, out: OutputStream) {
        val chatId = params["channel_id"]?.toLongOrNull() ?: 0L
        val query = params["query"]?.lowercase() ?: ""
        try {
            val catalog = ChannelCatalogBuilder.peekCache(chatId) ?: ChannelCatalogBuilder.getCatalog(chatId, false)
            val matched = catalog.filter {
                it.title.lowercase().contains(query) || (it.imdbId?.lowercase()?.contains(query) == true)
            }
            val json = ChannelCatalogBuilder.toJson(matched)
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: ${json.toByteArray().size}\r\n\r\n$json"
            out.write(response.toByteArray())
            out.flush()
        } catch (e: Exception) {
            val err = "HTTP/1.1 500 Internal Error\r\n\r\n${e.message}"
            out.write(err.toByteArray())
        }
    }

    private suspend fun handleVideo(params: Map<String, String>, headers: Map<String, String>, out: OutputStream) {
        val chatId = params["chat_id"]?.toLongOrNull() ?: return
        val messageId = params["message_id"]?.toLongOrNull() ?: return

        try {
            val (tdFile, fileName) = TelegramClient.getMessageFile(chatId, messageId)
            val totalSize = tdFile.size.toLong()

            val rangeHeader = headers["range"]
            var start = 0L
            var end = totalSize - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.removePrefix("bytes=").split("-")
                start = range[0].toLongOrNull() ?: 0L
                if (range.size > 1 && range[1].isNotBlank()) {
                    end = range[1].toLongOrNull() ?: (totalSize - 1)
                }
            }

            val length = (end - start) + 1
            val mime = if (fileName.endsWith(".mkv")) "video/x-matroska" else "video/mp4"

            val headerStr = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $start-$end/$totalSize\r\n" +
                    "Content-Length: $length\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"

            out.write(headerStr.toByteArray())
            out.flush()

            TelegramClient.streamFilePart(chatId, messageId, start, length, out)
        } catch (e: Exception) {
            FileLogger.error("handleVideo stream error", e)
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach { param ->
            val pair = param.split("=")
            if (pair.size == 2) {
                map[URLDecoder.decode(pair[0], "UTF-8")] = URLDecoder.decode(pair[1], "UTF-8")
            }
        }
        return map
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}