package com.constx.onvyra.data.ai.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A disposable, invisible reader; deliberately separate from every user-facing WebView.
 * The document has an opaque data: origin, so it cannot read website cookies or local storage.
 * Chromium networking is disabled. Only intercepted, validated, cookie-free GETs can return data.
 */
internal class AndroidDynamicWebPageRenderer(context: Context) : DynamicWebPageRenderer {
    private val context = context.applicationContext
    private val gate = Semaphore(1)

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun render(html: String, url: String, resources: WebResourceLoader): RenderedWebPage = gate.withPermit {
        withTimeoutOrNull(12_000) {
            withContext(Dispatchers.Main.immediate) {
                val parentContext = currentCoroutineContext()
                val requests = AtomicInteger()
                val bytesRead = AtomicLong()
                val rendererGone = AtomicBoolean()
                val view = WebView(context)
                try {
                    view.setBackgroundColor(Color.WHITE)
                    view.settings.apply {
                        javaScriptEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        domStorageEnabled = false
                        databaseEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    view.webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            rendererGone.set(true)
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // loadData itself may pass through interception. Let only that opaque,
                            // in-memory main document load; every remote main-frame navigation stays blocked.
                            if (request.isForMainFrame && request.url.scheme == "data") return null
                            if (request.isForMainFrame || request.method != "GET" || request.url.scheme !in setOf("http", "https")) return blockedResponse()
                            if (requests.incrementAndGet() > 48) return blockedResponse()
                            val path = request.url.path.orEmpty().lowercase()
                            if (Regex("\\.(?:png|jpe?g|gif|webp|svg|ico|woff2?|ttf|mp[34]|webm)(?:$)").containsMatchIn(path)) return blockedResponse()
                            return try {
                                // Never forward WebView headers, Cookie, Authorization or response Set-Cookie.
                                val resource = runBlocking(parentContext + Dispatchers.IO) { resources.load(request.url.toString()) }
                                if (bytesRead.addAndGet(resource.bytes.size.toLong()) > 8L * 1024 * 1024) return blockedResponse()
                                WebResourceResponse(
                                    resource.contentType, resource.charset ?: "UTF-8", 200, "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                                    ByteArrayInputStream(resource.bytes),
                                )
                            } catch (_: Exception) {
                                blockedResponse()
                            }
                        }
                    }
                    // Layout supplies a real viewport for responsive scripts and computed visibility.
                    view.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 1080, 1200)
                    val prepared = prepareDynamicReaderHtml(html, url)
                    view.loadData(Base64.encodeToString(prepared.toByteArray(), Base64.NO_WRAP), "text/html", "base64")
                    var previous = ""
                    var stable = 0
                    var content: WebPageContent? = null
                    var domTruncated = false
                    repeat(36) { index ->
                        delay(250)
                        check(!rendererGone.get()) { "The temporary JavaScript renderer stopped." }
                        val snapshot = view.readVisibleHtml()
                        val visibleHtml = snapshot.html
                        domTruncated = snapshot.truncated
                        if (visibleHtml.isNotBlank()) {
                            val current = extractWebPageContent(visibleHtml, url, MAX_WEB_DOCUMENT_CHARS)
                            content = current
                            stable = if (current.text == previous) stable + 1 else 0
                            previous = current.text
                            // Give async GETs and delayed hydration at least 1.5 seconds to settle.
                            if (index >= 5 && stable >= 3 && current.text.isNotBlank() && !needsDynamicWebPage(visibleHtml, current.text)) {
                                return@withContext RenderedWebPage(current, domTruncated || requests.get() > 48 || bytesRead.get() > 8L * 1024 * 1024)
                            }
                        }
                    }
                    RenderedWebPage(content ?: error("JavaScript did not return visible page text."),
                        domTruncated || requests.get() > 48 || bytesRead.get() > 8L * 1024 * 1024)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        view.stopLoading()
                        view.webViewClient = WebViewClient()
                        view.webChromeClient = null
                        view.removeAllViews()
                        view.destroy()
                    }
                }
            }
        } ?: throw java.io.IOException("JavaScript rendering exceeded its 12 second time limit.")
    }
}

