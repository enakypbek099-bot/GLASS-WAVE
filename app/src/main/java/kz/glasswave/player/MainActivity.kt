package kz.glasswave.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val HTML_ASSET = "GLASS_WAVE.html"
        private const val JS_ASSET = "js/gw-native.js"
        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"
        private const val EXPECTED_SIZE = 531623
        private const val EXPECTED_SHA256 =
            "6c715249c7331a45b259aec5c99275eb" +
            "9d2ccc6f728340d0bb0d6011598a7b2c"
    }

    private lateinit var webView: WebView
    private lateinit var libraryStore: GwLibraryStore
    private lateinit var bridge: GwBridge

    private val importExecutor =
        Executors.newSingleThreadExecutor()

    private var fileCallback:
        ValueCallback<Array<Uri>>? = null

    private var fatalPageShown = false
    private var pageRevealed = false

    private val webFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(
                result.resultCode,
                result.data
            )
        )
        fileCallback = null
    }

    private val trackPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult

        uris.distinct().forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                GwRuntimeLog.warn(
                    "LIBRARY",
                    "Persist permission unavailable: $uri"
                )
            }
        }

        importExecutor.execute {
            val ids = libraryStore.importBasics(uris)
            emitLibrary()

            ids.forEach { id ->
                libraryStore.enrich(id)
                emitLibrary()
            }
        }
    }

    @SuppressLint(
        "SetJavaScriptEnabled",
        "JavascriptInterface"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GwRuntimeLog.initialize(applicationContext)
        libraryStore = GwLibraryStore(applicationContext)

        val background = Color.rgb(11, 24, 32)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.setBackgroundColor(background)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        bridge = GwBridge(
            context = this,
            libraryStore = libraryStore,
            requestTrackPicker = {
                trackPicker.launch(arrayOf("audio/*"))
            },
            libraryChanged = {
                emitLibrary()
            }
        )

        webView = WebView(this).apply {
            alpha = 0f
            setBackgroundColor(background)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportMultipleWindows(false)
                setSupportZoom(false)
            }

            addJavascriptInterface(bridge, "GwBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback

                    return try {
                        webFileLauncher.launch(
                            params.createIntent()
                        )
                        true
                    } catch (_: Throwable) {
                        fileCallback = null
                        false
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (
                        request.url.host ==
                        "appassets.androidplatform.net" &&
                        request.url.path?.startsWith(
                            "/tracks/"
                        ) == true
                    ) {
                        return openTrackResponse(request)
                    }

                    return assetLoader.shouldInterceptRequest(
                        request.url
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean =
                    request.url.host !=
                        "appassets.androidplatform.net"

                override fun onPageFinished(
                    view: WebView,
                    url: String
                ) {
                    if (!fatalPageShown) injectBridge(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        showFatalPage(
                            "GLASS WAVE іске қосылмады.\n" +
                            error.description
                        )
                    }
                }
            }
        }

        setContentView(webView)
        loadHtml()
    }

    private fun emitLibrary() {
        if (!::webView.isInitialized) return

        val json = libraryStore.asJson()

        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(" +
                    "new CustomEvent('gw:library-changed'," +
                    "{detail:$json}));",
                null
            )
        }
    }

    private fun openTrackResponse(
        request: WebResourceRequest
    ): WebResourceResponse {
        return try {
            val id = request.url.pathSegments
                .lastOrNull()
                .orEmpty()

            val track = libraryStore.find(id)
                ?: return errorResponse(404, "Not Found")

            val uri = Uri.parse(track.uri)
            val afd = contentResolver.openAssetFileDescriptor(
                uri,
                "r"
            ) ?: return errorResponse(404, "Not Found")

            val total = when {
                afd.length > 0 -> afd.length
                track.size > 0 -> track.size
                else -> -1L
            }

            val range = request.requestHeaders["Range"]
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            var partial = false

            if (range != null && total > 0) {
                val match = Regex(
                    """bytes=(\d+)-(\d*)"""
                ).find(range)

                if (match != null) {
                    start = match.groupValues[1].toLong()
                    end = match.groupValues[2]
                        .toLongOrNull()
                        ?.coerceAtMost(total - 1)
                        ?: total - 1
                    partial = true
                }
            }

            val raw = java.io.FileInputStream(
                afd.fileDescriptor
            )

            skipFully(raw, afd.startOffset + start)

            val length = if (total > 0) {
                end - start + 1
            } else {
                Long.MAX_VALUE
            }

            val body: InputStream =
                if (request.method == "HEAD") {
                    afd.close()
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    LimitedInputStream(raw, length) {
                        runCatching { afd.close() }
                    }
                }

            val headers = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-store"
            )

            if (total > 0) {
                headers["Content-Length"] =
                    length.toString()

                if (partial) {
                    headers["Content-Range"] =
                        "bytes $start-$end/$total"
                }
            }

            WebResourceResponse(
                track.mime,
                null,
                if (partial) 206 else 200,
                if (partial) "Partial Content" else "OK",
                headers,
                body
            )
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "TRACK_STREAM",
                "Track response failed",
                error
            )
            errorResponse(500, "Internal Error")
        }
    }

    private fun errorResponse(
        code: Int,
        reason: String
    ) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        code,
        reason,
        emptyMap(),
        ByteArrayInputStream(reason.toByteArray())
    )

    private fun skipFully(
        stream: InputStream,
        count: Long
    ) {
        var remaining = count

        while (remaining > 0) {
            val skipped = stream.skip(remaining)

            if (skipped > 0) {
                remaining -= skipped
            } else if (stream.read() == -1) {
                break
            } else {
                remaining--
            }
        }
    }

    private class LimitedInputStream(
        input: InputStream,
        private var remaining: Long,
        private val finished: () -> Unit
    ) : FilterInputStream(input) {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            if (remaining <= 0) return -1

            val allowed = minOf(
                length.toLong(),
                remaining
            ).toInt()

            val read = super.read(
                buffer,
                offset,
                allowed
            )

            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            runCatching { super.close() }
            finished()
        }
    }

    private fun loadHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use {
                it.readBytes()
            }

            check(bytes.size == EXPECTED_SIZE)
            check(sha256(bytes) == EXPECTED_SHA256)

            webView.loadDataWithBaseURL(
                PAGE_URL,
                bytes.toString(Charsets.UTF_8),
                "text/html",
                "UTF-8",
                null
            )
        } catch (error: Throwable) {
            showFatalPage(
                "GLASS WAVE іске қосылмады.\n\n" +
                    error.stackTraceToString()
            )
        }
    }

    private fun injectBridge(view: WebView) {
        try {
            val script = assets.open(JS_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            view.evaluateJavascript(script) {
                emitLibrary()

                view.postOnAnimation {
                    view.postOnAnimation {
                        pageRevealed = true
                        view.alpha = 1f
                    }
                }
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "JS_INJECT",
                "Bridge injection failed",
                error
            )
            view.alpha = 1f
        }
    }

    private fun showFatalPage(message: String) {
        if (fatalPageShown) return
        fatalPageShown = true
        pageRevealed = true
        webView.alpha = 1f

        val escaped = TextUtils.htmlEncode(message)

        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html>
            <html><body style="background:#0b1820;color:white;
            font-family:sans-serif;padding:24px">
            <h2>GLASS WAVE</h2><pre>$escaped</pre>
            </body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        importExecutor.shutdownNow()
        GwNativeAudio.closeSafely()
        webView.removeJavascriptInterface("GwBridge")
        webView.destroy()
        super.onDestroy()
    }
}
