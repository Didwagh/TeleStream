package com.example.tgserver

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes logs to a plain text file so they're accessible via a Share
  * button, with no adb/logcat/PC required. Also mirrors to Logcat for
   * anyone who does have adb.
    */
    object FileLogger {
        private const val TAG = "TGServer"
            private lateinit var logFile: File
                private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

                    fun init(context: Context) {
                            logFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "tgserver_log.txt")
                                    log("=== App started, log file initialized at ${logFile.absolutePath} ===")
                                        }

                                            fun log(message: String) {
                                                    Log.d(TAG, message)
                                                            appendLine("D: $message")
                                                                }

                                                                    fun error(message: String, throwable: Throwable? = null) {
                                                                            Log.e(TAG, message, throwable)
                                                                                    val stack = throwable?.stackTraceToString() ?: ""
                                                                                            appendLine("E: $message ${if (stack.isNotEmpty()) "\n$stack" else ""}")
                                                                                                }

                                                                                                    fun getLogFile(): File? = if (::logFile.isInitialized) logFile else null

                                                                                                        private fun appendLine(line: String) {
                                                                                                                try {
                                                                                                                            if (!::logFile.isInitialized) return
                                                                                                                                        logFile.appendText("${timeFormat.format(Date())}  $line\n")
                                                                                                                                                } catch (e: Exception) {
                                                                                                                                                            Log.e(TAG, "Failed to write to log file", e)
                                                                                                                                                                    }
                                                                                                                                                                        }
                                                                                                                                                                        }
                                                                                                                                                                        