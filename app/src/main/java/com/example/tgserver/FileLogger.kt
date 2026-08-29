package com.example.tgserver

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val TAG = "TeleStream"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "telestream_debug.log")
        log("=== Logger initialized ===")
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

    private fun append(line: String) {
        val target = logFile ?: return
        try {
            FileWriter(target, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("${dateFormat.format(Date())} $line")
                }
            }
        } catch (ignored: Exception) {}
    }
}