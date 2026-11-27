package com.sky.translate

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts translate2.html in a locked-down WebView and wires it to the native ML Kit OCR bridge.
 * The page is served from https://appassets.androidplatform.net/ (a secure origin), which is
 * required for getUserMedia (live camera) and navigator.clipboard.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var bridge: OcrBridge? = null
    private var speech: SpeechBridge? = null
    private var micCallback: ((Boolean) -> Unit)? = null

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = handleBack()
    }

    private var pendingMediaRequest: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingMediaRequest
            pendingMediaRequest = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = micCallback
            micCallback = null
            cb?.invoke(granted)
        }

    /** Used by SpeechBridge: runs [cb] with true once the microphone permission is granted. */
    fun ensureMicPermission(cb: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            cb(true)
        } else {
            micCallback?.invoke(false)
            micCallback = cb
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Gallery picker used by the page file input (images only). */
    private val galleryPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.webview)

        // Keep content out of the system bars / keyboard (the page already pads with env(safe-area-inset-*),
        // which WebView does not reliably populate, so insets are applied natively instead).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        updateSystemBarIcons()

        configureWebView()

        bridge = OcrBridge(web).also { web.addJavascriptInterface(it, "MLKitOCR") }
        speech = SpeechBridge(this, web).also { web.addJavascriptInterface(it, "NativeSpeech") }
        web.addJavascriptInterface(ClipBridge(this), "NativeClip")

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(web.settings) {
            javaScriptEnabled = true            // required by the app
            domStorageEnabled = true            // localStorage (history, favourites, settings)
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false   // translated text is spoken after async requests
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION") saveFormData = false
        }
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg))

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // Only the bundled page may load in this WebView; web links (e.g. "Search") open in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "https" && url.host == APP_HOST) return false
                if (url.scheme == "https" || url.scheme == "http") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) {}
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Only our own origin, and only the camera. (Microphone/speech APIs are not available in WebView.)
                val ok = request.origin.host == APP_HOST &&
                    request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!ok) { request.deny(); return }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingMediaRequest?.deny()
                    pendingMediaRequest = request
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMediaRequest === request) pendingMediaRequest = null
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    galleryPicker.launch("image/*")
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, R.string.no_gallery, Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    /** Lets the page close its own overlays first; leaves the app only from the home screen. */
    private fun handleBack() {
        web.evaluateJavascript(BACK_JS) { result ->
            if (result?.contains("handled") != true) {
                backCallback.isEnabled = false          // fall through to the default behaviour (leave the app)
                onBackPressedDispatcher.onBackPressed()
                backCallback.isEnabled = true
            }
        }
    }

    private fun updateSystemBarIcons() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarIcons()
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onStop() { speech?.stopAll(); super.onStop() }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        pendingMediaRequest?.deny(); pendingMediaRequest = null
        micCallback?.invoke(false); micCallback = null
        bridge?.close(); bridge = null
        speech?.close(); speech = null
        web.removeJavascriptInterface("MLKitOCR")
        web.removeJavascriptInterface("NativeSpeech")
        web.removeJavascriptInterface("NativeClip")
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$APP_HOST/assets/translate2.html"

        // Closes the top-most overlay of translate2.html using its own buttons; returns "handled" or "none".
        const val BACK_JS = """(function(){
            var d=document,on=function(i){var e=d.getElementById(i);return e&&e.classList.contains('on')},
            click=function(i){var e=d.getElementById(i);if(e){e.click();return true}return false};
            if(on('cam')&&click('camX'))return 'handled';
            if(on('lvS')&&click('lvX'))return 'handled';
            var s=d.getElementById('sub');
            if(s&&getComputedStyle(s).display!=='none'&&click('back'))return 'handled';
            if(on('ts')&&click('tBk'))return 'handled';
            return 'none'})()"""
    }
}
