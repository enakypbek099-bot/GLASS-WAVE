package kz.glasswave.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        private const val HTML_ASSET = "GLASS_WAVE.html"
        private const val JS_ASSET = "js/gw-native.js"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"
        private const val EXPECTED_SIZE = 531623
        private const val EXPECTED_SHA256 = "6c715249c7331a45b259aec5c99275eb9d2ccc6f728340d0bb0d6011598a7b2c"
    }

    private lateinit var webView: WebView
    private lateinit var libraryStore: GwLibraryStore
    private lateinit var bridge: GwBridge
    private val mainHandler = Handler(Looper.getMainLooper())
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fatalPageShown = false
    private var pageReady = false
    private var routeCallbackRegistered = false

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = emitRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = emitRoute()
    }

    private val trackPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val selected = LinkedHashSet<Uri>()
        data.data?.let(selected::add)
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
        if (selected.isEmpty()) return@registerForActivityResult
        selected.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        importExecutor.execute {
            val ids = libraryStore.importUris(selected.toList())
            emitLibrary()
            metadataExecutor.execute {
                ids.forEachIndexed { index, id ->
                    libraryStore.enrich(id)
                    if ((index + 1) % 6 == 0) emitLibrary()
                }
                emitLibrary()
            }
        }
    }

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanDeviceMusic()
    }

    private val webFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GwRuntimeLog.initialize(applicationContext)
        libraryStore = GwLibraryStore(applicationContext)
        val bg = Color.rgb(11, 24, 32)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.setBackgroundColor(bg)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()

        bridge = GwBridge(
            this, libraryStore,
            requestTrackPicker = { launchTrackPicker() },
            requestDeviceScan = { requestDeviceMusicScan() },
            libraryChanged = { emitLibrary() }
        )

        webView = WebView(this).apply {
            alpha = 0f
            setBackgroundColor(bg)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(false)
                setSupportZoom(false)
            }
            addJavascriptInterface(bridge, "GwBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    GwRuntimeLog.info("WEB_CONSOLE", "${message.messageLevel()} ${message.message()}")
                    return true
                }
                override fun onShowFileChooser(
                    webView: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { webFileLauncher.launch(params.createIntent()); true }
                    catch (error: Throwable) { fileCallback = null; false }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host == "appassets.androidplatform.net" && request.url.path?.startsWith("/tracks/") == true) {
                        return openTrackResponse(request)
                    }
                    return loader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "appassets.androidplatform.net"
                override fun onPageFinished(view: WebView, url: String) { if (!fatalPageShown) injectBridge(view) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showFatalPage("GLASS WAVE іске қосылмады.\n${error.description}")
                }
            }
        }
        setContentView(webView)
        loadHtml()
    }

    private fun launchTrackPicker() {
        val types = arrayOf(
            "audio/*", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "application/ogg", "application/octet-stream"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, types)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        trackPicker.launch(intent)
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
        if (!routeCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(routeCallback, mainHandler)
            routeCallbackRegistered = true
        }
        emitRoute()
    }

    override fun onStop() {
        if (routeCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(routeCallback) }
            routeCallbackRegistered = false
        }
        webView.onPause()
        super.onStop()
    }

    private fun requestDeviceMusicScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            scanDeviceMusic()
        } else mediaPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private fun scanDeviceMusic() = importExecutor.execute {
        libraryStore.scanMediaStore()
        emitLibrary()
    }

    private fun emitLibrary() {
        if (!::webView.isInitialized || !pageReady) return
        val json = libraryStore.asJson()
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){const d=$json;if(window.GW_LIBRARY)window.GW_LIBRARY.replace(d);" +
                    "window.dispatchEvent(new CustomEvent('gw:library-changed',{detail:d}));})();", null
            )
        }
    }

    private fun emitRoute() {
        if (!::webView.isInitialized || !::bridge.isInitialized || !pageReady) return
        val json = bridge.currentOutputDevice()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('gw:route-changed',{detail:$json}));", null
            )
        }
    }

    private fun openTrackResponse(request: WebResourceRequest): WebResourceResponse {
        return try {
            val s = request.url.pathSegments
            if (s.size < 2 || s[0] != "tracks") return errorResponse(404, "Not Found")
            val track = libraryStore.find(s[1]) ?: return errorResponse(404, "Not Found")
            val afd = contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")
                ?: return errorResponse(404, "Not Found")
            val length = if (afd.length > 0) afd.length else track.size
            val headers = mutableMapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
            if (length > 0) headers["Content-Length"] = length.toString()
            if (request.method.equals("HEAD", true)) {
                afd.close()
                WebResourceResponse(track.mime, null, 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
            } else {
                val body = ClosingInputStream(afd.createInputStream()) { runCatching { afd.close() } }
                WebResourceResponse(track.mime, null, 200, "OK", headers, body)
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error("TRACK_STREAM", "Track stream failed", error)
            errorResponse(500, "Internal Error")
        }
    }

    private fun errorResponse(code: Int, reason: String) = WebResourceResponse(
        "text/plain", "UTF-8", code, reason, emptyMap(), ByteArrayInputStream(reason.toByteArray())
    )

    private class ClosingInputStream(input: InputStream, private val done: () -> Unit) : FilterInputStream(input) {
        private var closed = false
        override fun close() { if (!closed) { closed = true; runCatching { super.close() }; done() } }
    }

    private fun loadHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use { it.readBytes() }
            check(bytes.size == EXPECTED_SIZE)
            check(sha256(bytes) == EXPECTED_SHA256)
            webView.loadDataWithBaseURL(PAGE_URL, bytes.toString(Charsets.UTF_8), "text/html", "UTF-8", null)
        } catch (error: Throwable) {
            showFatalPage("GLASS WAVE іске қосылмады.\n\n${error.stackTraceToString()}")
        }
    }

    private fun injectBridge(view: WebView) {
        try {
            val script = assets.open(JS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            view.evaluateJavascript(script) {
                pageReady = true
                emitLibrary(); emitRoute()
                view.postOnAnimation { view.postOnAnimation { view.alpha = 1f } }
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error("JS_INJECT", "Bridge injection failed", error)
            pageReady = true
            view.alpha = 1f
        }
    }

    private fun showFatalPage(message: String) {
        if (fatalPageShown) return
        fatalPageShown = true
        pageReady = true
        webView.alpha = 1f
        val e = TextUtils.htmlEncode(message)
        webView.loadDataWithBaseURL(null,
            "<!doctype html><html><body style='background:#0b1820;color:white;padding:24px;font-family:sans-serif'><h2>GLASS WAVE</h2><pre>$e</pre></body></html>",
            "text/html", "UTF-8", null)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun onDestroy() {
        if (routeCallbackRegistered) runCatching { audioManager.unregisterAudioDeviceCallback(routeCallback) }
        fileCallback?.onReceiveValue(null)
        importExecutor.shutdownNow()
        metadataExecutor.shutdownNow()
        GwNativeAudio.closeSafely()
        webView.removeJavascriptInterface("GwBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
