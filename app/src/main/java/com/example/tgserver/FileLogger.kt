package com.example.tgserver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes debug/log output both to a file on disk (for "Share log file")
 * and to an in-memory ring buffer exposed as a [StateFlow], so the
 * in-app Logs tab can show a live-updating view of what's happening -
 * TMDB/Gemini lookups, Telegram fetches, HTTP hits on the local server,
 * errors, etc.
 */
object FileLogger {
    private const val TAG = "TeleStream"

    // Cap how many lines we keep in memory so a long-running server
    // process doesn't slowly leak memory just from logging.
    private const val MAX_LIVE_LINES = 2000

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val _liveLines = MutableStateFlow<List<String>>(emptyList())

    /** Live tail of recent log lines, newest last. Observe this from the Logs tab. */
    val liveLines: StateFlow<List<String>> = _liveLines.asStateFlow()

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "telestream_debug.log")
        log("=== Logger initialized ===")

        // Catch and log every unhandled crash automatically
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("FATAL UNCAUGHT EXCEPTION on thread [${thread.name}]", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getLogFile(): File? = logFile

    @Synchronized
    fun log(message: String) {
        Log.d(TAG, message)
        append("[INFO] $message")
    }

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val trace = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        append("[ERROR] $message$trace")
    }

    /**
     * Clears both the live in-memory buffer (what the Logs tab shows) and
     * the on-disk log file, so "Clear" in the Logs tab actually starts
     * from a clean slate rather than just hiding old lines.
     */
    @Synchronized
    fun clear() {
        _liveLines.value = emptyList()
        val target = logFile ?: return
        try {
            FileWriter(target, false).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.print("")
                }
            }
        } catch (ignored: Exception) {
        }
        log("=== Logs cleared ===")
    }

    private fun append(line: String) {
        val stamped = "${dateFormat.format(Date())} $line"

        val updated = _liveLines.value + stamped
        _liveLines.value =
            if (updated.size > MAX_LIVE_LINES) {
                updated.takeLast(MAX_LIVE_LINES)
            } else {
                updated
            }

        val target = logFile ?: return
        try {
            FileWriter(target, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println(stamped)
                }
            }
        } catch (ignored: Exception) {
        }
    }
}
