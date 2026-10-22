package kz.glasswave.player

import android.annotation.SuppressLint
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

    private var fileCallback:
        ValueCallback<Array<Uri>>? = null

    private var fatalPageShown = false
    private var pageRevealed = false

    private val fileLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val files =
                WebChromeClient.FileChooserParams.parseResult(
                    result.resultCode,
                    result.data
                )

            fileCallback?.onReceiveValue(files)
            fileCallback = null
        }

    @SuppressLint(
        "SetJavaScriptEnabled",
        "JavascriptInterface"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GwRuntimeLog.initialize(applicationContext)
        GwRuntimeLog.info(
            "ACTIVITY",
            "MainActivity.onCreate"
        )

        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Жаңа GLASS WAVE палитрасының негізгі тонына
        // сәйкес first-frame түсі. Күлгін ешқашан көрінбейді.
        val firstFrameColor = Color.rgb(11, 24, 32)

        window.statusBarColor = firstFrameColor
        window.navigationBarColor = firstFrameColor
        window.decorView.setBackgroundColor(firstFrameColor)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val assetLoader =
            WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader
                        .AssetsPathHandler(this)
                )
                .build()

        webView = WebView(this).apply {
            // HTML + injected bridge DOM-ды жаңартып болғанша
            // WebView көрінбейді. Ескі күлгін first frame жоқ.
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

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW

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
                            "${message.sourceId()}:" +
                            "${message.lineNumber()} " +
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
                            "Не удалось открыть выбор файла",
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
                    GwRuntimeLog.info("PAGE", "Finished: $url")

                    if (!fatalPageShown) {
                        injectGwBridge(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    GwRuntimeLog.error(
                        "WEB_ERROR",
                        "url=${request.url}, " +
                            "main=${request.isForMainFrame}, " +
                            "code=${error.errorCode}, " +
                            "description=${error.description}"
                    )

                    if (request.isForMainFrame) {
                        showFatalPage(
                            "GLASS WAVE не удалось запустить.\n\n" +
                                "Код: ${error.errorCode}\n" +
                                "Описание: ${error.description}"
                        )
                    }
                }
            }
        }

        setContentView(webView)
        loadOriginalHtml()
    }

    private fun loadOriginalHtml() {
        try {
            val bytes = assets.open(HTML_ASSET).use {
                it.readBytes()
            }

            val hash = sha256(bytes)

            GwRuntimeLog.info(
                "ASSET",
                "$HTML_ASSET bytes=${bytes.size}, sha256=$hash"
            )

            check(bytes.size == EXPECTED_SIZE) {
                "HTML size mismatch: ${bytes.size}"
            }

            check(hash == EXPECTED_SHA256) {
                "HTML SHA-256 mismatch: $hash"
            }

            val html = bytes.toString(Charsets.UTF_8)

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
                "Не удалось открыть $HTML_ASSET",
                error
            )

            showFatalPage(
                "GLASS WAVE не удалось запустить.\n\n" +
                    error.stackTraceToString()
            )
        }
    }

    private fun injectGwBridge(view: WebView) {
        try {
            val script = assets.open(JS_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            view.evaluateJavascript(
                """
                (function () {
                  try {
                    $script
                  } catch (error) {
                    try {
                      GwBridge.jsError(
                        error && error.stack
                          ? error.stack
                          : String(error)
                      );
                    } catch (_) {}
                  }
                })();
                """.trimIndent()
            ) {
                revealFinishedPage(view)
            }
        } catch (error: Throwable) {
            GwRuntimeLog.error(
                "JS_INJECT",
                "Не удалось внедрить $JS_ASSET",
                error
            )
            revealFinishedPage(view)
        }
    }

    private fun revealFinishedPage(view: WebView) {
        if (pageRevealed) return

        // Екі animation frame күтеміз:
        // inline <style> мен injected bridge DOM-ды
        // жаңа палитрамен толық сурет салып үлгереді.
        view.postOnAnimation {
            view.postOnAnimation {
                if (!pageRevealed) {
                    pageRevealed = true
                    view.alpha = 1f

                    GwRuntimeLog.info(
                        "PAGE",
                        "UI revealed"
                    )
                }
            }
        }
    }

    private fun showFatalPage(message: String) {
        if (fatalPageShown) return

        fatalPageShown = true
        pageRevealed = true
        webView.alpha = 1f

        val escaped = TextUtils.htmlEncode(message)

        val page = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport"
                    content="width=device-width,initial-scale=1">
              <title>GLASS WAVE</title>
              <style>
                html,body{
                  margin:0;
                  min-height:100%;
                  background:#0b1820;
                  color:#f1f3ff;
                  font-family:system-ui,sans-serif
                }
                main{padding:28px 20px}
                h1{font-size:20px;color:#eadfc6}
                pre{
                  white-space:pre-wrap;
                  word-break:break-word;
                  background:#111b24;
                  border:1px solid #40505e;
                  border-radius:16px;
                  padding:16px;
                  line-height:1.45
                }
              </style>
            </head>
            <body>
              <main>
                <h1>GLASS WAVE</h1>
                <pre>$escaped</pre>
              </main>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(
            null, page, "text/html", "UTF-8", null
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null

        GwNativeAudio.closeSafely()

        webView.removeJavascriptInterface("GwBridge")
        webView.stopLoading()
        webView.destroy()

        super.onDestroy()
    }
}
