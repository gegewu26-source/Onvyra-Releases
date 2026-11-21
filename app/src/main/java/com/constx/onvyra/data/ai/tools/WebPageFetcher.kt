package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock

private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
private const val MAX_PDF_BYTES = 8 * 1024 * 1024
private const val MAX_REDIRECTS = 5
internal const val MAX_WEB_DOCUMENT_CHARS = 512_000
private const val MAX_CONTENT_CHARS = 24_000
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

@Serializable
internal data class WebFetchResult(
    val url: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    val content: String = "",
    val links: List<WebPageLink> = emptyList(),
    val truncated: Boolean = false,
    val retrievedAt: String = Clock.System.now().toString(),
    val error: String? = null,
    val offset: Int = 0,
    val nextOffset: Int? = null,
    val totalChars: Int = content.length,
    val pageId: String? = null,
    val sourceTruncated: Boolean = false,
    val rendered: Boolean = false,
    val pageCount: Int? = null,
    val warning: String? = null,
)

internal data class RenderedWebPage(val content: WebPageContent, val sourceTruncated: Boolean = false)

internal data class WebRenderResource(val bytes: ByteArray, val contentType: String, val charset: String?)

internal fun interface WebResourceLoader {
    suspend fun load(url: String): WebRenderResource
}

internal fun interface DynamicWebPageRenderer {
    suspend fun render(html: String, url: String, resources: WebResourceLoader): RenderedWebPage
}

