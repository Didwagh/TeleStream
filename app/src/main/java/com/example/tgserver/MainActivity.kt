package com.example.tgserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.ImageView
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
 * App-wide look: near-black background, orange accent. Everything here is
 * built with plain Views (no XML layouts, no Compose) to match how the rest
 * of this Activity was already written - see build.gradle.kts, there's no
 * Compose dependency wired up. Rounded-corner GradientDrawables + consistent
 * spacing are used throughout instead of flat setBackgroundColor() calls to
 * give it a less "raw debug screen" look while keeping the exact same
 * black/orange palette.
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
    val good = Color.parseColor("#4CD964")
    val bad = Color.parseColor("#FF5A5A")
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

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

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

        root.addView(buildHeader())
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
            val padH = dp(16)
            setPadding(padH, dp(14), padH, dp(24))
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
    // Rounded-drawable helpers - everything visual below builds on
    // these instead of flat setBackgroundColor(), which is what was
    // making every screen look like an unstyled debug dump.
    // ------------------------------------------------------------

    private fun roundedDrawable(
        color: Int,
        radiusDp: Int = 12,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null && strokeWidthDp > 0) {
            setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun buttonBackground(baseColor: Int, pressedColor: Int, radiusDp: Int = 14): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(pressedColor, radiusDp))
            addState(intArrayOf(-android.R.attr.state_enabled), roundedDrawable(Theme.surfaceAlt, radiusDp))
            addState(intArrayOf(), roundedDrawable(baseColor, radiusDp))
        }

    /** A padded, rounded surface used to visually group related content instead of a flat page. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(Theme.surface, radiusDp = 16, strokeColor = Theme.divider, strokeWidthDp = 1)
        val padH = dp(16)
        setPadding(padH, dp(14), padH, dp(14))
    }

    private fun addSpaced(view: View, topMarginDp: Int = 10) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topMarginDp)
        container.addView(view, lp)
    }

    // ------------------------------------------------------------
    // Header
    // ------------------------------------------------------------

    private fun buildHeader(): LinearLayout {
        val padH = dp(16)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.bg)
            setPadding(padH, dp(16), padH, dp(10))

            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.icon)
                val size = dp(34)
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(icon)

            val spacer = View(this@MainActivity)
            addView(spacer, LinearLayout.LayoutParams(dp(10), 1))

            val textCol = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(this@MainActivity).apply {
                text = "TeleStream"
                setTextColor(Theme.textPrimary)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
            })
            textCol.addView(TextView(this@MainActivity).apply {
                text = "Telegram → CloudStream bridge"
                setTextColor(Theme.textMuted)
                textSize = 12f
            })
            addView(textCol)
        }
    }

    // ------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------

    private fun buildTabBar(): LinearLayout {
        val padH = dp(16)

        homeTabButton = styledTabButton("Home")
        logsTabButton = styledTabButton("Logs")

        homeTabButton.setOnClickListener { selectTab(showLogs = false) }
        logsTabButton.setOnClickListener { selectTab(showLogs = true) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.bg)
            setPadding(padH, 0, padH, dp(12))
            addView(
                homeTabButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val spacer = View(this@MainActivity)
            addView(spacer, LinearLayout.LayoutParams(dp(8), 1))
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
            textSize = 13f
            setTextColor(Theme.textPrimary)
            background = roundedDrawable(Theme.surfaceAlt, radiusDp = 20)
            setPadding(0, dp(10), 0, dp(10))
            stateListAnimator = null
            elevation = 0f
        }

    private fun selectTab(showLogs: Boolean) {
        homeScroll.visibility = if (showLogs) View.GONE else View.VISIBLE
        logsRoot.visibility = if (showLogs) View.VISIBLE else View.GONE

        homeTabButton.background = roundedDrawable(if (showLogs) Theme.surfaceAlt else Theme.accent, radiusDp = 20)
        homeTabButton.setTextColor(if (showLogs) Theme.textPrimary else Color.BLACK)

        logsTabButton.background = roundedDrawable(if (showLogs) Theme.accent else Theme.surfaceAlt, radiusDp = 20)
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
        val padH = dp(16)

        logsTextView = TextView(this).apply {
            setTextColor(Theme.textPrimary)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "No logs yet."
        }

        val logsCard = card().apply {
            addView(logsTextView)
        }

        logsScroll = ScrollView(this).apply {
            setBackgroundColor(Theme.bg)
            setPadding(padH, 0, padH, dp(12))
            clipToPadding = false
            addView(logsCard)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.bg)
            setPadding(padH, 0, padH, dp(16))
        }

        val clearBtn = orangeButton("Clear") { onClearLogs() }
        val saveBtn = orangeButton("Save .txt") { onSaveLogsAsTxt() }
        val shareBtn = orangeButton("Share") { shareLogFile() }
        val copyBtn = orangeButton("Copy") { onCopyLogs() }

        listOf(clearBtn, saveBtn, shareBtn, copyBtn).forEachIndexed { index, btn ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) {
                lp.marginStart = dp(6)
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
            textSize = 13f
            setTextColor(Color.BLACK)
            background = buttonBackground(Theme.accent, Theme.accentPressedTint)
            val padH = dp(16)
            setPadding(padH, dp(12), padH, dp(12))
            stateListAnimator = null
            elevation = dp(1).toFloat()
            setOnClickListener { onClick() }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(Theme.textPrimary)
            background = buttonBackground(Theme.surfaceAlt, Theme.divider)
            val padH = dp(16)
            setPadding(padH, dp(12), padH, dp(12))
            stateListAnimator = null
            elevation = 0f
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
    // Home tab
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
        val button = secondaryButton("Share Debug Log") { shareLogFile() }
        addSpaced(button, topMarginDp = 0)
    }

    private fun addText(text: String, size: Float = 16f, muted: Boolean = false, bold: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(if (muted) Theme.textMuted else Theme.textPrimary)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        addSpaced(view)
    }

    private fun sectionLabel(text: String) {
        val view = TextView(this).apply {
            this.text = text.uppercase()
            textSize = 12f
            setTextColor(Theme.accent)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
        }
        addSpaced(view, topMarginDp = 18)
    }

    private fun styledEditText(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(Theme.textMuted)
            setTextColor(Theme.textPrimary)
            background = roundedDrawable(Theme.surfaceAlt, radiusDp = 10)
            val padH = dp(14)
            setPadding(padH, dp(12), padH, dp(12))
        }

    private fun statusRow(label: String, value: String, isGood: Boolean?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 13f
            setTextColor(
                when (isGood) {
                    true -> Theme.good
                    false -> Theme.bad
                    null -> Theme.textMuted
                }
            )
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Theme.textMuted)
            textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            setTextColor(Theme.textPrimary)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })
    }

    private fun showApiCredentialsForm() {
        clearScreen()
        addText("Enter Telegram Credentials", 18f, bold = true)
        addText(
            "These come from my.telegram.org and are only stored locally on this device.",
            12f, muted = true
        )

        sectionLabel("Required")
        val requiredCard = card()
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
        requiredCard.addView(idInput)
        requiredCard.addView(hashInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10) })
        requiredCard.addView(channelInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10) })
        addSpaced(requiredCard, topMarginDp = 8)

        sectionLabel("Optional - metadata enrichment")
        val optionalCard = card()
        val tmdbLabel = TextView(this).apply {
            text = "TMDB API Key (32-character v3 key)"
            setTextColor(Theme.textMuted)
            textSize = 12f
        }
        val tmdbInput = styledEditText("tmdb_api_key").apply {
            setText(prefs.getString("tmdb_api_key", "") ?: "")
        }
        val geminiLabel = TextView(this).apply {
            text = "Gemini API Key (AI-assisted catalog matching, optional)"
            setTextColor(Theme.textMuted)
            textSize = 12f
        }
        val geminiInput = styledEditText("gemini_api_key").apply {
            setText(prefs.getString("gemini_api_key", "") ?: "")
        }
        optionalCard.addView(tmdbLabel)
        optionalCard.addView(tmdbInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })
        optionalCard.addView(geminiLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(14) })
        optionalCard.addView(geminiInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })
        addSpaced(optionalCard, topMarginDp = 8)
        addText(
            "Gemini is only used as a fallback when TMDB can't find a movie match on its own - " +
                "leave blank to disable.",
            12f, muted = true
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
        addSpaced(saveButton, topMarginDp = 20)
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
                        addText("Error", 18f, bold = true)
                        addText(state.message, 13f, muted = true)
                        val retryButton = orangeButton("Start Over") {
                            prefs.edit().clear().apply()
                            recreate()
                        }
                        addSpaced(retryButton, topMarginDp = 16)
                    }
                }
            }
        }
    }

    private fun showLoginInput(label: String, onSubmit: (String) -> Unit) {
        clearScreen()
        addText(label, 18f, bold = true)
        val input = styledEditText("")
        addSpaced(input)
        val button = orangeButton("Submit") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onSubmit(text)
        }
        addSpaced(button, topMarginDp = 14)
    }

    private fun showMainScreen() {
        clearScreen()
        addText("Status", 18f, bold = true)

        val statusCard = card()
        val savedContainer = container
        container = statusCard
        statusRow("Telegram", "Connected", true)
        statusRow(
            "Local Server",
            if (StreamService.isRunning) "Running on port ${StreamService.PORT}" else "Stopped",
            StreamService.isRunning
        )
        statusRow("TMDB Enriched", if (TmdbClient.isConfigured()) "Enabled" else "Disabled", TmdbClient.isConfigured())
        statusRow("Gemini Assist", if (GeminiClient.isConfigured()) "Enabled" else "Disabled", GeminiClient.isConfigured())
        container = savedContainer
        addSpaced(statusCard, topMarginDp = 8)

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
        addSpaced(toggleButton, topMarginDp = 18)

        sectionLabel("Catalog")
        val fetchButton = orangeButton("Refresh Catalog") { fetchAndShowCatalog(fullRebuild = false) }
        addSpaced(fetchButton, topMarginDp = 8)

        val fullRebuildButton = secondaryButton("Full Rebuild (Slow)") { fetchAndShowCatalog(fullRebuild = true) }
        addSpaced(fullRebuildButton)
        addText(
            "Refresh Catalog = fast incremental sync (only new files since last time). " +
                "Full Rebuild = re-scans everything and re-spends every TMDB/Gemini lookup - " +
                "only use it if something looks wrong or you renamed/edited files already synced before.",
            12f, muted = true
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
                },
                13f, muted = true
            )
            val items = try {
                withContext(Dispatchers.IO) {
                    CatalogRepository.fetchCatalog(localBaseUrl, channelId, forceRefresh = true, fullRebuild = fullRebuild)
                }
            } catch (e: Exception) {
                addText("Catalog error: ${e.message}", 13f, muted = true)
                return@launch
            }

            sectionLabel("${items.size} item(s)")
            val resultsCard = card()
            items.forEachIndexed { index, item ->
                val row = TextView(this@MainActivity).apply {
                    text = "${item.title} (${item.year ?: "N/A"}) · ${item.type.uppercase()}" +
                            (item.imdbId?.let { " · IMDb: $it" } ?: "")
                    setTextColor(Theme.textPrimary)
                    textSize = 13f
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (index > 0) lp.topMargin = dp(8)
                resultsCard.addView(row, lp)
            }
            addSpaced(resultsCard, topMarginDp = 8)
        }
    }
}
