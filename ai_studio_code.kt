package com.example.tgserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

class StreamService : Service() {

    companion object {
        const val PORT = 38471
        // BIND TO 0.0.0.0 SO IT ACCEPTS LAN (192.168.0.x) AND LOCALHOST (127.0.0.1)
        const val HOST = "0.0.0.0"
    }

    private var server: EmbeddedServer<*, *>? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startHttpServer()
    }

    private fun startHttpServer() {
        server = embeddedServer(CIO, port = PORT, host = HOST) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Range)
                allowHeader(HttpHeaders.AcceptRanges)
            }

            routing {
                // 1. Catalog Endpoint
                get("/catalog") {
                    val channelId = call.request.queryParameters["channel_id"]?.toLongOrNull() ?: -1004374443616L
                    val forceRefresh = call.request.queryParameters["refresh"] == "1"

                    // Retrieve cached/built catalog from your CatalogRepository / TDLib
                    val catalogJson = CatalogManager.getCatalogJson(channelId, forceRefresh)
                    
                    call.response.header(HttpHeaders.ContentType, "application/json; charset=utf-8")
                    call.respondText(catalogJson, ContentType.Application.Json)
                }

                // 2. Video Streaming Endpoint with 206 Partial Content & Range Support
                get("/video") {
                    val chatId = call.request.queryParameters["chat_id"]?.toLongOrNull()
                    val messageId = call.request.queryParameters["message_id"]?.toLongOrNull()

                    if (chatId == null || messageId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Missing chat_id or message_id")
                        return@get
                    }

                    // Get the downloaded/downloading file path from TDLib
                    val file = VideoFileManager.getVideoFile(chatId, messageId)
                    if (file == null || !file.exists()) {
                        call.respond(HttpStatusCode.NotFound, "Video file not ready or not found")
                        return@get
                    }

                    val fileLength = file.length()
                    val rangeHeader = call.request.headers[HttpHeaders.Range]

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        val ranges = rangeHeader.removePrefix("bytes=").split("-")
                        val start = ranges[0].toLongOrNull() ?: 0L
                        val end = if (ranges.size > 1 && ranges[1].isNotBlank()) {
                            ranges[1].toLongOrNull() ?: (fileLength - 1)
                        } else {
                            fileLength - 1
                        }

                        val contentLength = (end - start) + 1

                        call.response.status(HttpStatusCode.PartialContent)
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileLength")
                        call.response.header(HttpHeaders.ContentType, "video/mp4")
                        call.response.header(HttpHeaders.ContentLength, contentLength.toString())

                        call.respondOutputStream(ContentType.parse("video/mp4"), HttpStatusCode.PartialContent) {
                            RandomAccessFile(file, "r").use { raf ->
                                raf.seek(start)
                                val buffer = ByteArray(64 * 1024)
                                var bytesToRead = contentLength
                                while (bytesToRead > 0) {
                                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), bytesToRead).toInt())
                                    if (read == -1) break
                                    write(buffer, 0, read)
                                    bytesToRead -= read
                                }
                            }
                        }
                    } else {
                        // Full Content (Status 200)
                        call.response.status(HttpStatusCode.OK)
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentLength, fileLength.toString())
                        call.response.header(HttpHeaders.ContentType, "video/mp4")
                        call.respondFile(file)
                    }
                }
            }
        }.start(wait = false)
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
        server?.stop(1000, 2000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}