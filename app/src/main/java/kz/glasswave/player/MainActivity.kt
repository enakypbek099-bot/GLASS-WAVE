package kz.glasswave.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    companion object {
        private const val HTML_ASSET = "GLASS_WAVE.html"
        private const val JS_ASSET = "js/gw-native.js"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"
        private const val EXPECTED_SIZE = 531623
        private const val EXPECTED_SHA256 =
            "6c715249c7331a45b259aec5c99275eb9d2ccc6f728340d0bb0d6011598a7b2c"
    }

    private lateinit var webView: WebView
    private lateinit var libraryStore: GwLibraryStore
    private lateinit var bridge: GwBridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fatalPageShown = false
    private var pageRevealed = false
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val metadataExecutor = Executors.newFixedThreadPool(2)

    private val trackPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importTracks(uris)
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GwRuntimeLog.initialize(applicationContext)
        libraryStore = GwLibraryStore(applicationContext)

        val firstFrameColor = Color.rgb(11, 24, 32)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = firstFrameColor
        window.navigationBarColor = firstFrameColor
        window.decorView.setBackgroundColor(firstFrameColor)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        bridge = GwBridge(
            context = this,
            library = libraryStore,
            requestTrackPicker = { trackPicker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) },
            libraryChanged = ::emitLibrary
        )

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            alpha = 0f
            setBackgroundColor(firstFrameColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(bridge, "GwBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    GwRuntimeLog.info("WEB_CONSOLE", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { fileLauncher.launch(params.createIntent()); true }
                    catch (error: Throwable) {
                        GwRuntimeLog.error("FILE_CHOOSER", "File chooser failed", error)
                        fileCallback = null
                        false
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "appassets.androidplatform.net"

                override fun onPageFinished(view: WebView, url: String) {
                    if (!fatalPageShown) injectBridge(view)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) showFatalPage("GLASS WAVE іске қосылмады.\n\n${error.description}")
                }
            }
        }

        setContentView(webView)
        loadOriginalHtml()
    }

    private fun importTracks(uris: List<Uri>) {
        importExecutor.execute {
            val distinct = uris.distinctBy(Uri::toString)
            distinct.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { GwRuntimeLog.warn("LIBRARY", "Persistable permission failed: $uri") }
            }
            libraryStore.importBasics(contentResolver, distinct)
            runOnUiThread(::emitLibrary)

            val remaining = AtomicInteger(distinct.size)
            distinct.forEach { uri ->
                metadataExecutor.execute {
                    try { libraryStore.enrich(contentResolver, uri) }
                    finally {
                        if (remaining.decrementAndGet() == 0) runOnUiThread(::emitLibrary)
                    }
                }
            }
        }
    }

    private fun emitLibrary() {
        if (!::webView.isInitialized) return
        val payload = libraryStore.asJson()
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('gw:library-changed',{detail:$payload}));",
            null
        )
    }

    private fun loadOriginalHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use { it.readBytes() }
            check(bytes.size == EXPECTED_SIZE) { "HTML size mismatch" }
            check(sha256(bytes) == EXPECTED_SHA256) { "HTML hash mismatch" }
            webView.loadDataWithBaseURL(PAGE_URL, bytes.toString(Charsets.UTF_8), "text/html", "UTF-8", null)
        } catch (error: Throwable) {
            GwRuntimeLog.error("ASSET", "Unable to open $HTML_ASSET", error)
            showFatalPage("GLASS WAVE іске қосылмады.\n\n${error.message ?: "Белгісіз қате"}")
        }
    }

    private fun injectBridge(view: WebView) {
        try {
            val script = assets.open(JS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            view.evaluateJavascript("(function(){try{$script}catch(e){try{GwBridge.jsError(String(e.stack||e))}catch(_){}}})();") {
                emitLibrary()
                revealPage(view)
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error("JS_INJECT", "Bridge injection failed", error)
            revealPage(view)
        }
    }

    private fun revealPage(view: WebView) {
        if (pageRevealed) return
        view.postOnAnimation { view.postOnAnimation { pageRevealed = true; view.alpha = 1f } }
    }

    private fun showFatalPage(message: String) {
        if (fatalPageShown || !::webView.isInitialized) return
        fatalPageShown = true
        pageRevealed = true
        webView.alpha = 1f
        val page = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;min-height:100%;background:#0b1820;color:#f1f3ff;font-family:system-ui}main{padding:28px 20px}pre{white-space:pre-wrap;background:#111b24;border-radius:16px;padding:16px}</style></head><body><main><h1>GLASS WAVE</h1><pre>${TextUtils.htmlEncode(message)}</pre></main></body></html>"""
        webView.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        GwNativeAudio.closeSafely()
        importExecutor.shutdownNow()
        metadataExecutor.shutdownNow()
        webView.removeJavascriptInterface("GwBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
