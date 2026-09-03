package com.example.tgserver

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

/**
 * Caps how much disk space TDLib's downloaded video chunks are allowed to
 * occupy, using TDLib's own OptimizeStorage - which removes
 * least-recently-used files first and respects an "immunity" window so
 * nothing actively being watched (or just finished buffering) gets pruned
 * out from under you mid-playback.
 *
 * This is a DISK usage cap - separate and independent from
 * DataUsageTracker, which caps NETWORK bytes pulled over time. This one is
 * about how much stays on the device long-term; that one is about how much
 * ever gets downloaded in the first place. A file can count against the
 * network cap once when it's downloaded, then get cleaned up here later
 * without affecting that counter at all.
 */
object LocalStorageManager {
    private const val PREFS = "tgserver_prefs"
    private const val KEY_CAP_MB = "local_storage_cap_mb"
    private const val DEFAULT_CAP_MB = 1536L // 1.5 GB

    // Files touched within this window are never pruned, regardless of
    // the configured cap, so a cleanup pass can't yank buffered-ahead data
    // out from under something that's actively playing right now.
    private const val IMMUNITY_SECONDS = 60

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getCapMb(): Long = prefs()?.getLong(KEY_CAP_MB, DEFAULT_CAP_MB) ?: DEFAULT_CAP_MB

    /** 0 means unlimited - no cap enforced. */
    fun getCapBytes(): Long = getCapMb() * 1024 * 1024

    fun setCapMb(mb: Long) {
        prefs()?.edit()?.putLong(KEY_CAP_MB, mb.coerceAtLeast(0))?.apply()
    }

    /**
     * Prunes downloaded files down to the configured cap, right now.
     * Fire-and-forget - safe to call from a button tap or a periodic
     * timer alike. A no-op if the cap is set to unlimited (0) or if
     * TDLib isn't ready yet (e.g. called before login finishes).
     */
    fun enforceNow() {
        val capBytes = getCapBytes()
        if (capBytes <= 0) return

        val client = try {
            TelegramClient.rawClient()
        } catch (e: IllegalStateException) {
            FileLogger.log("LocalStorageManager: enforceNow skipped - TDLib not ready yet")
            return
        }

        client.send(
            TdApi.OptimizeStorage(
                capBytes,
                0,
                -1,
                IMMUNITY_SECONDS,
                emptyArray(),
                LongArray(0),
                LongArray(0),
                false,
                0
            )
        ) { res ->
            when (res) {
                is TdApi.StorageStatistics -> {
                    FileLogger.log(
                        "LocalStorageManager: enforced cap=${capBytes / (1024 * 1024)}MB - " +
                            "now using ${res.size / (1024 * 1024)}MB across ${res.count} file(s)"
                    )
                }
                is TdApi.Error -> {
                    FileLogger.error("LocalStorageManager: enforceNow failed: ${res.message}", RuntimeException(res.message))
                }
                else -> {}
            }
        }
    }

    /** Current total bytes TDLib has stored on disk for downloaded files. */
    suspend fun getCurrentUsageBytes(): Long {
        val client = try {
            TelegramClient.rawClient()
        } catch (e: IllegalStateException) {
            return 0L
        }

        return suspendCancellableCoroutine { cont ->
            client.send(TdApi.GetStorageStatistics(0)) { res ->
                if (res is TdApi.StorageStatistics) {
                    cont.resume(res.size)
                } else {
                    cont.resume(0L)
                }
            }
        }
    }
}