package com.constx.onvyra.ui.pages.netdisk

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** The real official password/SMS component owns its CSRF, RSA, agreement and risk challenge flow. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NetDiskAliyunLoginContent(onCredential: suspend (String) -> Unit,
    onOfficial: () -> Unit, onAdvanced: () -> Unit) {
    val currentOnCredential by rememberUpdatedState(onCredential)
    var generation by remember { mutableStateOf(0) }
    val nonce = remember(generation) { UUID.randomUUID().toString().replace("-", "") }
    val supported = remember(generation) {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
    var webView by remember { mutableStateOf<AliyunOfficialLoginWebView?>(null) }
    var loading by remember(generation) { mutableStateOf(true) }
    var failure by remember(generation) { mutableStateOf<String?>(null) }
    var rendererGone by remember(generation) { mutableStateOf(false) }
    var pendingToken by remember(generation) { mutableStateOf<String?>(null) }
    var saving by remember(generation) { mutableStateOf(false) }
    var saveAttempt by remember(generation) { mutableStateOf(0) }
    var received by remember(generation) { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false; CookieManager.getInstance().flush() } }
    LaunchedEffect(pendingToken, saveAttempt, nonce) {
        val token = pendingToken ?: return@LaunchedEffect
        saving = true
        try {
            currentOnCredential(token)
            pendingToken = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "登录信息保存失败，请点击重试保存。" }
        finally { saving = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)
        .testTag("netdisk-aliyun-login"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp),
            border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetDiskProviderBadge(NetDiskPlatform.ALIYUN, Modifier.size(36.dp))
                    Column {
                        Text("登录阿里云盘", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("密码或短信验证码登录", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!supported) {
                    Text("当前系统 WebView 不支持本页登录回调，请更新系统 WebView 或使用官方登录。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("netdisk-aliyun-login-error")) }
                    if (!rendererGone) key(nonce) {
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("netdisk-aliyun-official-form"),
                            factory = { context ->
                                val capturedNonce = nonce
                                AliyunOfficialLoginWebView(context).apply {
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
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        builtInZoomControls = false
                                        textZoom = 100
                                    }
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
                                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?) = true
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            if (request == null) return true
                                            val accepted = NetDiskLoginPolicy.mayLoad(NetDiskPlatform.ALIYUN,
                                                request.url.toString(), request.isForMainFrame) &&
                                                (!request.isForMainFrame || NetDiskAliyunLoginCapture.mayNavigateTop(request.url.toString()))
                                            if (!accepted && request.isForMainFrame) failure = "此链接不是阿里云盘登录页面，请返回重试。"
                                            return !accepted
                                        }
                                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                            if (request == null || !NetDiskLoginPolicy.mayLoad(NetDiskPlatform.ALIYUN,
                                                    request.url.toString(), request.isForMainFrame) ||
                                                (request.isForMainFrame && !NetDiskAliyunLoginCapture.mayNavigateTop(request.url.toString()))) {
                                                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                                                    ByteArrayInputStream(ByteArray(0)))
                                            }
                                            return null
                                        }
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            loading = true
                                            if (pendingToken == null) failure = null
                                        }
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            loading = false
                                            CookieManager.getInstance().flush()
                                        }
                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            if (request?.isForMainFrame == true) {
                                                loading = false; failure = "登录页面未能加载，请检查网络后重试。"
                                            }
                                        }
                                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                                            if (request?.isForMainFrame == true) {
                                                loading = false; failure = "登录页面暂时无法打开，请稍后重试。"
                                            }
                                        }
                                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                            handler?.cancel(); loading = false; failure = "登录页面证书校验失败，请稍后重试。"
                                        }
                                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                            loading = false; rendererGone = true; failure = "登录页面意外关闭，请点击重新加载。"
                                            return true
                                        }
                                    }
                                    try {
                                        WebViewCompat.addWebMessageListener(this, NetDiskAliyunLoginCapture.MESSAGE_OBJECT,
                                            setOf(NetDiskAliyunLoginCapture.CALLBACK_ORIGIN)) { view, message, origin, mainFrame, _ ->
                                            if (alive && webView === view && capturedNonce == nonce && !received) {
                                                val token = NetDiskAliyunLoginCapture.finalAccessToken(origin.toString(), mainFrame,
                                                    view.url, runCatching { message.data }.getOrNull(), capturedNonce)
                                                if (token != null) { received = true; pendingToken = token }
                                            }
                                        }
                                        messageListenerInstalled = true
                                        captureScript = WebViewCompat.addDocumentStartJavaScript(this,
                                            NetDiskAliyunLoginCapture.documentStartScript(capturedNonce),
                                            setOf(NetDiskAliyunLoginCapture.CALLBACK_ORIGIN))
                                        loadUrl(NetDiskAliyunLoginCapture.LOGIN_URL)
                                    } catch (_: Exception) {
                                        loading = false
                                        failure = "登录组件未能初始化，请重新加载或使用官方登录。"
                                    }
                                }
                            }, onRelease = { view ->
                                if (webView === view) webView = null
                                view.stopLoading()
                                runCatching { view.captureScript?.remove() }
                                if (view.messageListenerInstalled) runCatching {
                                    WebViewCompat.removeWebMessageListener(view, NetDiskAliyunLoginCapture.MESSAGE_OBJECT)
                                }
                                view.webChromeClient = null
                                view.webViewClient = WebViewClient()
                                view.destroy()
                            })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                if (!saving) {
                    if (pendingToken != null) saveAttempt++ else generation++
                }
            }, enabled = !saving) { Text(if (pendingToken != null) "重试保存" else "重新加载") }
            TextButton(onClick = onOfficial, enabled = !saving) { Text("官方登录") }
            TextButton(onClick = onAdvanced, enabled = !saving) { Text("凭据管理") }
        }
    }
}

private class AliyunOfficialLoginWebView(context: Context) : WebView(context) {
    var captureScript: ScriptHandler? = null
    var messageListenerInstalled: Boolean = false
}
