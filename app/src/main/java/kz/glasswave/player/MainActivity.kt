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
import java.io.*
import java.security.MessageDigest
import java.util.LinkedHashSet
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
    private lateinit var library: GwLibraryStore
    private lateinit var media: GwMediaMetadataStore
    private lateinit var bridge: GwBridge

    private val handler = Handler(Looper.getMainLooper())
    private val importWorker = Executors.newSingleThreadExecutor()
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var fatal = false
    private var routeCallbackRegistered = false

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(x: Array<out AudioDeviceInfo>) {
            emitRoute()
        }
        override fun onAudioDevicesRemoved(x: Array<out AudioDeviceInfo>) {
            emitRoute()
        }
    }

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        val selected = LinkedHashSet<Uri>()
        data.data?.let(selected::add)
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                selected.add(clip.getItemAt(index).uri)
            }
        }
        if (selected.isEmpty()) return@registerForActivityResult

        selected.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        importWorker.execute {
            val ids = library.importUris(selected.toList())
            emitLibrary()
            ids.forEach { id ->
                metadataWorker.execute {
                    library.enrich(id)
                    library.find(id)?.let { track ->
                        media.enrich(
                            GwMediaMetadataStore.Pending(
                                track.id, track.uri, track.name
                            )
                        )
                    }
                    emitLibrary()
                }
            }
        }
    }

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanDeviceMusic()
    }

    private val webPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams
                .parseResult(result.resultCode, result.data)
        )
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GwRuntimeLog.initialize(applicationContext)
        library = GwLibraryStore(applicationContext)
        media = GwMediaMetadataStore(applicationContext)

        val background = Color.rgb(11, 24, 32)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.setBackgroundColor(background)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        bridge = GwBridge(
            this, library,
            { launchPicker() },
            { requestDeviceScan() },
            { emitLibrary() }
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
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportMultipleWindows(false)
                setSupportZoom(false)
            }

            addJavascriptInterface(bridge, "GwBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    GwRuntimeLog.info(
                        "WEB_CONSOLE",
                        "${m.messageLevel()} ${m.message()}"
                    )
                    return true
                }
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        webPicker.launch(params.createIntent())
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
                    if (request.url.host == "appassets.androidplatform.net") {
                        val path = request.url.path.orEmpty()
                        if (path.startsWith("/tracks/")) {
                            return openTrack(request)
                        }
                        if (path.startsWith("/covers/")) {
                            return openCover(request)
                        }
                    }
                    return loader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean =
                    request.url.host != "appassets.androidplatform.net"

                override fun onPageFinished(view: WebView, url: String) {
                    if (!fatal) inject(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        showFatal(
                            "GLASS WAVE іске қосылмады.\n" +
                                error.description
                        )
                    }
                }
            }
        }

        setContentView(webView)
        loadHtml()
        scheduleExistingMetadata()
    }

    private fun launchPicker() {
        val types = arrayOf(
            "audio/*", "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav", "audio/mpeg",
            "audio/mp4", "audio/aac", "audio/ogg", "audio/opus",
            "application/ogg", "application/octet-stream"
        )
        picker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_MIME_TYPES, types)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        )
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
        if (!routeCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(routeCallback, handler)
            routeCallbackRegistered = true
        }
        emitRoute()
    }

    override fun onStop() {
        webView.onPause()
        if (routeCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(routeCallback)
            }
            routeCallbackRegistered = false
        }
        super.onStop()
    }

    private fun requestDeviceScan() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) scanDeviceMusic()
        else mediaPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private fun scanDeviceMusic() {
        importWorker.execute {
            library.scanMediaStore()
            emitLibrary()
            scheduleExistingMetadata()
        }
    }

    private fun scheduleExistingMetadata() {
        metadataWorker.execute {
            media.pending(library.asJson()).forEachIndexed { index, item ->
                media.enrich(item)
                if ((index + 1) % 4 == 0) emitLibrary()
            }
            emitLibrary()
        }
    }

    private fun emitLibrary() {
        if (!pageReady) return
        val json = media.decorateLibraryJson(library.asJson())
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){" +
                    "const d=$json;" +
                    "if(window.GW_LIBRARY)GW_LIBRARY.replace(d);" +
                    "window.dispatchEvent(new CustomEvent(" +
                    "'gw:library-changed',{detail:d}));" +
                    "})();",
                null
            )
        }
    }

    private fun emitRoute() {
        if (!pageReady || !::bridge.isInitialized) return
        val json = bridge.currentOutputDevice()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent(" +
                    "'gw:route-changed',{detail:$json}));",
                null
            )
        }
    }

    private fun openTrack(request: WebResourceRequest): WebResourceResponse {
        return try {
            val parts = request.url.pathSegments
            if (parts.size < 2) return response(404, "Not Found")
            val track = library.find(parts[1])
                ?: return response(404, "Not Found")

            val descriptor = contentResolver.openAssetFileDescriptor(
                Uri.parse(track.uri), "r"
            ) ?: return response(404, "Not Found")

            val total = when {
                descriptor.length > 0 -> descriptor.length
                track.size > 0 -> track.size
                else -> -1L
            }

            val rangeHeader = request.requestHeaders.entries
                .firstOrNull { it.key.equals("Range", true) }
                ?.value

            val range = parseRange(rangeHeader, total)

            if (rangeHeader != null && total > 0 && range == null) {
                descriptor.close()
                return WebResourceResponse(
                    "text/plain", "UTF-8",
                    416, "Range Not Satisfiable",
                    mapOf("Content-Range" to "bytes */$total"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val start = range?.first ?: 0L
            val end = range?.last ?: if (total > 0) total - 1 else -1L
            val length = if (total > 0) end - start + 1 else -1L

            val headers = linkedMapOf(
                "Accept-Ranges" to "bytes",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )
            if (length > 0) headers["Content-Length"] = length.toString()
            if (range != null) {
                headers["Content-Range"] = "bytes $start-$end/$total"
            }

            val status = if (range != null) 206 else 200
            val reason = if (range != null) "Partial Content" else "OK"

            if (request.method.equals("HEAD", true)) {
                descriptor.close()
                return WebResourceResponse(
                    track.mime, null, status, reason,
                    headers, ByteArrayInputStream(ByteArray(0))
                )
            }

            val input = descriptor.createInputStream()
            skipFully(input, start)

            val body: InputStream = if (length > 0) {
                LimitedInput(input, length) {
                    runCatching { descriptor.close() }
                }
            } else {
                ClosingInput(input) {
                    runCatching { descriptor.close() }
                }
            }

            WebResourceResponse(
                track.mime, null, status, reason, headers, body
            )
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "TRACK_STREAM",
                request.url.toString(),
                error
            )
            response(500, "Internal Error")
        }
    }

    private fun openCover(request: WebResourceRequest): WebResourceResponse {
        return try {
            val name = request.url.pathSegments.lastOrNull().orEmpty()
            val id = name.substringBeforeLast('.', name)
            val file = media.coverFileFor(id)
                ?: return response(404, "Not Found")

            WebResourceResponse(
                "image/jpeg", null, 200, "OK",
                mapOf(
                    "Cache-Control" to "public, max-age=86400",
                    "Content-Length" to file.length().toString()
                ),
                FileInputStream(file)
            )
        } catch (_: Throwable) {
            response(500, "Internal Error")
        }
    }

    private fun parseRange(value: String?, total: Long): LongRange? {
        if (value == null || total <= 0) return null
        val match = Regex("""bytes=(\d*)-(\d*)""")
            .matchEntire(value.trim()) ?: return null

        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isEmpty() && right.isEmpty()) return null

        val start: Long
        val end: Long
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (total - suffix).coerceAtLeast(0)
            end = total - 1
        } else {
            start = left.toLongOrNull() ?: return null
            end = right.toLongOrNull()?.coerceAtMost(total - 1)
                ?: total - 1
        }

        return if (start in 0 until total && end >= start) {
            start..end
        } else null
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() < 0) break
            else remaining--
        }
    }

    private fun response(code: Int, reason: String) =
        WebResourceResponse(
            "text/plain", "UTF-8", code, reason,
            emptyMap(),
            ByteArrayInputStream(reason.toByteArray())
        )

    private class ClosingInput(
        input: InputStream,
        private val finished: () -> Unit
    ) : FilterInputStream(input) {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { super.close() }
            finished()
        }
    }

    private class LimitedInput(
        input: InputStream,
        private var remaining: Long,
        private val finished: () -> Unit
    ) : FilterInputStream(input) {
        private var closed = false

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
            val allowed = minOf(remaining, length.toLong()).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) remaining -= count
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { super.close() }
            finished()
        }
    }

    private fun optimizeHtml(source: String): String {
        val changes = listOf(
            "ts-microLastFrame>45" to "ts-microLastFrame>80",
            "ts-soundLabLast>55" to "ts-soundLabLast>100",
            "if(ts-last<28)return" to "if(ts-last<50)return",
            "setInterval(()=>{if(labOpen())coreState()},350)" to
                "setInterval(()=>{if(labOpen())coreState()},750)",
            "setInterval(sync,400)" to "setInterval(sync,800)",
            "if(ts-last<45||document.hidden||fx()||!window.nwFx)return" to
                "if(ts-last<100||document.hidden||fx()||!window.nwFx)return"
        )
        return changes.fold(source) { text, pair ->
            if (text.contains(pair.first)) {
                text.replace(pair.first, pair.second)
            } else text
        }
    }

    private fun loadHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use { it.readBytes() }
            check(
                bytes.size == EXPECTED_SIZE &&
                    sha256(bytes) == EXPECTED_SHA256
            )

            val guard = """
                <script>
                (function(){
                  const r=requestAnimationFrame.bind(window);
                  const c=cancelAnimationFrame.bind(window);
                  const s=setInterval.bind(window);
                  const p=new Map();
                  let n=1;
                  window.requestAnimationFrame=function(f){
                    const k=n++,a=performance.now(),o={i:0};
                    const x=t=>{
                      if(!p.has(k))return;
                      if(t-a<33){o.i=r(x);return}
                      p.delete(k);f(t)
                    };
                    p.set(k,o);o.i=r(x);return k
                  };
                  window.cancelAnimationFrame=function(k){
                    const o=p.get(k);
                    if(o){p.delete(k);c(o.i)}else c(k)
                  };
                  window.setInterval=function(f,d){
                    if(typeof f==='function'&&f.name==='detect')return-1;
                    return s.apply(window,arguments)
                  }
                })();
                </script>
            """.trimIndent()

            val html = optimizeHtml(bytes.toString(Charsets.UTF_8))
                .replaceFirst(
                    "<head>",
                    "<head>$guard",
                    ignoreCase = true
                )

            webView.loadDataWithBaseURL(
                PAGE_URL, html, "text/html", "UTF-8", null
            )
        } catch (error: Throwable) {
            showFatal(
                "GLASS WAVE іске қосылмады.\n\n" +
                    error.stackTraceToString()
            )
        }
    }

    private fun inject(view: WebView) {
        try {
            val script = assets.open(JS_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            view.evaluateJavascript(script) {
                pageReady = true
                emitLibrary()
                emitRoute()
                view.postOnAnimation {
                    view.postOnAnimation { view.alpha = 1f }
                }
            }
        } catch (_: Throwable) {
            pageReady = true
            view.alpha = 1f
        }
    }

    private fun showFatal(message: String) {
        if (fatal) return
        fatal = true
        pageReady = true
        webView.alpha = 1f

        val text = TextUtils.htmlEncode(message)
        webView.loadDataWithBaseURL(
            null,
            "<!doctype html><html>" +
                "<body style='background:#0b1820;" +
                "color:white;padding:24px'>" +
                "<pre>$text</pre></body></html>",
            "text/html", "UTF-8", null
        )
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 255)
            }

    override fun onDestroy() {
        if (routeCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(routeCallback)
            }
        }
        fileCallback?.onReceiveValue(null)
        importWorker.shutdownNow()
        metadataWorker.shutdownNow()
        GwNativeAudio.closeSafely()
        webView.removeJavascriptInterface("GwBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
