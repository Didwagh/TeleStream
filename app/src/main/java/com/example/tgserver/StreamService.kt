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
import kotlinx.coroutines.launch

/**
 * Just starts/stops the LocalStreamServer (NanoHTTPD) as a foreground
 * service and keeps it alive. All actual HTTP handling - /catalog,
 * /search, /warmup, /video, /prefetch - lives in LocalStreamServer, which
 * is the version of this server that has actually been proven to stream
 * reliably (it reads bytes off disk via ChunkBridge once TDLib's
 * DownloadFile has confirmed them present, rather than trying to pull
 * arbitrary byte ranges out of TDLib on demand).
 */
class StreamService : Service() {

    companion object {
        const val PORT = 38471
        private const val CHANNEL_ID = "tg_stream_channel"
        private const val NOTIFICATION_ID = 1

        var isRunning = false
            private set
    }

    private var server: LocalStreamServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Idempotent - MainActivity also calls this, but the service can
        // in principle start first, and ChannelCatalogBuilder needs a
        // Context to read/write its on-disk catalog cache.
        ChannelCatalogBuilder.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (server == null) {
            server = LocalStreamServer(PORT).also { it.start(10_000, false) }
            isRunning = true
            warmUpCatalog()
        }
        return START_STICKY
    }

    /**
     * Syncs the catalog as soon as the server starts, so it's already warm
     * by the time you open CloudStream - rather than waiting for a manual
     * tap. This runs in the background and does not block the server from
     * serving /video requests in the meantime.
     *
     * This is now an INCREMENTAL sync (see ChannelCatalogBuilder.sync()):
     * on the first run ever it still walks the channel and resolves
     * TMDB/Gemini for everything, same as before, but every run after that
     * loads the persisted catalog from disk instantly and only fetches +
     * classifies whatever's newer than the last sync - no full re-walk, no
     * re-spent TMDB/Gemini calls on titles already resolved.
     */
    private fun warmUpCatalog() {
        val prefs = getSharedPreferences("tgserver_prefs", MODE_PRIVATE)
        val channelId = prefs.getLong("channel_id", 0L)
        if (channelId == 0L) {
            FileLogger.log("warmUpCatalog: no channel_id saved yet, skipping")
            return
        }
        serviceScope.launch {
            try {
                FileLogger.log("warmUpCatalog: syncing catalog for channelId=$channelId on server start")
                val items = ChannelCatalogBuilder.getCatalog(channelId, forceRefresh = true)
                FileLogger.log("warmUpCatalog: done, ${items.size} item(s) ready")
            } catch (e: Exception) {
                FileLogger.error("warmUpCatalog failed", e)
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Telegram Stream Server", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Stream Server")
            .setContentText("Running on port $PORT — keep this running while using CloudStream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}