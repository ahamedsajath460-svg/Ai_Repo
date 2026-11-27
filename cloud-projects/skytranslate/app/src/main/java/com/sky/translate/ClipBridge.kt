package com.sky.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/** JS name: window.NativeClip. WebView blocks navigator.clipboard.readText(), so the Paste button uses this. */
class ClipBridge(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun read(): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString().take(MAX) else ""
    } catch (e: Exception) { "" }

    @JavascriptInterface
    fun write(text: String?) {
        if (text == null) return
        val t = text.take(MAX)
        main.post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", t))
            } catch (_: Exception) {}
        }
    }

    private companion object { const val MAX = 50_000 }
}
