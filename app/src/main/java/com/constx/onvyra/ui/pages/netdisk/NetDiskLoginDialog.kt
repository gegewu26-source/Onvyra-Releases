package com.constx.onvyra.ui.pages.netdisk

import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Official pages retain their website session; no JavascriptInterface or native fetch bridge is added. */
@Composable
internal fun NetDiskLoginDialog(platform: NetDiskPlatform, onDismiss: () -> Unit,
    onCredential: (String) -> Unit, accountMode: Boolean = false, onAdvanced: (() -> Unit)? = null) {
    val initialUrl = remember(platform, accountMode) {
        if (accountMode) NetDiskLoginPolicy.accountUrl(platform) else NetDiskLoginPolicy.startUrl(platform)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentPageUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var address by remember(initialUrl) { mutableStateOf(NetDiskLoginPolicy.host(initialUrl).orEmpty()) }
    var loading by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pageFailure by remember { mutableStateOf<String?>(null) }
    var rendererGone by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    var loginEntryState by remember(currentPageUrl, generation) { mutableStateOf("waiting") }
    DisposableEffect(Unit) {
        onDispose {
            alive = false
            // Destroying a view never deletes its provider cookies or localStorage.
            CookieManager.getInstance().flush()
        }
    }
    // These official entry pages hydrate after onPageFinished. Wait for their actual form,
    // and open only the observed public login launcher rather than leaving a product home page.
    LaunchedEffect(webView, loading, currentPageUrl, generation, pageFailure, rendererGone) {
        val view = webView ?: return@LaunchedEffect
        val checkedUrl = currentPageUrl
        val script = NetDiskOfficialLoginPage.preparationScript(platform, checkedUrl, accountMode)
            ?: return@LaunchedEffect
        if (loading || rendererGone || pageFailure != null) return@LaunchedEffect
        repeat(40) {
            if (!alive || webView !== view || loading || rendererGone || pageFailure != null ||
                currentPageUrl != checkedUrl || view.url != checkedUrl) return@LaunchedEffect
            view.evaluateJavascript(script) { result ->
                if (alive && webView === view && !rendererGone && view.url == checkedUrl) {
                    loginEntryState = runCatching {
                        (Json.parseToJsonElement(result) as? JsonPrimitive)?.contentOrNull
                    }.getOrNull().orEmpty()
                }
            }
            delay(500)
            if (loginEntryState == "ready" || loginEntryState == "ignored") return@LaunchedEffect
        }
        if (alive && webView === view && view.url == checkedUrl && loginEntryState != "ready") {
            message = "官方登录组件暂未显示，请刷新后重试。"
        }
    }
    // A successful HTML response can still render nothing when its SPA bundle fails. Check only
    // a boolean after the document settles; page text, credentials and file names never leave JS.
    LaunchedEffect(webView, loading, currentPageUrl, generation, pageFailure, rendererGone) {
        val view = webView
        val checkedUrl = currentPageUrl
        if (view == null || loading || rendererGone || pageFailure != null) return@LaunchedEffect
        delay(8_000)
        if (!alive || webView !== view || loading || rendererGone || pageFailure != null ||
            currentPageUrl != checkedUrl || view.url != checkedUrl) return@LaunchedEffect
        runCatching {
            view.evaluateJavascript(NETDISK_VISIBLE_PAGE_SCRIPT) { result ->
                if (alive && webView === view && !loading && !rendererGone && pageFailure == null &&
                    currentPageUrl == checkedUrl && view.url == checkedUrl && result == "false") {
                    pageFailure = "网盘网页未显示内容，可能是页面资源未加载。请刷新或重新登录后重试。"
                }
            }
        }
    }
    fun navigate(url: String) {
        message = null
        pageFailure = null
        currentPageUrl = url
        if (rendererGone) {
            rendererGone = false
            generation++
        } else webView?.loadUrl(url)
    }
    fun retry() {
        message = null
        pageFailure = null
        if (rendererGone) {
            rendererGone = false
            generation++
        } else webView?.reload()
    }
    fun readCredential() {
        val view = webView ?: return
        if (!NetDiskLoginPolicy.mayLoad(platform, view.url, true)) {
            message = "请回到网盘官网完成登录后再导入"
            return
        }
        reading = true
        val cookies = NetDiskLoginPolicy.cookieUrls(platform).map {
            CookieManager.getInstance().getCookie(it).orEmpty()
        }
        val credential = NetDiskLoginPolicy.mergeLoginCookies(platform, cookies)
        if (credential != null) {
            reading = false
            CookieManager.getInstance().flush()
            onCredential(credential)
            return
        }
        val script = NetDiskLoginPolicy.tokenScript(platform)
        if (script == null) {
            reading = false
            message = if (NetDiskLoginPolicy.cookieUrls(platform).isEmpty())
                "此平台暂需手动填写 Token，请返回账号设置粘贴。" else "尚未读取到登录凭据，请先在官网完成登录。"
            return
        }
        if (!NetDiskLoginPolicy.mayReadToken(platform, view.url)) {
            reading = false
            message = "登录完成后请回到网盘首页，再导入登录信息。"
            return
        }
        val tokenOrigin = view.url
        view.evaluateJavascript(script) { result ->
            if (!alive || webView !== view || rendererGone) return@evaluateJavascript
            reading = false
            if (view.url != tokenOrigin || !NetDiskLoginPolicy.mayReadToken(platform, view.url)) {
                message = "页面发生跳转，请回到网盘首页后重新导入。"
                return@evaluateJavascript
            }
            val value = runCatching { (Json.parseToJsonElement(result) as? JsonPrimitive)?.contentOrNull }.getOrNull()
            if (value.isNullOrBlank() || value.length > 16384 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                message = "尚未读取到登录凭据，请登录后回到网盘页面再导入。"
            } else onCredential(value)
        }
    }

    Dialog(onDismissRequest = {
        if (!rendererGone && webView?.canGoBack() == true) webView?.goBack() else onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(if (accountMode) "${platform.label} · 我的文件" else platform.label,
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(address, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = ::retry) { Text("刷新") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { webView?.goBack() }, enabled = canGoBack && !rendererGone) { Text("返回上页") }
                    TextButton(onClick = { navigate(NetDiskLoginPolicy.accountUrl(platform)) }) { Text("我的文件") }
                    if (accountMode) TextButton(onClick = ::readCredential, enabled = !reading && !rendererGone) { Text("保存登录") }
                    else onAdvanced?.let { action -> TextButton(onClick = action) { Text("导入凭据") } }
                }
                if (loading || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val failure = pageFailure
                if (failure != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(failure, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = ::retry) { Text("重新加载") }
                    }
                }
                message?.let {
                    Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!rendererGone) key(platform, accountMode, generation) {
                    AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                        val zoomOrigins = NetDiskWebZoomPolicy.origins(platform)
                        (if (zoomOrigins.isEmpty()) WebView(context) else NetDiskZoomWebView(context, zoomOrigins)).apply {
                            webView = this
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                                setGeolocationEnabled(false)
                                safeBrowsingEnabled = true
                                mediaPlaybackRequiresUserGesture = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                if (platform != NetDiskPlatform.C139 || accountMode) userAgentString =
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean = true
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    if (request == null) return true
                                    val accepted = NetDiskLoginPolicy.mayLoad(platform, request.url.toString(), request.isForMainFrame)
                                    if (!accepted && request.isForMainFrame) message = "此链接不是当前网盘的官网页面。请返回“我的文件”继续。"
                                    return !accepted
                                }
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    if (request == null || !NetDiskLoginPolicy.mayLoad(platform, request.url.toString(), request.isForMainFrame)) {
                                        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loading = true
                                    pageFailure = null
                                    message = null
                                    if (NetDiskLoginPolicy.mayLoad(platform, url, true) && url != null) currentPageUrl = url
                                    address = NetDiskLoginPolicy.host(url).orEmpty()
                                }
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    canGoBack = view?.canGoBack() == true
                                    if (url != null && NetDiskLoginPolicy.mayLoad(platform, url, true)) currentPageUrl = url
                                    address = NetDiskLoginPolicy.host(url).orEmpty()
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    canGoBack = view?.canGoBack() == true
                                    if (view is NetDiskZoomWebView && NetDiskLoginPolicy.mayLoad(platform, url, true))
                                        view.applyZoomPolicy()
                                    CookieManager.getInstance().flush()
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        pageFailure = "网盘网页未能加载，请检查网络后重试（${error?.errorCode ?: 0}）。"
                                    }
                                }
                                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        pageFailure = "网盘网页暂时无法打开（HTTP ${response?.statusCode ?: 0}），请刷新或重新登录。"
                                    }
                                }
                                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                    handler?.cancel()
                                    pageFailure = "官网证书校验失败，请稍后重试。"
                                    loading = false
                                }
                                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                    loading = false
                                    pageFailure = "网盘网页意外关闭，点击重新加载即可恢复。"
                                    rendererGone = true
                                    return true
                                }
                            }
                            loadUrl(currentPageUrl)
                        }
                    }, onRelease = { view ->
                        if (webView === view) webView = null
                        view.stopLoading()
                        view.webChromeClient = null
                        view.webViewClient = WebViewClient()
                        view.destroy()
                    })
                }
                if (!accountMode) Button(onClick = ::readCredential, enabled = !reading && !rendererGone,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { Text("使用此登录信息") }
            }
        }
    }
}

