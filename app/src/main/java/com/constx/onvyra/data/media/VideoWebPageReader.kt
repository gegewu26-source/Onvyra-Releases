package com.constx.onvyra.data.media

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.constx.onvyra.data.ai.tools.PublicWebNetwork
import com.constx.onvyra.data.ai.tools.extractWebPageContent
import com.constx.onvyra.data.ai.tools.validateWebUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

internal data class VideoPageDocument(val page: MovieWebPage, val mediaUrls: List<String>)

/** Reads public HTML as data. It never runs site scripts or imports a remote source definition. */
internal class VideoWebPageReader(
    client: OkHttpClient,
    private val maximumEntries: Int = 12,
    private val ttlNanos: Long = TimeUnit.MINUTES.toNanos(2),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class CachedPage(val document: VideoPageDocument, val insertedAt: Long)
    private val network = PublicWebNetwork(client)
    private val cache = LinkedHashMap<String, CachedPage>(12, .75f, true)

    init { require(maximumEntries > 0 && ttlNanos > 0) }

    suspend fun read(url: String): VideoPageDocument = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val canonical = validateWebUrl(url).toString()
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = canonical)
        synchronized(cache) {
            cache[canonical]?.takeIf { nanoTime() - it.insertedAt < ttlNanos }?.document
        }?.let {
            com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = it.page.url)
            return@withContext it
        }

        val response = network.read(canonical)
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = canonical)
        com.constx.onvyra.security.PrivateMediaAccess.requireAllowed(url = response.finalUrl)
        if (response.status !in 200..299) throw IOException("视频网页暂时无法读取")
        if (response.sourceTruncated) throw IOException("视频网页超过可读取大小")
        val mime = response.contentType?.let { "${it.type}/${it.subtype}" }?.lowercase()
        if (mime != null && mime !in HTML_COMPATIBLE_TYPES) throw IOException("链接没有返回 HTML 视频网页")
        val prefix = response.bytes.take(4_096).toByteArray().toString(Charsets.ISO_8859_1)
        if (!looksLikeVideoHtml(prefix)) throw IOException("链接没有返回 HTML 视频网页")
        // Jsoup detects an HTML-declared encoding when the HTTP response has no charset.
        val html = response.bytes.inputStream().use {
            Jsoup.parse(it, response.contentType?.charset()?.name(), response.finalUrl).outerHtml()
        }
        val document = parseVideoPageDocument(html, response.finalUrl)
        currentCoroutineContext().ensureActive()
        synchronized(cache) {
            val now = nanoTime()
            cache.entries.removeAll { now - it.value.insertedAt >= ttlNanos }
            cache[canonical] = CachedPage(document, now)
            while (cache.size > maximumEntries) cache.remove(cache.keys.first())
        }
        document
    }
}

private val HTML_COMPATIBLE_TYPES = setOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream")
private val htmlTag = Regex("<(?:!doctype\\s+html|html\\b|head\\b|body\\b|title\\b|meta\\b|main\\b|article\\b|div\\b|video\\b|iframe\\b|script\\b)", RegexOption.IGNORE_CASE)
private val pageSpaces = Regex("[\\s\\u00a0]+")
private fun looksLikeVideoHtml(value: String): Boolean = value.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith('<') && htmlTag.containsMatchIn(value)

