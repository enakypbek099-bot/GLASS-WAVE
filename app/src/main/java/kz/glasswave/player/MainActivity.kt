package kz.glasswave.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
            }

            addJavascriptInterface(
                GwBridge(this@MainActivity),
                "GwBridge"
            )

            webChromeClient = object : WebChromeClient() {
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
                    } catch (_: Exception) {
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
                    return loader.shouldInterceptRequest(request.url)
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
                    val bridgeScript = assets
                        .open("js/gw-native.js")
                        .bufferedReader()
                        .use { it.readText() }

                    view.evaluateJavascript(bridgeScript, null)
                }
            }
        }

        setContentView(webView)

        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/GLASS_WAVE.html"
        )
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        GwNativeAudio.nativeClose()
        webView.removeJavascriptInterface("GwBridge")
        webView.destroy()
        super.onDestroy()
    }
}
