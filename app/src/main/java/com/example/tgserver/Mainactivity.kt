package com.example.tgserver

import android.content.Intent
import android.content.SharedPreferences
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var container: LinearLayout

    // Same Render backend from main.py - only ever sends/receives text.
    private val renderBaseUrl = "https://tg-cs3.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        if (savedApiId == 0 || savedApiHash.isEmpty()) {
            showApiCredentialsForm()
        } else {
            TelegramClient.init(applicationContext, savedApiId, savedApiHash)
            observeAuthState()
        }
    }

    private fun clearScreen() = container.removeAllViews()

    private fun addText(text: String, size: Float = 16f) {
        container.addView(TextView(this).apply { this.text = text; textSize = size })
    }

    private fun showApiCredentialsForm() {
        clearScreen()
        addText("Enter your Telegram API credentials", 18f)
        addText("Get these from my.telegram.org -> API Development Tools. Same values as your Python backend's .env.")

        val idInput = EditText(this).apply { hint = "api_id (numbers only)" }
        val hashInput = EditText(this).apply { hint = "api_hash" }
        container.addView(idInput)
        container.addView(hashInput)

        val saveButton = Button(this).apply { text = "Save & Continue" }
        saveButton.setOnClickListener {
            val id = idInput.text.toString().trim().toIntOrNull()
            val hash = hashInput.text.toString().trim()
            if (id == null || hash.isEmpty()) {
                Toast.makeText(this, "Enter a valid api_id and api_hash", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putInt("api_id", id).putString("api_hash", hash).apply()
            TelegramClient.init(applicationContext, id, hash)
            observeAuthState()
        }
        container.addView(saveButton)
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            TelegramClient.authState.collect { state ->
                when (state) {
                    is AuthState.Idle -> {
                        clearScreen(); addText("Starting Telegram client...")
                    }
                    is AuthState.WaitPhone -> showLoginInput("Enter your phone number (e.g. +15551234567)") {
                        TelegramClient.submitPhone(it)
                    }
                    is AuthState.WaitCode -> showLoginInput("Enter the login code Telegram just sent you") {
                        TelegramClient.submitCode(it)
                    }
                    is AuthState.WaitPassword -> showLoginInput("Enter your 2FA password") {
                        TelegramClient.submitPassword(it)
                    }
                    is AuthState.Ready -> showMainScreen()
                    is AuthState.Error -> {
                        clearScreen(); addText("Error: ${state.message}")
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
        addText("Logged in to Telegram", 18f)

        addText(
            if (StreamService.isRunning) "Server: running on 127.0.0.1:${StreamService.PORT}"
            else "Server: stopped"
        )

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
            Toast.makeText(this, "Updating...", Toast.LENGTH_SHORT).show()
            container.postDelayed({ showMainScreen() }, 1000)
        }
        container.addView(toggleButton)

        val fetchButton = Button(this).apply { text = "Fetch Catalog (from Render)" }
        fetchButton.setOnClickListener { fetchAndShowCatalog() }
        container.addView(fetchButton)
    }

    private fun fetchAndShowCatalog() {
        lifecycleScope.launch {
            addText("Loading catalog...")
            val items = try {
                withContext(Dispatchers.IO) { CatalogRepository.fetchCatalog(renderBaseUrl) }
            } catch (e: Exception) {
                addText("Failed to load catalog: ${e.message}")
                return@launch
            }
            items.forEach { item ->
                val part = item.parts.firstOrNull() ?: return@forEach
                val row = TextView(this@MainActivity).apply {
                    text = "${item.title}  (${part.size / 1024} KB)\n" +
                        "http://127.0.0.1:${StreamService.PORT}/video?chat_id=${part.chatId}&message_id=${part.messageId}"
                    setPadding(0, 24, 0, 24)
                }
                container.addView(row)
            }
        }
    }
}