/** Passive rendering probe: its only native-visible result is true/false, never document content. */
private val NETDISK_VISIBLE_PAGE_SCRIPT = """
    (() => { try {
      const body = document.body;
      if (!body) return false;
      const width = window.innerWidth || document.documentElement.clientWidth;
      const height = window.innerHeight || document.documentElement.clientHeight;
      const intersects = r => r.width >= 3 && r.height >= 6 && r.right > 0 && r.bottom > 0 && r.left < width && r.top < height;
      const styledVisible = element => {
        for (let e = element, depth = 0; e && depth < 40; e = e.parentElement, depth++) {
          const s = window.getComputedStyle(e);
          if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse' || Number(s.opacity) <= 0.01) return false;
        }
        return true;
      };
      const text = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
      for (let node, count = 0; (node = text.nextNode()) && count < 3000; count++) {
        const parent = node.parentElement;
        if (!parent || /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/i.test(parent.tagName) || !node.nodeValue.trim() || !styledVisible(parent)) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        if (Array.from(range.getClientRects()).some(intersects)) return true;
      }
      const controls = body.querySelectorAll('input:not([type="hidden"]),button,select,textarea,img,canvas,video,iframe,svg,[role="progressbar"],[role="status"],[aria-busy="true"],.ant-spin,.el-loading-spinner,.loading-spinner,.spinner');
      for (let i = 0; i < controls.length && i < 600; i++) {
        const element = controls[i];
        if (!styledVisible(element)) continue;
        const r = element.getBoundingClientRect();
        if (r.width >= 16 && r.height >= 16 && intersects(r)) return true;
      }
      return false;
    } catch (_) { return null; } })()
""".trimIndent()