/** Reads public pages using the app's network preferences, never its browser account state. */
internal class WebPageFetcher(
    client: OkHttpClient,
    private val renderer: DynamicWebPageRenderer? = null,
    private val pdfParser: WebPdfTextParser = NativeWebPdfTextParser,
) {
    private val network = PublicWebNetwork(client)
    // A continuation reads the exact same bounded snapshot, even if the live article changes.
    private val snapshots = LinkedHashMap<String, Pair<Long, WebFetchResult>>()

    suspend fun fetch(
        url: String,
        maxChars: Int = 20_000,
        offset: Int = 0,
        pageId: String? = null,
    ): WebFetchResult = withContext(Dispatchers.IO) {
        // Legacy hosts may permanently redirect or serve a Cloudflare
        // challenge; the live mirror serves the same page, so fetch it directly.
        val originalUrl = com.constx.onvyra.data.media.rewriteToActiveMirror(validateWebUrl(url).toString())
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = originalUrl)
        require(offset >= 0) { "offset must be a non-negative integer." }
        val limit = maxChars.coerceIn(2, MAX_CONTENT_CHARS)
        if (pageId != null) {
            val snapshot = synchronized(snapshots) {
                pruneSnapshots()
                snapshots[pageId]?.second
            } ?: throw IllegalArgumentException("The page snapshot expired. Read the URL again without pageId or offset.")
            require(originalUrl == snapshot.url || originalUrl == snapshot.finalUrl) { "pageId belongs to a different URL." }
            com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = snapshot.finalUrl)
            return@withContext snapshot.segment(offset, limit)
        }
        require(offset == 0) { "To continue without gaps, pass pageId from the first result together with nextOffset." }
        val result = withTimeoutOrNull(45_000) { fetchDocument(originalUrl) }
            ?: return@withContext WebFetchResult(originalUrl, originalUrl, 0, null, error = "The webpage reader exceeded its 45 second time limit.")
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = originalUrl)
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = result.finalUrl)
        if (result.error != null) return@withContext result
        currentCoroutineContext().ensureActive()
        val id = UUID.randomUUID().toString()
        val snapshot = result.copy(pageId = id, totalChars = result.content.length)
        synchronized(snapshots) {
            pruneSnapshots()
            while (snapshots.size >= 4 || snapshots.values.sumOf { it.second.content.length } + snapshot.content.length > 1_024_000) {
                if (snapshots.isEmpty()) break
                snapshots.remove(snapshots.keys.first())
            }
            snapshots[id] = System.nanoTime() to snapshot
        }
        snapshot.segment(0, limit)
    }

    private fun pruneSnapshots() {
        val cutoff = System.nanoTime() - TimeUnit.MINUTES.toNanos(5)
        snapshots.entries.removeAll { it.value.first < cutoff }
    }

    private suspend fun fetchDocument(originalUrl: String): WebFetchResult {
        val page = network.read(originalUrl)
        var result = WebFetchResult(
            url = originalUrl,
            finalUrl = page.finalUrl,
            status = page.status,
            contentType = page.contentType?.toString(),
            sourceTruncated = page.sourceTruncated,
            truncated = page.sourceTruncated,
        )
        if (page.status !in 200..299) {
            return result.copy(error = "HTTP ${page.status}: the website did not return readable page content.")
        }
        val bytes = page.bytes
        val isPdf = page.contentType?.subtype == "pdf" || bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
        if (isPdf) {
            if (page.sourceTruncated) return result.copy(error = "The PDF exceeds the 8 MiB download limit; a partial PDF cannot be parsed reliably.")
            return try {
                val pdf = pdfParser.parse(bytes)
                result.copy(
                    content = pdf.text,
                    pageCount = pdf.pageCount,
                    sourceTruncated = pdf.truncated,
                    truncated = pdf.truncated,
                    error = if (pdf.text.isBlank()) "No selectable PDF text was found. Scanned pages require OCR, which this reader does not perform." else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                result.copy(error = "PDF text extraction failed: ${error.message?.take(500) ?: "invalid or unsupported PDF"}")
            }
        }
        if (!isTextContent(page.contentType)) {
            return result.copy(error = "This URL returns a binary file rather than a readable webpage or PDF.")
        }
        val isHtml = page.contentType?.subtype?.let { it == "html" || it == "xhtml+xml" }
            ?: bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart().let {
                it.startsWith("<!doctype html", ignoreCase = true) || it.startsWith("<html", ignoreCase = true)
            }
        if (!isHtml && bytes.any { it == 0.toByte() } && page.contentType?.charset()?.name()?.startsWith("UTF-16") != true) {
            return result.copy(error = "The response does not contain readable text.")
        }
        if (!isHtml) {
            val content = bytes.toString(page.contentType?.charset() ?: Charsets.UTF_8).trim()
            val text = content.safeTake(MAX_WEB_DOCUMENT_CHARS)
            return result.copy(
                content = text,
                sourceTruncated = result.sourceTruncated || content.length > text.length,
                truncated = result.truncated || content.length > text.length,
                error = if (text.isEmpty()) "The website returned an empty response." else null,
            )
        }
        val document = Jsoup.parse(ByteArrayInputStream(bytes), page.contentType?.charset()?.name(), page.finalUrl)
        val html = document.outerHtml()
        var content = extractWebPageContent(html, page.finalUrl, MAX_WEB_DOCUMENT_CHARS)
        if (looksLikeWebChallenge(content)) {
            return result.copy(error = "The website returned a sign-in or verification challenge. This reader does not bypass access controls.")
        }
        if (renderer != null && needsDynamicWebPage(html, content.text)) {
            try {
                val dynamic = renderer.render(html, page.finalUrl, WebResourceLoader { resource ->
                    val response = network.read(resource, renderResource = true)
                    check(response.status in 200..299) { "JavaScript resource returned HTTP ${response.status}." }
                    check(!response.sourceTruncated) { "JavaScript resource exceeds the size limit." }
                    WebRenderResource(
                        response.bytes,
                        response.contentType?.let { "${it.type}/${it.subtype}" } ?: "text/plain",
                        response.contentType?.charset()?.name(),
                    )
                })
                if (dynamic.content.text.isNotBlank() && dynamic.content.text.length >= content.text.length) {
                    content = dynamic.content
                    result = result.copy(rendered = true, sourceTruncated = result.sourceTruncated || dynamic.sourceTruncated)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                result = result.copy(warning = "JavaScript rendering was unavailable: ${error.message?.take(400)}")
            }
        }
        return result.copy(
            title = content.title.ifEmpty { null },
            description = content.description.ifEmpty { null },
            language = content.language,
            content = content.text,
            links = content.links,
            sourceTruncated = result.sourceTruncated || content.truncated,
            truncated = result.sourceTruncated || content.truncated,
            error = when {
                looksLikeWebChallenge(content) -> "The website requires sign-in or verification; that content was not read."
                content.text.isEmpty() -> "No readable page text was returned. The site may require unsupported JavaScript, sign-in or verification."
                needsDynamicWebPage(html, content.text) && !result.rendered -> "Only a JavaScript loading shell was returned; the page content could not be read."
                else -> null
            },
        ).let { if (it.error != null) it.copy(content = "") else it }
    }
}

private fun WebFetchResult.segment(start: Int, limit: Int): WebFetchResult {
    require(start <= content.length) { "offset exceeds the available page text." }
    require(start == 0 || start == content.length || !content[start].isLowSurrogate()) { "offset splits a Unicode character; use nextOffset exactly as returned." }
    val text = content.substring(start).safeTake(limit)
    val end = start + text.length
    return copy(content = text, offset = start, nextOffset = end.takeIf { it < content.length }, totalChars = content.length,
        truncated = sourceTruncated || end < content.length)
}

internal fun String.safeTake(limit: Int): String = take(limit).let {
    if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
}

internal fun needsDynamicWebPage(html: String, text: String): Boolean {
    if (text.isBlank()) return true
    val document = Jsoup.parse(html)
    val hasScript = document.select("script[src], script[type=module]").isNotEmpty()
    val emptyAppRoot = document.select("#app, #root, #__next, #__nuxt, [data-reactroot]").any { !it.hasText() }
    val loading = Regex("(?i)^(?:loading[.\\s…]*|please (?:enable|turn on) javascript.*|you need to enable javascript.*|正在加载[.\\s…]*|请(?:开启|启用)\\s*javascript.*)$")
    return hasScript && (loading.matches(text.trim()) || emptyAppRoot && text.length < 240)
}

private fun looksLikeWebChallenge(content: WebPageContent): Boolean =
    Regex("(?i)^(?:just a moment|checking your browser|verify (?:that )?you are human|attention required|access denied|sign in to continue|请完成验证|安全验证)")
        .containsMatchIn(content.title.trim()) ||
        Regex("(?i)^(?:please complete the security check|verify (?:that )?you are human|请完成(?:安全)?验证)").containsMatchIn(content.text.trim())

internal data class RawWebPage(
    val status: Int,
    val contentType: MediaType?,
    val location: String?,
    val bytes: ByteArray = byteArrayOf(),
    val sourceTruncated: Boolean = false,
    val finalUrl: String = "",
)

/** The sole network path for initial requests, redirects, scripts and dynamic GET data. */
internal class PublicWebNetwork(client: OkHttpClient) {
    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun read(url: String, renderResource: Boolean = false): RawWebPage {
        var target = validateWebUrl(url)
        val visited = mutableSetOf<HttpUrl>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = target.toString())
            check(visited.add(target)) { "The page redirects in a loop." }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("The webpage request timed out.")
            val targetHost = target.host
            val inheritedDns = client.dns
            val requestClient = client.newBuilder()
                .callTimeout(remaining, TimeUnit.NANOSECONDS)
                .dns(Dns { hostname ->
                    inheritedDns.lookup(hostname).also { addresses ->
                        if (hostname.equals(targetHost, ignoreCase = true) && (addresses.isEmpty() || addresses.any { !isPublicWebAddress(it) })) {
                            throw UnknownHostException("Only public website addresses are supported.")
                        }
                    }
                })
                .build()
            val request = Request.Builder().url(target)
                .header("Accept", if (renderResource) "*/*" else "text/html,application/xhtml+xml,application/pdf,text/plain,application/json;q=0.9,text/*;q=0.8")
                .get().build()
            val page = requestClient.newCall(request).readPage(renderResource)
            currentCoroutineContext().ensureActive()
            com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = target.toString())
            if (page.status in REDIRECT_CODES) {
                check(redirects < MAX_REDIRECTS) { "The page exceeded $MAX_REDIRECTS redirects." }
                target = validateWebUrl(target.resolve(page.location ?: throw IOException("The redirect has no destination."))?.toString()
                    ?: error("Invalid redirect destination."))
                redirects++
            } else return page.copy(finalUrl = target.toString())
        }
    }
}

