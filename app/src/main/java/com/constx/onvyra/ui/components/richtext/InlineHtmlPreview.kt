package com.constx.onvyra.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.UUID

/** An inline demonstration, with its own empty origin and no access to app files or native APIs. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InlineHtmlPreview(code: String, modifier: Modifier = Modifier) {
    val document = remember(code) {
        Jsoup.parse(code).apply {
            head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "form-action 'none'; object-src 'none'")
            if (selectFirst("meta[name=viewport]") == null) {
                head().prependElement("meta").attr("name", "viewport")
                    .attr("content", "width=device-width, initial-scale=1")
            }
        }.outerHtml()
    }
    val origin = remember { "https://${UUID.randomUUID()}.preview.invalid/" }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { PreviewHolder() }
    var failed by remember(code) { mutableStateOf(false) }

    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier.clip(RoundedCornerShape(4.dp)).testTag("inline-html-preview")) {
        if (failed) {
            Text("预览暂时不可用，可切换查看代码", modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PreviewWebView(context).apply {
                        holder.view = this
                        tag = "inline-html-preview-webview"
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            @Suppress("DEPRECATION")
                            allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            setGeolocationEnabled(false)
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            builtInZoomControls = true
                            displayZoomControls = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            // A generated demo must stay in its slot instead of navigating the chat.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val uri = request.url
                                if (!request.isForMainFrame && uri.scheme in setOf("data", "blob")) return null
                                if (uri.scheme == "https" && uri.host != "onvyra.local" &&
                                    !uri.host.orEmpty().endsWith(".preview.invalid")
                                ) return null
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                (view as PreviewWebView).releasePreview()
                                failed = true
                                return true
                            }
                        }
                        // Suppress native prompts, file/permission requests and separate windows.
                        webChromeClient = object : WebChromeClient() {
                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                                result.cancel()
                                return true
                            }
                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                                result.cancel()
                                return true
                            }
                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                                result.cancel()
                                return true
                            }
                            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                        }
                    }
                },
                update = { view ->
                    holder.view = view
                    if (view.loadedDocument != document) {
                        view.loadedDocument = document
                        view.loadDataWithBaseURL(origin, document, "text/html", "UTF-8", null)
                    }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume() else view.onPause()
                },
                onReset = { view ->
                    view.stopLoading()
                    view.onPause()
                    view.loadUrl("about:blank")
                    view.loadedDocument = null
                },
                onRelease = { view ->
                    if (holder.view === view) holder.view = null
                    view.releasePreview()
                },
            )
        }
    }
}

private class PreviewHolder(var view: PreviewWebView? = null)

@SuppressLint("ViewConstructor")
private class PreviewWebView(context: Context) : WebView(context) {
    var loadedDocument: String? = null
    private var released = false

    fun releasePreview() {
        if (released) return
        released = true
        stopLoading()
        webChromeClient = null
        webViewClient = WebViewClient()
        loadUrl("about:blank")
        clearHistory()
        removeAllViews()
        destroy()
    }
}