private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))

internal fun prepareDynamicReaderHtml(html: String, url: String): String {
    val document = Jsoup.parse(html, url)
    document.select("base, meta[http-equiv=refresh], iframe, frame, object, embed").remove()
    val head = document.head()
    head.prependElement("meta").attr("name", "onvyra-internal-reader").attr("content", "1")
    head.prependElement("script").appendChild(DataNode(dynamicReaderPrelude))
    head.prependElement("base").attr("href", validateWebUrl(url).toString())
    head.prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
        "default-src 'none'; script-src http: https: data: 'unsafe-inline' 'unsafe-eval'; " +
            "style-src http: https: 'unsafe-inline'; connect-src http: https:; img-src data:; " +
            "frame-src 'none'; child-src 'none'; worker-src 'none'; form-action 'none'; base-uri http: https:")
    return document.outerHtml()
}

private val dynamicReaderPrelude = """
    (() => {
      const disable = (target, key, value) => { try { Object.defineProperty(target, key, {value, configurable:false, writable:false}); } catch (_) {} };
      ['WebSocket','Worker','SharedWorker','RTCPeerConnection','webkitRTCPeerConnection'].forEach(k => disable(window,k,undefined));
      disable(window,'open',() => null);
      disable(navigator,'sendBeacon',() => false);
      disable(navigator,'serviceWorker',undefined);
      try { Object.defineProperty(document,'cookie',{get:()=>'',set:()=>{},configurable:false}); } catch (_) {}
    })();
""".trimIndent()

/** Clone rendered DOM after removing nodes hidden by stylesheets, before the pure text extractor. */
private data class RenderedDomSnapshot(val html: String, val truncated: Boolean, val error: String? = null)

private suspend fun WebView.readVisibleHtml(): RenderedDomSnapshot = suspendCancellableCoroutine { continuation ->
    evaluateJavascript("""
        (() => {
          if (!document.body) return JSON.stringify({html:'',truncated:false});
          const marked = !!document.querySelector('meta[name="onvyra-internal-reader"]');
          if (location.protocol === 'chrome-error:' || location.href.startsWith('chrome-error://') ||
              (!marked && document.querySelector('#main-frame-error,body.neterror,.interstitial-wrapper,.error-code'))) {
            return JSON.stringify({error:'Chromium returned an internal error document; the website content was not read.'});
          }
          // The initial about:blank page and an unmarked replacement are not website content.
          if (!marked) return JSON.stringify({html:'',truncated:false});
          const clone = document.documentElement.cloneNode(true);
          const source = document.documentElement.querySelectorAll('*');
          const target = clone.querySelectorAll('*');
          for (let i=source.length-1;i>=0;i--) {
            const style = getComputedStyle(source[i]);
            if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') target[i].remove();
          }
          const html = clone.outerHTML;
          return JSON.stringify({html:html.slice(0,2097152),truncated:html.length>2097152});
        })();
    """.trimIndent()) { encoded ->
        if (continuation.isActive) {
            val value = runCatching {
                val json = Json.parseToJsonElement(encoded).jsonPrimitive.contentOrNull.orEmpty()
                val snapshot = Json.parseToJsonElement(json).jsonObject
                RenderedDomSnapshot(snapshot["html"]?.jsonPrimitive?.contentOrNull.orEmpty(), snapshot["truncated"]?.jsonPrimitive?.booleanOrNull == true,
                    snapshot["error"]?.jsonPrimitive?.contentOrNull)
            }.getOrDefault(RenderedDomSnapshot("", false))
            if (value.error != null) continuation.resumeWithException(java.io.IOException(value.error))
            else continuation.resume(value)
        }
    }
}
