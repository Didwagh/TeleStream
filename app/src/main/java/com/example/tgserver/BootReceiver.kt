package com.example.tgserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Automatically starts StreamService upon device boot if credentials are configured
 * and the user hasn't disabled auto-start in settings.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        FileLogger.init(context.applicationContext)
        FileLogger.log("BootReceiver: received boot completed broadcast ($action)")

        val prefs = context.getSharedPreferences("tgserver_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("pref_auto_start_on_boot", true)
        if (!autoStart) {
            FileLogger.log("BootReceiver: auto-start disabled by user setting")
            return
        }

        val apiId = prefs.getInt("api_id", 0)
        val apiHash = prefs.getString("api_hash", "") ?: ""
        val channelId = prefs.getLong("channel_id", 0L)

        if (apiId == 0 || apiHash.isEmpty() || channelId == 0L) {
            FileLogger.log("BootReceiver: credentials not configured yet, skipping auto-start")
            return
        }

        FileLogger.log("BootReceiver: starting StreamService on boot")
        try {
            val serviceIntent = Intent(context, StreamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            FileLogger.error("BootReceiver: failed to start StreamService", t)
        }
    }
}
