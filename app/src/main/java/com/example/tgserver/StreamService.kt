package com.example.tgserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Just starts/stops the LocalStreamServer (NanoHTTPD) as a foreground
 * service and keeps it alive. All actual HTTP handling - /catalog,
 * /search, /warmup, /video, /prefetch - lives in LocalStreamServer, which
 * is the version of this server that has actually been proven to stream
 * reliably (it reads bytes off disk via ChunkBridge once TDLib's
 * DownloadFile has confirmed them present, rather than trying to pull
 * arbitrary byte ranges out of TDLib on demand).
 *
 * Notification: tapping it opens MainActivity. It has two action buttons -
 * "Stop Server" and "Refresh Catalog" - both routed back through this same
 * Service via onStartCommand's intent.action, so no separate
 * BroadcastReceiver is needed.
 *
 * Staying alive when the app is swiped from Recents: android:stopWithTask
 * is explicitly set to "false" on the <service> in the manifest (Android's
 * default for a plain foreground service already behaves this way, but
 * leaving it implicit invites exactly the kind of "why did it die" confusion
 * this was built to avoid). onTaskRemoved() is also overridden below purely
 * to make that intent unmistakable in the logs, and to defensively re-post
 * the foreground notification in case a manufacturer's battery manager
 * knocks it down anyway.
 *
 * One honest limit: this covers everything the Android framework itself
 * guarantees. Aggressive OEM battery managers (MIUI "no restrictions",
 * Samsung "put unused apps to sleep", etc.) can still kill any app's
 * background/foreground service regardless of what the app declares - that
 * requires a manual allowance in the phone's own battery settings, no code
 * change here can override it.
 */
class StreamService : Service() {

    companion object {
        const val PORT = 38471
        private const val CHANNEL_ID = "tg_stream_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.example.tgserver.action.STOP"
        const val ACTION_REFRESH_CATALOG = "com.example.tgserver.action.REFRESH_CATALOG"

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
        DataUsageTracker.init(applicationContext)
        LocalStorageManager.init(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                FileLogger.log("StreamService: stop requested from notification")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_CATALOG -> {
                // Must still call startForeground() here - this is a fresh
                // onStartCommand delivery, and the system expects it
                // regardless of which action triggered it.
                startForeground(NOTIFICATION_ID, buildNotification())
                FileLogger.log("StreamService: catalog refresh requested from notification")
                triggerCatalogRefresh()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (server == null) {
            server = LocalStreamServer(PORT).also { it.start(10_000, false) }
            isRunning = true
            warmUpCatalog()
            startLocalStorageEnforcement()
        }
        return START_STICKY
    }

    /**
     * Enforces the configured local-storage cap once immediately, then
     * every 10 minutes for as long as the server keeps running - matches
     * what Settings tells you this does. TDLib's own OptimizeStorage
     * (used inside LocalStorageManager) removes least-recently-used files
     * first and skips anything downloaded in the last minute, so this
     * can't rip buffered-ahead data out from under something you're
     * actively watching.
     */
    private fun startLocalStorageEnforcement() {
        serviceScope.launch {
            while (isRunning) {
                LocalStorageManager.enforceNow()
                delay(10 * 60 * 1000L)
            }
        }
    }

    /**
     * Called when the user swipes the app away from the recent-apps list.
     * Deliberately does NOT stop the server - the whole point of this
     * being a foreground service is that it keeps running until you tap
     * "Stop Server" yourself. Re-posting the notification here is a cheap
     * defensive measure in case something along the way tried to tear it
     * down with the task.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLogger.log("StreamService: app removed from recent apps - server keeps running")
        if (isRunning) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
        super.onTaskRemoved(rootIntent)
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

    /** Same incremental sync as warmUpCatalog(), just triggered manually from the notification. */
    private fun triggerCatalogRefresh() {
        val prefs = getSharedPreferences("tgserver_prefs", MODE_PRIVATE)
        val channelId = prefs.getLong("channel_id", 0L)
        if (channelId == 0L) {
            FileLogger.log("triggerCatalogRefresh: no channel_id saved yet, skipping")
            return
        }
        serviceScope.launch {
            try {
                val items = ChannelCatalogBuilder.getCatalog(channelId, forceRefresh = true)
                FileLogger.log("triggerCatalogRefresh: done, ${items.size} item(s) ready")
            } catch (e: Exception) {
                FileLogger.error("triggerCatalogRefresh failed", e)
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
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // Tapping the notification body opens MainActivity.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP },
            pendingIntentFlags
        )

        val refreshIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, StreamService::class.java).apply { action = ACTION_REFRESH_CATALOG },
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Stream Server")
            .setContentText("Running on port $PORT — keep this running while using CloudStream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop Server", stopIntent)
            .addAction(0, "Refresh Catalog", refreshIntent)
            .build()
    }
}