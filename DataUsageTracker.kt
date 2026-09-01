package com.example.tgserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks VIDEO streaming data usage only - not TMDB/Gemini/catalog
 * traffic, which is small JSON and not worth capping separately.
 *
 * Bytes are counted in ChunkBridge, right when it issues a genuinely NEW
 * TDLib download for a byte range it hasn't fetched before in that
 * ChunkBridge instance's lifetime - re-reading already-downloaded bytes
 * (rewinding, replaying within the same session) never gets counted
 * twice.
 *
 * One known, deliberate imprecision, worth knowing about: if a file was
 * already fully downloaded in a PREVIOUS app session (rewatching
 * something after an app restart), TDLib itself won't re-download it over
 * the network - but a fresh ChunkBridge instance has no cheap way to know
 * that in advance, and will still count it as if newly downloaded. For a
 * soft usage cap this is an acceptable approximation, not a precise
 * billing meter. Getting this byte-exact would mean querying TDLib's
 * current downloadedPrefixSize before every single download and diffing
 * it against the size after - doable, but adds a round-trip to a
 * streaming path that has explicitly been hardened to be reliable, for a
 * cap that's meant to be a soft guardrail rather than an exact meter.
 */
object DataUsageTracker {

    private const val PREFS = "tgserver_data_usage"
    private const val CHANNEL_ID = "tg_stream_data_channel"
    private const val NOTIFICATION_ID = 2

    enum class Mode { BLOCK, NOTIFY_ONLY }

    @Volatile
    private var appContext: Context? = null

    private var prefs: SharedPreferences? = null

    private val usedBytes = AtomicLong(0L)

    @Volatile
    private var capBytes: Long = 0L

    @Volatile
    private var mode: Mode = Mode.BLOCK

    @Volatile
    private var hasNotifiedThisPeriod = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        usedBytes.set(p.getLong("used_bytes", 0L))
        capBytes = p.getLong("cap_bytes", 0L)
        mode = if (p.getString("mode", "BLOCK") == "NOTIFY_ONLY") Mode.NOTIFY_ONLY else Mode.BLOCK
        hasNotifiedThisPeriod = p.getBoolean("has_notified", false)
        createChannel()
        FileLogger.log("DataUsageTracker: init - used=${usedBytes.get()} cap=$capBytes mode=$mode")
    }

    fun getUsedBytes(): Long = usedBytes.get()
    fun getCapBytes(): Long = capBytes
    fun getMode(): Mode = mode

    /** capMb <= 0 means unlimited. */
    fun setCapMb(capMb: Long) {
        capBytes = if (capMb <= 0) 0L else capMb * 1024 * 1024
        hasNotifiedThisPeriod = false
        prefs?.edit()
            ?.putLong("cap_bytes", capBytes)
            ?.putBoolean("has_notified", false)
            ?.apply()
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        prefs?.edit()?.putString("mode", newMode.name)?.apply()
    }

    fun resetUsage() {
        usedBytes.set(0L)
        hasNotifiedThisPeriod = false
        prefs?.edit()
            ?.putLong("used_bytes", 0L)
            ?.putBoolean("has_notified", false)
            ?.apply()
        FileLogger.log("DataUsageTracker: usage counter reset")
    }

    /**
     * True only when a cap is actually set, it's been reached, AND the
     * mode is BLOCK. Callers (ChunkBridge/LocalStreamServer) should
     * refuse new downloads when this is true - already-downloaded bytes
     * should still be servable regardless, since serving them costs no
     * additional data.
     */
    fun isBlocked(): Boolean = capBytes > 0 && usedBytes.get() >= capBytes && mode == Mode.BLOCK

    fun isOverLimit(): Boolean = capBytes > 0 && usedBytes.get() >= capBytes

    fun addVideoBytes(bytes: Long) {
        if (bytes <= 0) return
        val newTotal = usedBytes.addAndGet(bytes)
        prefs?.edit()?.putLong("used_bytes", newTotal)?.apply()

        if (capBytes > 0 && newTotal >= capBytes && !hasNotifiedThisPeriod) {
            hasNotifiedThisPeriod = true
            prefs?.edit()?.putBoolean("has_notified", true)?.apply()
            notifyLimitReached()
        }
    }

    private fun notifyLimitReached() {
        val ctx = appContext ?: return
        FileLogger.log(
            "DataUsageTracker: limit reached (used=${usedBytes.get()} cap=$capBytes mode=$mode) - sending notification"
        )

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            ctx,
            3,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags
        )

        val text = when (mode) {
            Mode.BLOCK -> "New video downloads are paused until you raise or reset the limit in Settings."
            Mode.NOTIFY_ONLY -> "Streaming keeps going, but you've crossed the limit you set."
        }

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("TeleStream data limit reached")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ctx = appContext ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, "TeleStream Data Limit", NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
