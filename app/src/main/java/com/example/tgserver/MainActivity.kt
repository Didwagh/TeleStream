package com.example.tgserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide look: near-black background, orange accent buttons. Everything
 * here is built with plain Views (no XML layouts, no Compose) to match how
 * the rest of this Activity was already written - see build.gradle.kts,
 * there's no Compose dependency wired up.
 */
private object Theme {
    val bg = Color.parseColor("#0D0D0D")
    val surface = Color.parseColor("#1B1B1B")
    val surfaceAlt = Color.parseColor("#242424")
    val accent = Color.parseColor("#FF7A1A")
    val accentPressedTint = Color.parseColor("#E86D14")
    val textPrimary = Color.parseColor("#F5F5F5")
    val textMuted = Color.parseColor("#9A9A9A")
    val divider = Color.parseColor("#2E2E2E")
}

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var container: LinearLayout
    private var authObserverJob: Job? = null

    private val localBaseUrl = "http://127.0.0.1:${StreamService.PORT}"

    // Tab plumbing
    private lateinit var contentFrame: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var logsRoot: LinearLayout
    private lateinit var logsTextView: TextView
    private lateinit var logsScroll: ScrollView
    private lateinit var homeTabButton: Button
    private lateinit var logsTabButton: Button
    private var logsCollectorJob: Job? = null

    private lateinit var createLogDocLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        ChannelCatalogBuilder.init(applicationContext)
        prefs = getSharedPreferences("tgserver_prefs", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        createLogDocLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) writeLogsToUri(uri)
            }

        window.decorView.setBackgroundColor(Theme.bg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(buildTabBar())

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(contentFrame)

        // --- Home tab content (the existing credential/status flow) ---
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        homeScroll = ScrollView(this).apply {
            setBackgroundColor(Theme.bg)
            addView(container)
        }
        contentFrame.addView(homeScroll)

        // --- Logs tab content ---
        logsRoot = buildLogsTab()
        logsRoot.visibility = View.GONE
        contentFrame.addView(logsRoot)

        setContentView(root)
        selectTab(showLogs = false)

        val savedApiId = prefs.getInt("api_id", 0)
        val savedApiHash = prefs.getString("api_hash", "") ?: ""
        val savedChannelId = prefs.getLong("channel_id", 0L)
        val savedTmdbKey = prefs.getString("tmdb_api_key", "") ?: ""
        val savedGeminiKey = prefs.getString("gemini_api_key", "") ?: ""

        TmdbClient.init(savedTmdbKey)
        GeminiClient.init(savedGeminiKey)

        if (savedApiId == 0 || savedApiHash.isEmpty() || savedChannelId == 0L) {
            showApiCredentialsForm()
        } else {
            try {
                TelegramClient.init(applicationContext, savedApiId, savedApiHash)
                observeAuthState()
                startWatchdog()
            } catch (t: Throwable) {
                FileLogger.error("Error during startup init", t)
                showApiCredentialsForm()
            }
        }
    }

    // ------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------

    private fun buildTabBar(): LinearLayout {
        val pad = (12 * resources.displayMetrics.density).toInt()

        homeTabButton = styledTabButton("Home")
        logsTabButton = styledTabButton("Logs")

        homeTabButton.setOnClickListener { selectTab(showLogs = false) }
        logsTabButton.setOnClickListener { selectTab(showLogs = true) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.surface)
            setPadding(pad, pad, pad, pad)
            addView(
                homeTabButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val spacer = View(this@MainActivity)
            addView(spacer, LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1))
            addView(
                logsTabButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun styledTabButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Theme.textPrimary)
            setBackgroundColor(Theme.surfaceAlt)
        }

    private fun selectTab(showLogs: Boolean) {
        homeScroll.visibility = if (showLogs) View.GONE else View.VISIBLE
        logsRoot.visibility = if (showLogs) View.VISIBLE else View.GONE

        homeTabButton.setBackgroundColor(if (showLogs) Theme.surfaceAlt else Theme.accent)
        homeTabButton.setTextColor(if (showLogs) Theme.textPrimary else Color.BLACK)

        logsTabButton.setBackgroundColor(if (showLogs) Theme.accent else Theme.surfaceAlt)
        logsTabButton.setTextColor(if (showLogs) Color.BLACK else Theme.textPrimary)

        if (showLogs) {
            startLogsCollector()
        } else {
            logsCollectorJob?.cancel()
        }
    }

    // ------------------------------------------------------------
    // Logs tab
    // ------------------------------------------------------------

    private fun buildLogsTab(): LinearLayout {
        val pad = (12 * resources.displayMetrics.density).toInt()

        logsTextView = TextView(this).apply {
            setTextColor(Theme.textPrimary)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
            text = "No logs yet."
        }

        logsScroll = ScrollView(this).apply {
            setBackgroundColor(Theme.bg)
            addView(logsTextView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.surface)
            setPadding(pad, pad, pad, pad)
        }

        val clearBtn = orangeButton("Clear") { onClearLogs() }
        val saveBtn = orangeButton("Save .txt") { onSaveLogsAsTxt() }
        val shareBtn = orangeButton("Share") { shareLogFile() }
        val copyBtn = orangeButton("Copy") { onCopyLogs() }

        listOf(clearBtn, saveBtn, shareBtn, copyBtn).forEachIndexed { index, btn ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) {
                lp.marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            buttonRow.addView(btn, lp)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            addView(logsScroll)
            addView(buttonRow)
        }
    }

    private fun orangeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.BLACK)
            setBackgroundColor(Theme.accent)
            setOnClickListener { onClick() }
        }

    private fun startLogsCollector() {
        logsCollectorJob?.cancel()
        logsCollectorJob = lifecycleScope.launch {
            FileLogger.liveLines.collect { lines ->
                logsTextView.text = if (lines.isEmpty()) "No logs yet." else lines.joinToString("\n")
                logsScroll.post { logsScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun onClearLogs() {
        FileLogger.clear()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    private fun onSaveLogsAsTxt() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        createLogDocLauncher.launch("telestream_logs_$stamp.txt")
    }

    private fun writeLogsToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(FileLogger.liveLines.value.joinToString("\n").toByteArray())
            }
            Toast.makeText(this, "Logs saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.error("Failed to save logs to $uri", e)
            Toast.makeText(this, "Couldn't save logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onCopyLogs() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TeleStream logs", FileLogger.liveLines.value.joinToString("\n"))
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.error("Failed to copy logs to clipboard", e)
            Toast.makeText(this, "Couldn't copy logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogFile() {
        val file = FileLogger.getLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No log file yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share log file"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------
    // Home tab (unchanged flow, restyled)
    // ------------------------------------------------------------

    private fun startWatchdog() {
        lifecycleScope.launch {
            delay(20_000)
            if (TelegramClient.authState.value is AuthState.Idle) {
                TelegramClient.authState.value = AuthState.Error(
                    "TDLib did not respond within 20 seconds. Check your internet connection and API credentials."
                )
            }
        }
    }

    private fun clearScreen() {
        container.removeAllViews()
        addShareLogButton()
    }

    private fun addShareLogButton() {
        val button = orangeButton("Share Debug Log") { shareLogFile() }
        container.addView(button)
    }

    private fun addText(text: String, size: Float = 16f) {
        container.addView(
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Theme.textPrimary)
            }
        )
    }

    private fun styledEditText(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            setBackgroundColor(Theme.surfaceAlt)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

    private fun showApiCredentialsForm() {
        clearScreen()
        addText("Enter Telegram Credentials", 18f)

        val idInput = styledEditText("api_id (e.g. 1234567)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            prefs.getInt("api_id", 0).let { if (it != 0) setText(it.toString()) }
        }
        val hashInput = styledEditText("api_hash").apply {
            setText(prefs.getString("api_hash", "") ?: "")
        }
        val channelInput = styledEditText("channel_id (e.g. -1001234567890)").apply {
            setText(prefs.getLong("channel_id", 0L).let { if (it != 0L) it.toString() else "" })
        }
        container.addView(idInput)
        container.addView(hashInput)
        container.addView(channelInput)

        addText("")
        addText("TMDB API Key (Optional - 32 character key)", 15f)
        val tmdbInput = styledEditText("tmdb_api_key (v3 auth)").apply {
            setText(prefs.getString("tmdb_api_key", "") ?: "")
        }
        container.addView(tmdbInput)

        addText("")
        addText("Gemini API Key (Optional - AI-assisted catalog matching)", 15f)
        val geminiInput = styledEditText("gemini_api_key").apply {
            setText(prefs.getString("gemini_api_key", "") ?: "")
        }
        container.addView(geminiInput)
        addText(
            "Only used as a fallback when TMDB can't find a movie match on its own - " +
                "leave blank to disable.",
            12f
        )

        val saveButton = orangeButton("Save & Continue") {
            val id = idInput.text.toString().trim().toIntOrNull()
            val hash = hashInput.text.toString().trim()
            val channelId = channelInput.text.toString().trim().toLongOrNull()
            val tmdbKey = tmdbInput.text.toString().trim()
            val geminiKey = geminiInput.text.toString().trim()

            if (id == null || hash.isEmpty()) {
                Toast.makeText(this, "Enter valid api_id and api_hash", Toast.LENGTH_SHORT).show()
                return@orangeButton
            }
            if (channelId == null) {
                Toast.makeText(this, "Enter valid channel_id (negative integer)", Toast.LENGTH_SHORT).show()
                return@orangeButton
            }

            try {
                prefs.edit()
                    .putInt("api_id", id)
                    .putString("api_hash", hash)
                    .putLong("channel_id", channelId)
                    .putString("tmdb_api_key", tmdbKey)
                    .putString("gemini_api_key", geminiKey)
                    .apply()

                TmdbClient.init(tmdbKey)
                GeminiClient.init(geminiKey)
                TelegramClient.init(applicationContext, id, hash)
                observeAuthState()
                startWatchdog()
            } catch (t: Throwable) {
                FileLogger.error("Failed to save credentials and start client", t)
                Toast.makeText(this, "Startup error: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
        container.addView(saveButton)
    }

    private fun observeAuthState() {
        authObserverJob?.cancel()
        authObserverJob = lifecycleScope.launch {
            TelegramClient.authState.collect { state ->
                when (state) {
                    is AuthState.Idle -> {
                        clearScreen(); addText("Starting Telegram Client...")
                    }
                    is AuthState.WaitPhone -> showLoginInput("Enter phone number (+1234567890)") {
                        TelegramClient.submitPhone(it)
                    }
                    is AuthState.WaitCode -> showLoginInput("Enter verification code") {
                        TelegramClient.submitCode(it)
                    }
                    is AuthState.WaitPassword -> showLoginInput("Enter 2FA password") {
                        TelegramClient.submitPassword(it)
                    }
                    is AuthState.Ready -> showMainScreen()
                    is AuthState.Error -> {
                        clearScreen()
                        addText("Error: ${state.message}", 18f)
                        val retryButton = orangeButton("Start Over") {
                            prefs.edit().clear().apply()
                            recreate()
                        }
                        container.addView(retryButton)
                    }
                }
            }
        }
    }

    private fun showLoginInput(label: String, onSubmit: (String) -> Unit) {
        clearScreen()
        addText(label)
        val input = styledEditText("")
        container.addView(input)
        val button = orangeButton("Submit") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onSubmit(text)
        }
        container.addView(button)
    }

    private fun showMainScreen() {
        clearScreen()
        addText("Telegram Status: Connected", 18f)
        addText("Local Server: ${if (StreamService.isRunning) "Running on port ${StreamService.PORT}" else "Stopped"}")
        addText("TMDB Enriched: ${if (TmdbClient.isConfigured()) "Enabled" else "Disabled"}")
        addText("Gemini Assist: ${if (GeminiClient.isConfigured()) "Enabled" else "Disabled"}")

        val toggleButton = orangeButton(
            if (StreamService.isRunning) "Stop Server" else "Start Server"
        ) {
            val intent = Intent(this, StreamService::class.java)
            if (StreamService.isRunning) {
                stopService(intent)
            } else {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            container.postDelayed({ showMainScreen() }, 1000)
        }
        container.addView(toggleButton)

        val fetchButton = orangeButton("Refresh Catalog") { fetchAndShowCatalog(fullRebuild = false) }
        container.addView(fetchButton)

        val fullRebuildButton = orangeButton("Full Rebuild (Slow)") { fetchAndShowCatalog(fullRebuild = true) }
        container.addView(fullRebuildButton)
        addText(
            "Refresh Catalog = fast incremental sync (only new files since last time). " +
                "Full Rebuild = re-scans everything and re-spends every TMDB/Gemini lookup - " +
                "only use it if something looks wrong or you renamed/edited files already synced before.",
            12f
        )
    }

    private fun fetchAndShowCatalog(fullRebuild: Boolean) {
        val channelId = prefs.getLong("channel_id", 0L)
        lifecycleScope.launch {
            addText(
                if (fullRebuild) {
                    "Full rebuild: re-scanning everything from Telegram & TMDB/Gemini..."
                } else {
                    "Syncing catalog (new files only)..."
                }
            )
            val items = try {
                withContext(Dispatchers.IO) {
                    CatalogRepository.fetchCatalog(localBaseUrl, channelId, forceRefresh = true, fullRebuild = fullRebuild)
                }
            } catch (e: Exception) {
                addText("Catalog error: ${e.message}")
                return@launch
            }
            items.forEach { item ->
                val row = TextView(this@MainActivity).apply {
                    text = "${item.title} (${item.year ?: "N/A"}) [${item.type.uppercase()}]" +
                            (item.imdbId?.let { " - IMDb: $it" } ?: "")
                    setTextColor(Theme.textPrimary)
                    setPadding(0, 16, 0, 16)
                }
                container.addView(row)
            }
        }
    }
}
