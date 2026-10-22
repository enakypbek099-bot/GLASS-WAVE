package kz.glasswave.player

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    companion object {
        private const val HTML_ASSET = "GLASS_WAVE.html"
        private const val JS_ASSET = "js/gw-native.js"

        private const val PAGE_URL =
            "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"

        private const val EXPECTED_HTML_SIZE = 531623

        private const val EXPECTED_HTML_SHA256 =
            "6c715249c7331a45b259aec5c99275eb9d2ccc6f728340d0bb0d6011598a7b2c"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fatalPageShown = false

    private var diagnosticTapCount = 0
    private var diagnosticTapStarted = 0L

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val files = WebChromeClient.FileChooserParams.parseResult(
            result.resultCode,
            result.data
        )

        fileCallback?.onReceiveValue(files)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GwRuntimeLog.initialize(applicationContext)
        GwRuntimeLog.info("ACTIVITY", "MainActivity.onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.statusBarColor = Color.rgb(5, 7, 11)
        window.navigationBarColor = Color.rgb(5, 7, 11)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 16))

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

            addJavascriptInterface(
                GwBridge(this@MainActivity),
                "GwBridge"
            )

            webChromeClient = object : WebChromeClient() {

                override fun onConsoleMessage(
                    message: ConsoleMessage
                ): Boolean {
                    GwRuntimeLog.info(
                        "WEB_CONSOLE",
                        "${message.messageLevel()} " +
                            "${message.sourceId()}:${message.lineNumber()} " +
                            message.message()
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
                        fileLauncher.launch(params.createIntent())
                        true
                    } catch (error: Throwable) {
                        GwRuntimeLog.error(
                            "FILE_CHOOSER",
                            "Не удалось открыть системный выбор файла",
                            error
                        )
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
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val local =
                        request.url.host == "appassets.androidplatform.net"

                    if (!local) {
                        GwRuntimeLog.warn(
                            "NAVIGATION",
                            "Заблокирован внешний URL: ${request.url}"
                        )
                    }

                    return !local
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    GwRuntimeLog.info("PAGE", "Started: $url")
                }

                override fun onPageCommitVisible(
                    view: WebView,
                    url: String
                ) {
                    GwRuntimeLog.info("PAGE", "Commit visible: $url")
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String
                ) {
                    GwRuntimeLog.info("PAGE", "Finished: $url")

                    if (!fatalPageShown) {
                        injectGwBridge(view)
                        runDomProbe(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    GwRuntimeLog.error(
                        "WEB_ERROR",
                        "url=${request.url}, main=${request.isForMainFrame}, " +
                            "code=${error.errorCode}, description=${error.description}"
                    )

                    if (request.isForMainFrame) {
                        showFatalPage(
                            "Ошибка загрузки WebView\n" +
                                "URL: ${request.url}\n" +
                                "Код: ${error.errorCode}\n" +
                                "Описание: ${error.description}"
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse
                ) {
                    GwRuntimeLog.error(
                        "HTTP_ERROR",
                        "url=${request.url}, main=${request.isForMainFrame}, " +
                            "status=${response.statusCode}"
                    )

                    if (request.isForMainFrame) {
                        showFatalPage(
                            "HTML не загружен\n" +
                                "HTTP status: ${response.statusCode}\n" +
                                "URL: ${request.url}"
                        )
                    }
                }
            }
        }

        setContentView(webView)
        installDiagnosticGesture()
        loadOriginalHtml()
    }

    private fun loadOriginalHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use { it.readBytes() }
            val hash = sha256(bytes)

            GwRuntimeLog.info(
                "ASSET",
                "$HTML_ASSET bytes=${bytes.size}, sha256=$hash"
            )

            if (bytes.size != EXPECTED_HTML_SIZE) {
                GwRuntimeLog.warn(
                    "ASSET",
                    "Ожидалось $EXPECTED_HTML_SIZE байт, получено ${bytes.size}"
                )
            }

            if (hash != EXPECTED_HTML_SHA256) {
                GwRuntimeLog.warn(
                    "ASSET",
                    "SHA-256 отличается от исходного дизайна"
                )
            }

            val html = bytes.toString(Charsets.UTF_8)

            check(html.contains("<html", ignoreCase = true)) {
                "В asset отсутствует тег <html>"
            }

            check(html.contains("<body", ignoreCase = true)) {
                "В asset отсутствует тег <body>"
            }

            GwRuntimeLog.info(
                "ASSET",
                "HTML подтверждён; загрузка через loadDataWithBaseURL"
            )

            webView.loadDataWithBaseURL(
                PAGE_URL,
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "ASSET",
                "Не удалось прочитать $HTML_ASSET",
                error
            )

            showFatalPage(
                "GLASS_WAVE.html не удалось открыть.\n\n" +
                    error.stackTraceToString()
            )
        }
    }

    private fun injectGwBridge(view: WebView) {
        try {
            val script = assets.open(JS_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            GwRuntimeLog.info(
                "JS_INJECT",
                "$JS_ASSET chars=${script.length}"
            )

            val wrapped = """
                (function () {
                  try {
                    $script
                  } catch (error) {
                    try {
                      GwBridge.jsError(
                        "GW bootstrap: " +
                        (error && error.stack ? error.stack : String(error))
                      );
                    } catch (_) {}
                  }
                })();
            """.trimIndent()

            view.evaluateJavascript(wrapped) { result ->
                GwRuntimeLog.info(
                    "JS_INJECT",
                    "evaluateJavascript result=$result"
                )
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "JS_INJECT",
                "Не удалось прочитать или внедрить $JS_ASSET",
                error
            )
        }
    }

    private fun runDomProbe(view: WebView) {
        view.postDelayed({
            val probe = """
                (function () {
                  try {
                    var body = document.body;
                    var style = body ? getComputedStyle(body) : null;
                    return JSON.stringify({
                      readyState: document.readyState,
                      title: document.title,
                      body: !!body,
                      bodyChildren: body ? body.children.length : -1,
                      shell: !!document.querySelector(".shell"),
                      bodyDisplay: style ? style.display : "none",
                      bodyVisibility: style ? style.visibility : "none",
                      width: document.documentElement.scrollWidth,
                      height: document.documentElement.scrollHeight
                    });
                  } catch (error) {
                    return "DOM_PROBE_ERROR: " + String(error);
                  }
                })();
            """.trimIndent()

            view.evaluateJavascript(probe) { result ->
                GwRuntimeLog.info("DOM_PROBE", result ?: "null")
            }
        }, 500L)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDiagnosticGesture() {
        val density = resources.displayMetrics.density

        webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val insideCorner =
                    event.x >= view.width - (110f * density) &&
                    event.y <= 150f * density

                if (insideCorner) {
                    val now = SystemClock.elapsedRealtime()

                    if (now - diagnosticTapStarted > 3000L) {
                        diagnosticTapStarted = now
                        diagnosticTapCount = 0
                    }

                    diagnosticTapCount++

                    if (diagnosticTapCount >= 5) {
                        diagnosticTapCount = 0
                        copyDiagnostics()
                    }
                }
            }

            false
        }
    }

    private fun copyDiagnostics() {
        val text = GwRuntimeLog.snapshot()

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "GLASS WAVE runtime diagnostics",
                text
            )
        )

        Toast.makeText(
            this,
            "GW runtime log скопирован",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showFatalPage(message: String) {
        if (fatalPageShown) return
        fatalPageShown = true

        val escaped = TextUtils.htmlEncode(message)

        val page = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport"
                    content="width=device-width,initial-scale=1">
              <title>GLASS WAVE — диагностика</title>
              <style>
                html,body{margin:0;background:#080910;color:#f1f3ff;
                font-family:system-ui,sans-serif;min-height:100%}
                main{padding:28px 20px}
                h1{font-size:20px;color:#eadfc6}
                pre{white-space:pre-wrap;word-break:break-word;
                background:#11131f;border:1px solid #37334f;
                border-radius:16px;padding:16px;line-height:1.45}
              </style>
            </head>
            <body>
              <main>
                <h1>GW Runtime Diagnostics</h1>
                <pre>$escaped</pre>
                <p>Нажмите 5 раз в правом верхнем углу,
                чтобы скопировать журнал.</p>
              </main>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(
            null,
            page,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        GwRuntimeLog.info("ACTIVITY", "MainActivity.onDestroy")

        fileCallback?.onReceiveValue(null)
        fileCallback = null

        GwNativeAudio.closeSafely()

        webView.removeJavascriptInterface("GwBridge")
        webView.stopLoading()
        webView.destroy()

        super.onDestroy()
    }
}
