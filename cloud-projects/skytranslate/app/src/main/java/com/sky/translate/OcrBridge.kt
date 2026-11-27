package com.sky.translate

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** JS name: window.MLKitOCR  (translate2.html already calls it). */
class OcrBridge(private val web: WebView, private val ocr: OcrService = OcrService()) {

    @Volatile private var closed = false

    @JavascriptInterface
    fun supports(lang: String?): Boolean =
        !closed && lang != null && lang.length <= MAX_LANG_LEN && ocr.scriptFor(lang) != null

    @JavascriptInterface
    fun recognize(id: Int, base64Jpeg: String?, lang: String?) {
        // Validate everything that comes from JavaScript before touching native code.
        if (closed) return
        if (id <= 0) return                       // can't reply to an invalid id
        if (lang == null || lang.length > MAX_LANG_LEN) {
            reply(id, error("unsupported", "Text reading isn't available for this language")); return
        }
        if (base64Jpeg.isNullOrEmpty() || base64Jpeg.length > MAX_BASE64_CHARS) {
            reply(id, error("fail", "Couldn't open that image")); return
        }
        ocr.recognize(base64Jpeg, lang,
            onOk = { json -> reply(id, json) },
            onErr = { code, msg -> reply(id, error(code, msg)) })
    }

    private fun error(code: String, msg: String) =
        JSONObject().put("code", code).put("error", msg).toString()

    private fun reply(id: Int, json: String) {
        if (closed) return
        web.post {
            if (!closed) web.evaluateJavascript("window.__ocrDone($id,${JSONObject.quote(json)})", null)
        }
    }

    fun close() {
        closed = true
        ocr.close()
    }

    private companion object {
        const val MAX_LANG_LEN = 32
        const val MAX_BASE64_CHARS = 14_000_000   // ~10 MB of JPEG; the page sends <= 1600 px images
    }
}
