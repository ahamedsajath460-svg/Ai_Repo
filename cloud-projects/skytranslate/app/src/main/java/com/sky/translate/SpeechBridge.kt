package com.sky.translate

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * JS name: window.NativeSpeech. Android's WebView has no SpeechRecognition / usable speechSynthesis,
 * so assets/native-speech.js maps those web APIs onto this bridge (native SpeechRecognizer + TextToSpeech).
 * All Android calls run on the main thread. Events go back through window.__nsEvt(kind, id, type, json).
 */
class SpeechBridge(
    private val activity: MainActivity,
    private val web: WebView
) {
    @Volatile private var closed = false

    // ---- speech recognition -------------------------------------------------------------------
    private var recognizer: SpeechRecognizer? = null
    private var recId = 0

    @JavascriptInterface
    fun recognitionAvailable(): Boolean = !closed && SpeechRecognizer.isRecognitionAvailable(activity)

    @JavascriptInterface
    fun startRecognition(id: Int, lang: String?, interim: Boolean) {
        if (closed || id <= 0) return
        val tag = cleanLang(lang)
        activity.runOnUiThread {
            if (closed) return@runOnUiThread
            activity.ensureMicPermission { granted ->
                if (!granted) { emit("rec", id, "error", err("not-allowed")); emit("rec", id, "end"); return@ensureMicPermission }
                beginRecognition(id, tag, interim)
            }
        }
    }

    @JavascriptInterface
    fun stopRecognition(id: Int) { activity.runOnUiThread { if (id == recId) recognizer?.stopListening() } }

    @JavascriptInterface
    fun abortRecognition(id: Int) {
        activity.runOnUiThread { if (id == recId) { destroyRecognizer(); recId = 0 } }
    }

    private fun beginRecognition(id: Int, lang: String, interim: Boolean) {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            emit("rec", id, "error", err("service-not-allowed")); emit("rec", id, "end"); return
        }
        recId = id
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        var ended = false
        fun finish() { if (!ended) { ended = true; emit("rec", id, "end") } }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (id == recId) emit("rec", id, "start") }
            override fun onBeginningOfSpeech() { if (id == recId) emit("rec", id, "speechstart") }
            override fun onEndOfSpeech() { if (id == recId) emit("rec", id, "speechend") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (id != recId) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) emit("rec", id, "result", JSONObject().put("t", t).put("final", false).toString())
            }
            override fun onResults(results: Bundle?) {
                if (id != recId) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val c = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: 0.9f
                if (t.isNotBlank()) emit("rec", id, "result",
                    JSONObject().put("t", t).put("final", true).put("c", c.toDouble()).toString())
                finish()
            }
            override fun onError(error: Int) {
                if (id != recId) return
                emit("rec", id, "error", err(mapError(error)))
                finish()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        }
        try { r.startListening(intent) }
        catch (e: Exception) { emit("rec", id, "error", err("audio-capture")); finish() }
    }

    private fun mapError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-not-supported"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> "aborted"
        else -> "network"
    }

    private fun destroyRecognizer() {
        recognizer?.let { try { it.cancel(); it.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    // ---- text to speech ------------------------------------------------------------------------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private val ttsQueue = ArrayList<() -> Unit>()
    private var speakId = 0

    @JavascriptInterface
    fun ttsAvailable(): Boolean = !closed && !ttsFailed

    @JavascriptInterface
    fun speak(id: Int, text: String?, lang: String?, rate: Float) {
        if (closed || id <= 0 || text.isNullOrBlank()) { if (id > 0) emit("tts", id, "end"); return }
        val tag = cleanLang(lang)
        val body = text.take(MAX_SPEAK_CHARS)
        activity.runOnUiThread { withTts { doSpeak(id, body, tag, rate.coerceIn(0.5f, 2.0f)) } }
    }

    @JavascriptInterface
    fun stopSpeaking() { activity.runOnUiThread { tts?.stop(); speakId = 0 } }

    private fun withTts(action: () -> Unit) {
        if (ttsReady) { action(); return }
        ttsQueue.add(action)
        if (tts != null) return
        tts = TextToSpeech(activity) { status ->
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(progress)
                    ttsQueue.toList().also { ttsQueue.clear() }.forEach { it() }
                } else {
                    ttsFailed = true
                    ttsQueue.clear()
                }
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = utteranceEnded(utteranceId, false)
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = utteranceEnded(utteranceId, true)
        override fun onError(utteranceId: String?, errorCode: Int) = utteranceEnded(utteranceId, true)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = utteranceEnded(utteranceId, interrupted)
    }

    /** Utterance ids look like "<id>:<chunk>:<last 0|1>". Only the last chunk (or any failure) reports back. */
    private fun utteranceEnded(utteranceId: String?, failed: Boolean) {
        val p = utteranceId?.split(":") ?: return
        val id = p.getOrNull(0)?.toIntOrNull() ?: return
        if (failed) emit("tts", id, "error", err("synthesis-failed"))
        else if (p.getOrNull(2) == "1") emit("tts", id, "end")
    }

    private fun doSpeak(id: Int, text: String, lang: String, rate: Float) {
        val engine = tts ?: run { emit("tts", id, "error", err("synthesis-unavailable")); return }
        val loc = Locale.forLanguageTag(lang)
        val avail = engine.isLanguageAvailable(loc)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            emit("tts", id, "error", err("language-unavailable")); return
        }
        engine.language = loc
        engine.setSpeechRate(rate)
        engine.stop()
        speakId = id
        val chunks = chunkText(text, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500))
        chunks.forEachIndexed { i, part ->
            val last = if (i == chunks.lastIndex) 1 else 0
            val r = engine.speak(part, TextToSpeech.QUEUE_ADD, null, "$id:$i:$last")
            if (r != TextToSpeech.SUCCESS) { emit("tts", id, "error", err("synthesis-failed")); return }
        }
    }

    private fun chunkText(t: String, max: Int): List<String> {
        val out = ArrayList<String>(); var s = t.trim()
        while (s.length > max) {
            var i = maxOf(s.lastIndexOf(". ", max), s.lastIndexOf("\n", max), s.lastIndexOf(' ', max))
            if (i < 1) i = max
            out.add(s.substring(0, i + 1).trim()); s = s.substring(i + 1).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }

    // ---- shared -------------------------------------------------------------------------------
    private fun err(code: String) = JSONObject().put("error", code).toString()

    private fun cleanLang(l: String?): String =
        l?.takeIf { it.length in 2..35 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
            ?.replace('_', '-') ?: "en-US"

    private fun emit(kind: String, id: Int, type: String, json: String = "{}") {
        if (closed) return
        web.post {
            if (!closed) web.evaluateJavascript("window.__nsEvt&&window.__nsEvt('$kind',$id,'$type',${JSONObject.quote(json)})", null)
        }
    }

    /** Called when the app goes to the background: stop listening/speaking. */
    fun stopAll() {
        activity.runOnUiThread { destroyRecognizer(); recId = 0; tts?.stop(); speakId = 0 }
    }

    fun close() {
        closed = true
        destroyRecognizer()
        tts?.let { try { it.stop(); it.shutdown() } catch (_: Exception) {} }
        tts = null; ttsQueue.clear()
    }

    private companion object { const val MAX_SPEAK_CHARS = 20_000 }
}
