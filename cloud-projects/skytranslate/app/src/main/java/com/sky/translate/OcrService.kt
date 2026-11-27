package com.sky.translate

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Single OCR engine for the whole app. Models are bundled, so it works offline. */
class OcrService {

    enum class Script { LATIN, DEVANAGARI, CHINESE, JAPANESE, KOREAN }

    private val worker = Executors.newSingleThreadExecutor()
    private val clients = HashMap<Script, TextRecognizer>()

    /** Language name (same names as the app's language list) -> ML Kit script. null = unsupported. */
    fun scriptFor(lang: String): Script? = when (lang) {
        "English", "French", "Spanish", "German", "Italian", "Portuguese",
        "Indonesian", "Turkish", "Dutch", "Vietnamese", "Polish", "Swedish" -> Script.LATIN
        "Hindi", "Marathi", "Nepali" -> Script.DEVANAGARI
        "Chinese" -> Script.CHINESE
        "Japanese" -> Script.JAPANESE
        "Korean" -> Script.KOREAN
        else -> null // Tamil, Arabic, Telugu, Thai, Russian... -> app falls back to Tesseract
    }

    private fun client(s: Script): TextRecognizer = synchronized(clients) {
        clients.getOrPut(s) {
            TextRecognition.getClient(
                when (s) {
                    Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                    Script.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                    Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                    Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                    Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                }
            )
        }
    }

    /** Runs off the main thread. Calls exactly one of onOk(json) / onErr(code, message). */
    fun recognize(
        base64Jpeg: String,
        lang: String,
        onOk: (String) -> Unit,
        onErr: (String, String) -> Unit
    ) {
        val script = scriptFor(lang)
        if (script == null) {
            onErr("unsupported", "Text reading isn't available for $lang")
            return
        }
        try { worker.execute { runOcr(base64Jpeg, script, onOk, onErr) } }
        catch (e: java.util.concurrent.RejectedExecutionException) { onErr("fail", "Couldn't read the text. Try again") }
    }

    private fun runOcr(
        base64Jpeg: String,
        script: Script,
        onOk: (String) -> Unit,
        onErr: (String, String) -> Unit
    ) {
        run {
            val bmp = try {
                val bytes = Base64.decode(base64Jpeg, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Throwable) { null }
            if (bmp == null) {
                onErr("fail", "Couldn't open that image")
                return
            }
            client(script).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val lines = JSONArray()
                    for (block in result.textBlocks) for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        lines.put(JSONObject()
                            .put("text", line.text)
                            .put("confidence", (line.confidence * 100).toDouble())
                            .put("bbox", JSONObject()
                                .put("x0", b.left).put("y0", b.top)
                                .put("x1", b.right).put("y1", b.bottom)))
                    }
                    bmp.recycle()
                    onOk(JSONObject().put("lines", lines).toString())
                }
                .addOnFailureListener { e ->
                    bmp.recycle()
                    val m = e.message.orEmpty()
                    if (m.contains("model", true) || m.contains("download", true))
                        onErr("model", "Text model isn't ready yet. Connect to the internet once")
                    else onErr("fail", "Couldn't read the text. Try again")
                }
        }
    }

    fun close() {
        synchronized(clients) { clients.values.forEach { it.close() }; clients.clear() }
        worker.shutdown()
    }
}
