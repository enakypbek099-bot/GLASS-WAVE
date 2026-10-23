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
        private const val HTML_ASSET =
            "GLASS_WAVE.html"

        private const val JS_ASSET =
            "js/gw-native.js"

        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"

        private const val EXPECTED_SIZE =
            531623

        private const val EXPECTED_SHA256 =
            "6c715249c7331a45b259aec5c99275eb" +
                "9d2ccc6f728340d0bb0d6011598a7b2c"
    }

    private lateinit var webView: WebView
    private lateinit var libraryStore: GwLibraryStore
    private lateinit var bridge: GwBridge

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val importExecutor =
        Executors.newSingleThreadExecutor()

    private val metadataExecutor =
        Executors.newFixedThreadPool(2)

    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private var fileCallback:
        ValueCallback<Array<Uri>>? = null

    private var fatalPageShown = false
    private var pageReady = false
    private var routeCallbackRegistered = false

    private val routeCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(
                addedDevices: Array<out AudioDeviceInfo>
            ) {
                emitRoute()
            }

            override fun onAudioDevicesRemoved(
                removedDevices: Array<out AudioDeviceInfo>
            ) {
                emitRoute()
            }
        }

    private val trackPicker =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) {
                return@registerForActivityResult
            }

            val resultIntent =
                result.data
                    ?: return@registerForActivityResult

            val selected = LinkedHashSet<Uri>()

            resultIntent.data?.let(selected::add)

            resultIntent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    selected.add(
                        clip.getItemAt(index).uri
                    )
                }
            }

            if (selected.isEmpty()) {
                GwRuntimeLog.warn(
                    "LIBRARY",
                    "Picker returned no URI"
                )
                return@registerForActivityResult
            }

            selected.forEach { uri ->
                runCatching {
                    contentResolver
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                }.onFailure {
                    GwRuntimeLog.warn(
                        "LIBRARY",
                        "Persistent access unavailable: $uri"
                    )
                }
            }

            importExecutor.execute {
                val ids = libraryStore.importUris(
                    selected.toList()
                )

                GwRuntimeLog.info(
                    "LIBRARY",
                    "Selected=${selected.size}, indexed=${ids.size}"
                )

                emitLibrary()

                ids.forEach { id ->
                    metadataExecutor.execute {
                        libraryStore.enrich(id)
                        emitLibrary()
                    }
                }
            }
        }

    private val mediaPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                scanDeviceMusic()
            }
        }

    private val webFileLauncher =
        registerForActivityResult(
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

    @SuppressLint(
        "SetJavaScriptEnabled",
        "JavascriptInterface"
    )
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        GwRuntimeLog.initialize(applicationContext)
        libraryStore = GwLibraryStore(applicationContext)

        val background = Color.rgb(11, 24, 32)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            true
        )

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.setBackgroundColor(background)

        val assetLoader =
            WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(this)
                )
                .build()

        bridge = GwBridge(
            context = this,
            libraryStore = libraryStore,
            requestTrackPicker = {
                launchTrackPicker()
            },
            requestDeviceScan = {
                requestDeviceMusicScan()
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
                builtInZoomControls = false
                displayZoomControls = false
            }

            addJavascriptInterface(
                bridge,
                "GwBridge"
            )

            webChromeClient =
                object : WebChromeClient() {
                    override fun onConsoleMessage(
                        message: ConsoleMessage
                    ): Boolean {
                        GwRuntimeLog.info(
                            "WEB_CONSOLE",
                            "${message.messageLevel()} ${message.message()}"
                        )
                        return true
                    }

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
                        } catch (error: Throwable) {
                            fileCallback = null
                            GwRuntimeLog.error(
                                "FILE_PICKER",
                                "Web chooser failed",
                                error
                            )
                            false
                        }
                    }
                }

            webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        if (
                            request.url.host ==
                            "appassets.androidplatform.net" &&
                            request.url.path
                                ?.startsWith("/tracks/") == true
                        ) {
                            return openTrackResponse(request)
                        }

                        return assetLoader
                            .shouldInterceptRequest(request.url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        return request.url.host !=
                            "appassets.androidplatform.net"
                    }

                    override fun onPageFinished(
                        view: WebView,
                        url: String
                    ) {
                        if (!fatalPageShown) {
                            injectBridge(view)
                        }
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
                        } else {
                            GwRuntimeLog.warn(
                                "WEB_RESOURCE",
                                "${request.url}: ${error.description}"
                            )
                        }
                    }
                }
        }

        setContentView(webView)
        loadHtml()
    }

    private fun launchTrackPicker() {
        val mimeTypes = arrayOf(
            "audio/*",
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/mpeg",
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "audio/opus",
            "application/ogg",
            "application/octet-stream"
        )

        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"

                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    true
                )

                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    mimeTypes
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                addFlags(
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        trackPicker.launch(intent)
    }

    override fun onStart() {
        super.onStart()

        if (::webView.isInitialized) {
            webView.onResume()
        }

        if (!routeCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(
                routeCallback,
                mainHandler
            )
            routeCallbackRegistered = true
        }

        emitRoute()
    }

    override fun onStop() {
        if (::webView.isInitialized) {
            webView.onPause()
        }

        if (routeCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(
                    routeCallback
                )
            }
            routeCallbackRegistered = false
        }

        super.onStop()
    }

    private fun requestDeviceMusicScan() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            scanDeviceMusic()
        } else {
            mediaPermission.launch(
                Manifest.permission.READ_MEDIA_AUDIO
            )
        }
    }

    private fun scanDeviceMusic() {
        importExecutor.execute {
            val added = libraryStore.scanMediaStore()

            GwRuntimeLog.info(
                "LIBRARY",
                "MediaStore scan added=$added"
            )

            emitLibrary()
        }
    }

    private fun emitLibrary() {
        if (
            !::webView.isInitialized ||
            !pageReady
        ) {
            return
        }

        val json = libraryStore.asJson()

        runOnUiThread {
            webView.evaluateJavascript(
                """
                (function () {
                  const data = $json;

                  if (
                    window.GW_LIBRARY &&
                    typeof window.GW_LIBRARY.replace === "function"
                  ) {
                    window.GW_LIBRARY.replace(data);
                  }

                  window.dispatchEvent(
                    new CustomEvent(
                      "gw:library-changed",
                      { detail: data }
                    )
                  );
                })();
                """.trimIndent(),
                null
            )
        }
    }

    private fun emitRoute() {
        if (
            !::webView.isInitialized ||
            !::bridge.isInitialized ||
            !pageReady
        ) {
            return
        }

        val json = bridge.currentOutputDevice()

        runOnUiThread {
            webView.evaluateJavascript(
                """
                window.dispatchEvent(
                  new CustomEvent(
                    "gw:route-changed",
                    { detail: $json }
                  )
                );
                """.trimIndent(),
                null
            )
        }
    }

    private fun openTrackResponse(
        request: WebResourceRequest
    ): WebResourceResponse {
        return try {
            val segments = request.url.pathSegments

            if (
                segments.size < 2 ||
                segments[0] != "tracks"
            ) {
                return errorResponse(
                    404,
                    "Not Found"
                )
            }

            val track =
                libraryStore.find(segments[1])
                    ?: return errorResponse(
                        404,
                        "Not Found"
                    )

            val descriptor =
                contentResolver.openAssetFileDescriptor(
                    Uri.parse(track.uri),
                    "r"
                ) ?: return errorResponse(
                    404,
                    "Not Found"
                )

            val length = when {
                descriptor.length > 0 ->
                    descriptor.length

                track.size > 0 ->
                    track.size

                else ->
                    -1L
            }

            val headers = mutableMapOf(
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            )

            if (length > 0) {
                headers["Content-Length"] =
                    length.toString()
            }

            if (
                request.method.equals(
                    "HEAD",
                    ignoreCase = true
                )
            ) {
                descriptor.close()

                return WebResourceResponse(
                    track.mime,
                    null,
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val body =
                ClosingInputStream(
                    descriptor.createInputStream()
                ) {
                    runCatching {
                        descriptor.close()
                    }
                }

            WebResourceResponse(
                track.mime,
                null,
                200,
                "OK",
                headers,
                body
            )
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "TRACK_STREAM",
                "Track stream failed: ${request.url}",
                error
            )

            errorResponse(
                500,
                "Internal Error"
            )
        }
    }

    private fun errorResponse(
        code: Int,
        reason: String
    ): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            code,
            reason,
            emptyMap(),
            ByteArrayInputStream(
                reason.toByteArray()
            )
        )
    }

    private class ClosingInputStream(
        input: InputStream,
        private val finished: () -> Unit
    ) : FilterInputStream(input) {
        private var closed = false

        override fun close() {
            if (closed) {
                return
            }

            closed = true
            runCatching { super.close() }
            finished()
        }
    }

    private fun optimizeHtmlForThermal(
        original: String
    ): String {
        val replacements = listOf(
            "ts-microLastFrame>45" to
                "ts-microLastFrame>80",

            "ts-soundLabLast>55" to
                "ts-soundLabLast>100",

            "if(ts-last<28)return" to
                "if(ts-last<50)return",

            "setInterval(()=>{if(labOpen())coreState()},350)" to
                "setInterval(()=>{if(labOpen())coreState()},750)",

            "setInterval(sync,400)" to
                "setInterval(sync,800)",

            "if(ts-last<45||document.hidden||fx()||!window.nwFx)return" to
                "if(ts-last<100||document.hidden||fx()||!window.nwFx)return"
        )

        return replacements.fold(original) {
                html,
                (before, after) ->
            check(
                html.windowed(before.length)
                    .count { it == before } == 1
            ) {
                "Thermal source marker mismatch: $before"
            }

            html.replace(before, after)
        }
    }

    private fun loadHtml() {
        try {
            val bytes =
                assets.open(HTML_ASSET).use {
                    it.readBytes()
                }

            check(bytes.size == EXPECTED_SIZE)
            check(sha256(bytes) == EXPECTED_SHA256)

            val original =
                bytes.toString(Charsets.UTF_8)

            val earlyGuard = """
                <script>
                (function () {
                  const nativeRequestFrame =
                    window.requestAnimationFrame.bind(window);

                  const nativeCancelFrame =
                    window.cancelAnimationFrame.bind(window);

                  const nativeSetInterval =
                    window.setInterval.bind(window);

                  const minimumFrameMs = 33;
                  const pendingFrames = new Map();
                  let nextFrameToken = 1;

                  window.requestAnimationFrame =
                    function (callback) {
                      const token = nextFrameToken++;
                      const requestedAt = performance.now();
                      const task = { nativeId: 0 };

                      const run = function (timestamp) {
                        if (!pendingFrames.has(token)) {
                          return;
                        }

                        if (
                          timestamp - requestedAt <
                          minimumFrameMs
                        ) {
                          task.nativeId =
                            nativeRequestFrame(run);
                          return;
                        }

                        pendingFrames.delete(token);
                        callback(timestamp);
                      };

                      pendingFrames.set(token, task);
                      task.nativeId =
                        nativeRequestFrame(run);

                      return token;
                    };

                  window.cancelAnimationFrame =
                    function (token) {
                      const task =
                        pendingFrames.get(token);

                      if (task) {
                        pendingFrames.delete(token);
                        nativeCancelFrame(task.nativeId);
                        return;
                      }

                      nativeCancelFrame(token);
                    };

                  window.setInterval =
                    function (handler, delay) {
                      try {
                        if (
                          typeof handler === "function" &&
                          handler.name === "detect"
                        ) {
                          return -1;
                        }
                      } catch (_) {}

                      return nativeSetInterval.apply(
                        window,
                        arguments
                      );
                    };
                })();
                </script>
            """.trimIndent()

            val thermalOptimized =
                optimizeHtmlForThermal(original)

            val html =
                thermalOptimized.replaceFirst(
                    "<head>",
                    "<head>$earlyGuard",
                    ignoreCase = true
                )

            webView.loadDataWithBaseURL(
                PAGE_URL,
                html,
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

    private fun labCleanupScript(): String {
        return """
            (function () {
              if (window.__GW_LAB_CLEANUP_V2__) return;
              window.__GW_LAB_CLEANUP_V2__ = true;

              function removeCardById(id) {
                const anchor =
                  document.getElementById(id);

                if (!anchor) return false;

                const card =
                  anchor.closest('.settings-card');

                if (!card) return false;

                card.remove();
                return true;
              }

              function cleanText(node) {
                return (node.textContent || '')
                  .replace(/\s+/g, ' ')
                  .trim()
                  .toLowerCase();
              }

              removeCardById('waveScope') ||
                removeCardById('freqScope') ||
                removeCardById('microFreeze');

              removeCardById('soundLabConsole') ||
                removeCardById('soundLabChip') ||
                removeCardById('soundLabPath');

              document
                .querySelectorAll('.section-head')
                .forEach(function (head) {
                  const text = cleanText(head);

                  if (
                    text.includes('sound lab') ||
                    text.includes('live signal bench')
                  ) {
                    head.remove();
                  }
                });

              document
                .querySelectorAll(
                  '#labPanel .settings-card'
                )
                .forEach(function (card) {
                  const text = cleanText(card);

                  if (
                    text.includes('audio microscope') ||
                    text.includes(
                      'sound lab · live signal bench'
                    ) ||
                    text.includes('live signal bench')
                  ) {
                    card.remove();
                  }
                });

              [
                'waveScope',
                'freqScope',
                'microPeak',
                'microFreq',
                'microWidth',
                'microFreeze',
                'microReset',
                'soundLabConsole',
                'soundLabChip',
                'soundLabPath',
                'slSub',
                'slBass',
                'slVocal',
                'slAir'
              ].forEach(function (id) {
                const node =
                  document.getElementById(id);

                if (node) {
                  node.remove();
                }
              });

              try {
                microFrozen = true;
              } catch (_) {}

              try {
                drawMicroscope = function () {};
              } catch (_) {}

              try {
                microscopeTick = function () {};
              } catch (_) {}

              try {
                bandLevel = function () {
                  return 0;
                };
              } catch (_) {}

              try {
                updateSoundLab = function () {};
              } catch (_) {}

              try {
                soundLabTick = function () {};
              } catch (_) {}

              const style =
                document.createElement('style');

              style.id =
                'gw-lab-cleanup-v2-style';

              style.textContent = `
                #waveScope,
                #freqScope,
                #microPeak,
                #microFreq,
                #microWidth,
                #microFreeze,
                #microReset,
                #soundLabConsole,
                #soundLabChip,
                #soundLabPath,
                #slSub,
                #slBass,
                #slVocal,
                #slAir {
                  display: none !important;
                }

                #labPanel .settings-card {
                  margin-top: 0;
                }
              `;

              document.head.appendChild(style);

              try {
                GwBridge.jsLog(
                  'INFO',
                  'LAB_CLEANUP_V2 applied'
                );
              } catch (_) {}
            })();
        """.trimIndent()
    }

    private fun injectBridge(
        view: WebView
    ) {
        try {
            val script =
                assets.open(JS_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

            val cleanup = labCleanupScript()

            view.evaluateJavascript(
                script + "\n" + cleanup
            ) {
                pageReady = true
                emitLibrary()
                emitRoute()

                view.postOnAnimation {
                    view.postOnAnimation {
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

            pageReady = true
            view.alpha = 1f
        }
    }

    private fun showFatalPage(
        message: String
    ) {
        if (fatalPageShown) {
            return
        }

        fatalPageShown = true
        pageReady = true
        webView.alpha = 1f

        val escaped =
            TextUtils.htmlEncode(message)

        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html>
            <html>
            <body style="
              margin:0;
              padding:24px;
              background:#0b1820;
              color:white;
              font-family:sans-serif">
              <h2>GLASS WAVE</h2>
              <pre>$escaped</pre>
            </body>
            </html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun sha256(
        bytes: ByteArray
    ): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
    }

    override fun onDestroy() {
        if (routeCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(
                    routeCallback
                )
            }
        }

        fileCallback?.onReceiveValue(null)
        fileCallback = null

        importExecutor.shutdownNow()
        metadataExecutor.shutdownNow()

        GwNativeAudio.closeSafely()

        webView.removeJavascriptInterface(
            "GwBridge"
        )

        webView.stopLoading()
        webView.destroy()

        super.onDestroy()
    }
}
