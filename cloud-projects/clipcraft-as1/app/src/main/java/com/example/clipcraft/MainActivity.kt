package com.example.clipcraft

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false

        web.webViewClient = object : WebViewClient() {
            // The page is served from the APK on a private https address, so saved drafts work reliably.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != HOST) return null
                if (url.path == "/index.html") {
                    return WebResourceResponse("text/html", "UTF-8", assets.open("index.html"))
                }
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val types = fileChooserParams.acceptTypes
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.contains("/") }

                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = if (types.size == 1) types[0] else "*/*"
                if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                return try {
                    startActivityForResult(Intent.createChooser(intent, "Select media"), PICK_FILES)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        setContentView(web)
        web.loadUrl("https://$HOST/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES) return

        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data?.data?.let { uris.add(it) }
            }
        }
        fileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileCallback = null
    }

    // Back goes to the previous screen inside the page; on the home screen it leaves the app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val script = """
            (function () {
                var shown = function (id) { return !document.getElementById(id).classList.contains('hide'); };
                if (shown('sheet')) { document.getElementById('sheet').classList.add('hide'); return true; }
                if (shown('sEd')) { document.getElementById('eBack').click(); return true; }
                if (shown('sPick')) { document.getElementById('pBack').click(); return true; }
                return false;
            })()
        """
        web.evaluateJavascript(script) { handled ->
            if (handled != "true") finish()
        }
    }

    override fun onPause() {
        web.evaluateJavascript("if (typeof pause === 'function') pause();", null)
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOST = "clipcraft.local"
        private const val PICK_FILES = 100
    }
}
