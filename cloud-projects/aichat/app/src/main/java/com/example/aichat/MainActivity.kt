package com.example.aichat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val history = ArrayList<JSONObject>()
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private var chatId = 0

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messages = findViewById(R.id.messages)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        sendButton = findViewById(R.id.sendButton)

        sendButton.setOnClickListener { send() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { newChat() }

        newChat()
        if (apiKey().isEmpty()) showSettings()
    }

    private fun apiKey(): String = prefs.getString("key", "") ?: ""

    private fun model(): String = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    private fun newChat() {
        chatId++
        history.clear()
        messages.removeAllViews()
        sendButton.isEnabled = true
        addBubble(getString(R.string.welcome), false)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val key = apiKey()
        if (key.isEmpty()) {
            showSettings()
            return
        }

        input.setText("")
        addBubble(text, true)
        val question = JSONObject().put("role", "user").put("content", text)
        history.add(question)

        val waiting = addBubble(getString(R.string.thinking), false)
        sendButton.isEnabled = false

        val body = JSONObject()
            .put("model", model())
            .put("messages", JSONArray(history.takeLast(20)))
            .toString()
        val currentChat = chatId

        thread {
            val result = runCatching { ask(key, body) }
            runOnUiThread {
                if (currentChat != chatId || isDestroyed) return@runOnUiThread
                result.onSuccess { reply ->
                    waiting.text = reply
                    history.add(JSONObject().put("role", "assistant").put("content", reply))
                }.onFailure { error ->
                    waiting.text = "Error: ${error.message}"
                    history.remove(question)
                    input.setText(text)
                }
                sendButton.isEnabled = true
                scrollToBottom()
            }
        }
    }

    private fun ask(key: String, body: String): String {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(response) }.getOrNull()

            val apiError = json?.optJSONObject("error")?.optString("message")
            if (code !in 200..299 || !apiError.isNullOrEmpty()) {
                throw Exception("HTTP $code ${apiError ?: ""}")
            }

            val message = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            if (message == null || message.isNull("content")) throw Exception("Empty reply. Try again.")
            val reply = message.getString("content").trim()
            if (reply.isEmpty()) throw Exception("Empty reply. Try again.")
            return reply
        } finally {
            connection.disconnect()
        }
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = 16f
        bubble.setTextIsSelectable(true)
        bubble.setTextColor(if (fromUser) Color.WHITE else Color.BLACK)
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10))

        val background = GradientDrawable()
        background.cornerRadius = dp(16).toFloat()
        background.setColor(Color.parseColor(if (fromUser) "#6750A4" else "#ECE6F0"))
        bubble.background = background

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = if (fromUser) Gravity.END else Gravity.START
        params.topMargin = dp(8)
        if (fromUser) params.marginStart = dp(48) else params.marginEnd = dp(48)

        messages.addView(bubble, params)
        scrollToBottom()
        return bubble
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val keyInput = EditText(this)
        keyInput.hint = getString(R.string.key_hint)
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.setText(apiKey())
        box.addView(keyInput)

        val modelInput = EditText(this)
        modelInput.hint = getString(R.string.model_hint)
        modelInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        modelInput.setText(model())
        box.addView(modelInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit()
                    .putString("key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim().ifEmpty { DEFAULT_MODEL })
                    .apply()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val DEFAULT_MODEL = "openrouter/free"
    }
}
