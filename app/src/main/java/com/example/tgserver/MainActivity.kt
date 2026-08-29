package com.example.tgserver

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var container: LinearLayout
    private var authObserverJob: Job? = null

    private val localBaseUrl = "http://127.0.0.1:${StreamService.PORT}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        prefs = getSharedPreferences("tgserver_prefs", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)
        setContentView(scroll)

        val savedApiId = prefs.getInt("api_id", 0)
        val savedApiHash = prefs.getString("api_hash", "") ?: ""
        val savedChannelId = prefs.getLong("channel_id", 0L)
        val savedTmdbKey = prefs.getString("tmdb_api_key", "") ?: ""

        TmdbClient.init(savedTmdbKey)

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
        val button = Button(this).apply { text = "Share Debug Log" }
        button.setOnClickListener { shareLogFile() }
        container.addView(button)
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

    private fun addText(text: String, size: Float = 16f) {
        container.addView(TextView(this).apply { this.text = text; textSize = size })
    }

    private fun showApiCredentialsForm() {
        clearScreen()
        addText("Enter Telegram Credentials", 18f)

        val idInput = EditText(this).apply {
            hint = "api_id (e.g. 1234567)"
            prefs.getInt("api_id", 0).let { if (it != 0) setText(it.toString()) }
        }
        val hashInput = EditText(this).apply {
            hint = "api_hash"
            setText(prefs.getString("api_hash", "") ?: "")
        }
        val channelInput = EditText(this).apply {
            hint = "channel_id (e.g. -1001234567890)"
            setText(prefs.getLong("channel_id", 0L).let { if (it != 0L) it.toString() else "" })
        }
        container.addView(idInput)
        container.addView(hashInput)
        container.addView(channelInput)

        addText("")
        addText("TMDB API Key (Optional - 32 character key)", 15f)
        val tmdbInput = EditText(this).apply {
            hint = "tmdb_api_key (v3 auth)"
            setText(prefs.getString("tmdb_api_key", "") ?: "")
        }
        container.addView(tmdbInput)

        val saveButton = Button(this).apply { text = "Save & Continue" }
        saveButton.setOnClickListener {
            val id = idInput.text.toString().trim().toIntOrNull()
            val hash = hashInput.text.toString().trim()
            val channelId = channelInput.text.toString().trim().toLongOrNull()
            val tmdbKey = tmdbInput.text.toString().trim()

            if (id == null || hash.isEmpty()) {
                Toast.makeText(this, "Enter valid api_id and api_hash", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (channelId == null) {
                Toast.makeText(this, "Enter valid channel_id (negative integer)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            try {
                prefs.edit()
                    .putInt("api_id", id)
                    .putString("api_hash", hash)
                    .putLong("channel_id", channelId)
                    .putString("tmdb_api_key", tmdbKey)
                    .apply()

                TmdbClient.init(tmdbKey)
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
                        val retryButton = Button(this@MainActivity).apply { text = "Start Over" }
                        retryButton.setOnClickListener {
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
        val input = EditText(this)
        container.addView(input)
        val button = Button(this).apply { text = "Submit" }
        button.setOnClickListener {
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

        val toggleButton = Button(this).apply {
            text = if (StreamService.isRunning) "Stop Server" else "Start Server"
        }
        toggleButton.setOnClickListener {
            val intent = Intent(this, StreamService::class.java)
            if (StreamService.isRunning) {
                stopService(intent)
            } else {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            container.postDelayed({ showMainScreen() }, 1000)
        }
        container.addView(toggleButton)

        val fetchButton = Button(this).apply { text = "Refresh Catalog" }
        fetchButton.setOnClickListener { fetchAndShowCatalog() }
        container.addView(fetchButton)
    }

    private fun fetchAndShowCatalog() {
        val channelId = prefs.getLong("channel_id", 0L)
        lifecycleScope.launch {
            addText("Rebuilding catalog from Telegram & TMDB...")
            val items = try {
                withContext(Dispatchers.IO) {
                    CatalogRepository.fetchCatalog(localBaseUrl, channelId, forceRefresh = true)
                }
            } catch (e: Exception) {
                addText("Catalog error: ${e.message}")
                return@launch
            }
            items.forEach { item ->
                val row = TextView(this@MainActivity).apply {
                    text = "${item.title} (${item.year ?: "N/A"}) [${item.type.uppercase()}]" +
                            (item.imdbId?.let { " - IMDb: $it" } ?: "")
                    setPadding(0, 16, 0, 16)
                }
                container.addView(row)
            }
        }
    }
}