/** Extracts actual document URLs only, retaining no scripts, cookies, or signed playback headers. */
internal fun parseVideoPageDocument(html: String, url: String): VideoPageDocument {
    require(html.length <= 2 * 1024 * 1024) { "Video webpage is too large" }
    require(looksLikeVideoHtml(html.take(4_096))) { "HTML video webpage required" }
    val original = validateWebUrl(url)
    val document = Jsoup.parse(html, original.toString())
    val base = document.selectFirst("base[href]")?.attr("href")?.let { relative ->
        original.resolve(relative)?.toString()?.let { runCatching { validateWebUrl(it) }.getOrNull() }
    } ?: original
    fun actualUrl(value: String): String? = value.trim().takeIf { it.isNotEmpty() }?.let { relative ->
        base.resolve(relative)?.toString()?.let { resolved -> runCatching { validateWebUrl(resolved).toString() }.getOrNull() }
    }
    fun clean(value: String, maximum: Int) = value.replace(pageSpaces, " ").trim().take(maximum)
    val media = LinkedHashSet<String>()
    val playerLinks = LinkedHashMap<String, MovieWebCandidate>()
    fun mediaUrl(value: String) { if (media.size < 8) actualUrl(value)?.let(media::add) }
    fun playerUrl(value: String, title: String) {
        if (playerLinks.size >= 30) return
        actualUrl(value)?.let { playerLinks.putIfAbsent(it, MovieWebCandidate(clean(title, 160).ifBlank { "播放" }, it)) }
    }

    document.select("video[src], video source[src]").forEach { mediaUrl(it.attr("src")) }
    document.select("meta[property], meta[name]").forEach { tag ->
        val key = tag.attr("property").ifBlank { tag.attr("name") }.lowercase()
        if (key in setOf("og:video", "og:video:url", "og:video:secure_url")) mediaUrl(tag.attr("content"))
    }

    var videoName: String? = null
    var videoDescription: String? = null
    var remainingNodes = 256
    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun videoType(element: JsonElement?): Boolean = when (element) {
        is JsonArray -> element.any(::videoType)
        is JsonPrimitive -> element.isString && element.content.substringAfterLast('/').substringAfterLast('#').equals("VideoObject", true)
        else -> false
    }
    fun inspect(element: JsonElement, depth: Int) {
        if (depth > 10 || remainingNodes-- <= 0) return
        when (element) {
            is JsonObject -> {
                if (videoType(element["@type"])) {
                    string(element["contentUrl"])?.let(::mediaUrl)
                    string(element["embedUrl"])?.let { playerUrl(it, "播放：" + (string(element["name"]) ?: "视频")) }
                    if (videoName == null) videoName = string(element["name"])?.let { clean(it, 512) }?.takeIf { it.isNotBlank() }
                    if (videoDescription == null) videoDescription = string(element["description"])?.let { clean(it, 2_000) }
                }
                element.values.forEach { inspect(it, depth + 1) }
            }
            is JsonArray -> element.forEach { inspect(it, depth + 1) }
            else -> Unit
        }
    }
    document.select("script[type]").filter { it.attr("type").substringBefore(';').trim().equals("application/ld+json", true) }
        .take(8).forEach { tag ->
            val value = tag.data().trim()
            if (hasBoundedJsonDepth(value)) runCatching { Json.parseToJsonElement(value) }.getOrNull()?.let { inspect(it, 0) }
        }
    document.select("iframe[src]").take(30).forEach { playerUrl(it.attr("src"), "播放：" + it.attr("title").ifBlank { "视频" }) }

    val extracted = extractWebPageContent(html, original.toString(), 12_000)
    extracted.links.forEach { playerUrl(it.url, it.title) }
    val title = extracted.title.ifBlank { videoName.orEmpty() }
    return VideoPageDocument(
        page = MovieWebPage(
            url = original.toString(), title = title,
            description = extracted.description.ifBlank { videoDescription.orEmpty() },
            text = extracted.text, links = playerLinks.values.take(30),
        ),
        mediaUrls = media.toList(),
    )
}

/** Bound nesting before JSON parsing, so hostile metadata cannot recurse deeply in the parser. */
private fun hasBoundedJsonDepth(value: String): Boolean {
    if (value.isEmpty() || value.length > 262_144) return false
    var depth = 0
    var inString = false
    var escaped = false
    for (character in value) {
        if (inString) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') inString = false
        } else when (character) {
            '"' -> inString = true
            '{', '[' -> if (++depth > 12) return false
            '}', ']' -> if (--depth < 0) return false
        }
    }
    return !inString && depth == 0
}