/** Cancellation also closes the response body, rather than merely abandoning its callback. */
private suspend fun Call.readPage(renderResource: Boolean): RawWebPage = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val page = response.use {
                    val type = it.body.contentType()
                    val raw = RawWebPage(it.code, type, it.header("Location"))
                    val pdfHint = type?.subtype == "pdf" || it.request.url.encodedPath.endsWith(".pdf", ignoreCase = true)
                    if (!it.isSuccessful || !renderResource && !isTextContent(type) && !pdfHint && type?.subtype != "octet-stream") return@use raw
                    var maxBytes = if (pdfHint && !renderResource) MAX_PDF_BYTES else MAX_SOURCE_BYTES
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    var headerChecked = renderResource
                    it.body.byteStream().use { input ->
                        while (output.size() <= maxBytes) {
                            if (call.isCanceled()) throw IOException("The webpage request was canceled.")
                            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            if (!headerChecked && output.size() >= 5) {
                                if (output.toByteArray().copyOf(5).contentEquals("%PDF-".toByteArray())) maxBytes = MAX_PDF_BYTES
                                headerChecked = true
                            }
                        }
                    }
                    val bytes = output.toByteArray()
                    raw.copy(bytes = if (bytes.size > maxBytes) bytes.copyOf(maxBytes) else bytes, sourceTruncated = bytes.size > maxBytes)
                }
                if (continuation.isActive) continuation.resume(page)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

private fun isTextContent(type: MediaType?): Boolean = type == null || type.type == "text" ||
    type.subtype in setOf("json", "xml", "xhtml+xml") || type.subtype.endsWith("+json") || type.subtype.endsWith("+xml")

internal fun validateWebUrl(value: String): HttpUrl {
    require(value.length <= 4_096) { "The website URL is too long." }
    val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("A complete http:// or https:// website URL is required.")
    require(url.toString().length <= 4_096) { "The website URL is too long." }
    require(url.username.isEmpty() && url.password.isEmpty()) { "URLs containing account credentials are not supported." }
    val host = url.host.lowercase().trimEnd('.')
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Only public website addresses are supported." }
    if (':' in host || host.all { it.isDigit() || it == '.' }) {
        require(isPublicWebAddress(InetAddress.getByName(host))) { "Only public website addresses are supported." }
    }
    return url.newBuilder().fragment(null).build()
}

private fun isPublicWebAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
    if (bytes.size == 4) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first == 0 || first == 100 && second in 64..127 || first >= 240) return false
    }
    return true
}
