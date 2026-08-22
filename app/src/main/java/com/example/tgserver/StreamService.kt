package com.example.tgserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class StreamService : Service() {

    companion object {
        const val PORT = 38471
        var isRunning: Boolean = false
    }

    private var server: HttpServer? = null
    private val executor = Executors.newFixedThreadPool(8)

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startServer()
    }

    private fun startServer() {
        try {
            // Bind to 0.0.0.0 so both 127.0.0.1 and 192.168.0.194 can connect
            server = HttpServer.create(InetSocketAddress("0.0.0.0", PORT), 0).apply {
                executor = this@StreamService.executor

                createContext("/catalog") { exchange ->
                    handleCatalog(exchange)
                }

                createContext("/video") { exchange ->
                    handleVideo(exchange)
                }

                start()
            }
            isRunning = true
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
        }
    }

    private fun handleCatalog(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }

        val channelId = params["channel_id"]?.toLongOrNull() ?: -1004374443616L
        val forceRefresh = params["refresh"] == "1"

        // Use your existing ChannelCatalogBuilder / cache
        val catalogJson = try {
            CatalogManager.getCatalogJson(channelId, forceRefresh)
        } catch (e: Exception) {
            "[]"
        }

        val bytes = catalogJson.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleVideo(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val params = query.split("&").associate {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }

        val chatId = params["chat_id"]?.toLongOrNull()
        val messageId = params["message_id"]?.toLongOrNull()

        if (chatId == null || messageId == null) {
            exchange.sendResponseHeaders(400, -1)
            return
        }

        val file = VideoFileManager.getVideoFile(chatId, messageId)
        if (file == null || !file.exists()) {
            exchange.sendResponseHeaders(404, -1)
            return
        }

        val fileLength = file.length()
        val rangeHeader = exchange.requestHeaders.getFirst("Range")

        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("Content-Type", "video/mp4")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val ranges = rangeHeader.removePrefix("bytes=").split("-")
            val start = ranges[0].toLongOrNull() ?: 0L
            val end = if (ranges.size > 1 && ranges[1].isNotBlank()) {
                ranges[1].toLongOrNull() ?: (fileLength - 1)
            } else {
                fileLength - 1
            }

            val contentLength = (end - start) + 1
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$fileLength")
            exchange.sendResponseHeaders(206, contentLength)

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(64 * 1024)
                var bytesRemaining = contentLength
                exchange.responseBody.use { out ->
                    while (bytesRemaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                }
            }
        } else {
            exchange.sendResponseHeaders(200, fileLength)
            FileInputStream(file).use { input ->
                exchange.responseBody.use { out ->
                    input.copyTo(out)
                }
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "tgserver_stream_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "TeleStream Server", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TeleStream Service Running")
            .setContentText("Listening on 0.0.0.0:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        isRunning = false
        server?.stop(0)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}