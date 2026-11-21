package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional artwork metadata. It never changes a book's text provider or book ID. */
data class ResolvedCover(val url: String, val referer: String, val sourcePage: String,
    val requestHeaders: Map<String, String> = emptyMap())

/**
 * Reads one normal public search page from the publisher, with exact title AND author
 * matching. This is optional enrichment: callers should show search results first.
 */
class NovelCoverResolver(client: OkHttpClient, private val cacheDir: File? = null,
    private val referenceProviders: Boolean = false) {
    @Volatile private var sourceLookup: (suspend (NovelBook, Set<String>) -> ResolvedCover?)? = null
    internal fun useSourceLookup(lookup: suspend (NovelBook, Set<String>) -> ResolvedCover?) { sourceLookup = lookup }
    private val client = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val permits = Semaphore(3)
    private val gates = List(32) { Mutex() }
    private val failed = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 256
    }
    private data class Cached(val at: Long, val cover: ResolvedCover?)
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean = size > 256
    }

    /** A user retry may revisit this book's failed artwork without resetting other books. */
    internal fun retry(book: NovelBook, attemptedUrls: Set<String>) {
        synchronized(failed) { attemptedUrls.forEach { failed.remove(it) } }
        val key = "${normalized(book.title)}\u0000${normalizedAuthor(book.author)}"
        synchronized(cache) {
            // A previous lookup with no match must not consume an explicit retry.
            // Keep positive metadata: the CDN may have recovered since the failure.
            if (cache[key]?.cover == null) cache.remove(key)
        }
    }

    suspend fun resolve(book: NovelBook, failedUrl: String? = null): ResolvedCover? = withContext(Dispatchers.IO) {
        val title = normalized(book.title)
        val author = normalizedAuthor(book.author)
        // An absent author cannot prove identity for same-titled works.
        if (book.isComic || title.isBlank() || author.isBlank() || title.length > 160 || author.length > 100) {
            return@withContext null
        }
        if (!failedUrl.isNullOrBlank()) synchronized(failed) { failed[failedUrl] = System.currentTimeMillis() }
        val key = "$title\u0000$author"
        // A visible row and ViewModel warmup can ask simultaneously. Only one
        // publisher request may run per identity; canceling a row cancels its wait.
        gates[(key.hashCode() and Int.MAX_VALUE) % gates.size].withLock {
            // Prefer metadata returned by an actual exact-identity source hit,
            // including publishers absent from the optional Qidian fallback.
            book.sources.asSequence().filter { it.sourceId != NovelSourceEngine.SOURCE_SKIN }.map { it.asBook() }.filter { ReaderBookIdentity.matches(book, it) }
                .firstOrNull { !needsCover(it) && !rejected(it.coverUrl) }?.let {
                    return@withLock ResolvedCover(it.coverUrl, it.coverReferer.ifBlank { it.detailUrl },
                        it.detailUrl, it.artworkHeaders)
                }
            cached(key)?.let { return@withLock it.cover }
            permits.withPermit {
                currentCoroutineContext().ensureActive()
                if (referenceProviders) referenceCoverMetadata(book, ::rejected, ::fetch)?.let {
                    synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), it) }
                    save(key, it)
                    return@withPermit it
                }
                try {
                    val excluded = synchronized(failed) { failed.keys.toList() }.filter(::rejected).toSet()
                    sourceLookup?.invoke(book, excluded)?.takeIf { !rejected(it.url) }?.let {
                        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), it) }
                        return@withPermit it
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { /* Publisher lookup remains optional. */ }
                val url = "https://m.qidian.com/search/".toHttpUrl().newBuilder()
                    .addQueryParameter("kw", book.title.trim()).build()
                try {
                    val page = fetch(Request.Builder().url(url).header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html").build())
                    currentCoroutineContext().ensureActive()
                    val result = parse(page.first, page.second, title, author)
                    synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), result) }
                    if (result != null) save(key, result)
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // Optional enrichment must never fail search or fabricate artwork.
                    null
                }
            }
        }
    }

    private fun rejected(url: String): Boolean = knownPlaceholderUrl(url) || synchronized(failed) {
        failed[url]?.let { System.currentTimeMillis() - it in 0 until 5L * 60 * 1000 } == true
    }

    private fun cached(key: String): Cached? {
        val found = synchronized(cache) { cache[key] } ?: load(key)?.also { synchronized(cache) { cache[key] = it } }
        if (found == null || System.currentTimeMillis() - found.at !in 0 until
            (if (found.cover == null) 2L * 60 * 1000 else 7L * 24 * 60 * 60 * 1000)) return null
        if (found.cover?.let { rejected(it.url) } == true) return null
        return found
    }

    private fun diskFile(key: String): File? = cacheDir?.let { directory ->
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(directory, "$hash.json")
    }

    private fun load(key: String): Cached? { return try {
        diskFile(key)?.takeIf { it.isFile && it.length() in 1L..16_384L }?.let { file ->
            val data = Json.parseToJsonElement(file.readText()).jsonObject
            if (data["identity"]?.jsonPrimitive?.content != key) return null
            val cover = ResolvedCover(data["url"]!!.jsonPrimitive.content,
                data["referer"]!!.jsonPrimitive.content, data["sourcePage"]!!.jsonPrimitive.content)
            val pageHost = cover.sourcePage.toHttpUrlOrNull()?.host
            if (pageHost == "m.qidian.com") {
                if (!publisherCover(cover.url)) return null
            } else if (!referenceProviders || pageHost !in REFERENCE_HOSTS || cover.url.toHttpUrlOrNull() == null) return null
            Cached(data["at"]!!.jsonPrimitive.long, cover)
        }
    } catch (_: Exception) { null } }

    private fun save(key: String, cover: ResolvedCover) {
        val target = diskFile(key) ?: return
        try {
            target.parentFile?.mkdirs()
            val temporary = File.createTempFile("cover-", ".tmp", target.parentFile)
            try {
                temporary.writeText(buildJsonObject {
                    put("identity", key); put("at", System.currentTimeMillis()); put("url", cover.url)
                    put("referer", cover.referer); put("sourcePage", cover.sourcePage)
                }.toString())
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // Bound disk metadata as well as image-loader and memory caches.
                target.parentFile?.listFiles { file -> file.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }?.drop(512)?.forEach { it.delete() }
            } finally { temporary.delete() }
        } catch (_: IOException) { /* A read-only cache does not prevent an actual cover being shown. */ }
    }

    private fun publisherCover(url: String): Boolean = url.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.host in COVER_HOSTS
    } == true

    private fun parse(html: String, sourcePage: String, title: String, author: String): ResolvedCover? {
        val doc = Jsoup.parse(html, sourcePage)
        for (card in doc.select("a[data-bid], a[href*=\"/book/\"], a[href*=\"/chapter/\"]")) {
            val actualTitle = card.selectFirst("h2, h3")?.text().orEmpty()
            val actualAuthor = card.selectFirst("[class*=searchBookAuthor], [class*=bookAuthor], .author")?.text().orEmpty()
            if (normalized(actualTitle) != title || normalizedAuthor(actualAuthor) != author) continue
            val img = card.selectFirst("img") ?: continue
            val raw = sequenceOf("data-src", "data-original", "src").map { img.attr(it) }
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val cover = sourcePage.toHttpUrlOrNull()?.resolve(raw) ?: continue
            // Only use an actual publisher CDN URL present on the matching card.
            if (!publisherCover(cover.toString())) continue
            if (!rejected(cover.toString())) return ResolvedCover(cover.toString(), sourcePage, sourcePage)
            // Same publisher identity, alternative size of the actual card's CDN
            // image. Never guess another book ID or keep retrying a known-bad URL.
            if (cover.host == "bookcover.yuewen.com" && cover.encodedPath.matches(Regex("/qdbimg/\\d+/\\d+/(?:180|300)"))) {
                val alternate = cover.newBuilder().setPathSegment(cover.pathSize - 1,
                    if (cover.pathSegments.last() == "180") "300" else "180").build().toString()
                if (!rejected(alternate)) return ResolvedCover(alternate, sourcePage, sourcePage)
            }
        }
        return null
    }

    private suspend fun fetch(request: Request): Pair<String, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code != 200 || (it.request.url.host != "m.qidian.com" &&
                            (!referenceProviders || it.request.url.host !in REFERENCE_HOSTS)) || it.request.url.scheme != "https") {
                            throw IOException("Publisher cover page is unavailable")
                        }
                        val body = it.body
                        val input = body.source()
                        input.request(MAX_BYTES + 1)
                        if (input.buffer.size > MAX_BYTES) throw IOException("Publisher cover page is too large")
                        input.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8) to it.request.url.toString()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun normalizedAuthor(value: String): String = normalized(value.trim()
        .removePrefix("作者：").removePrefix("作者:")
        .replace(Regex("[，,]\\s*(?:分类|状态|字数)[:：].*$"), "").removeSuffix(" 著"))

    private fun normalized(value: String): String = value.trim().replace(Regex("[\\s　]+"), "")

    companion object {
        /** Actual source placeholders may have a URL while still lacking book artwork. */
        fun needsCover(book: NovelBook): Boolean {
            if (book.isComic) return false
            if (book.coverUrl.isBlank()) return true
            val url = book.coverUrl.toHttpUrlOrNull() ?: return true
            return knownPlaceholderUrl(url.toString())
        }
        private fun knownPlaceholderUrl(value: String): Boolean {
            if (value.toHttpUrlOrNull()?.host?.let { it == "biquge.skin" || it.endsWith(".biquge.skin") || it == "apibi.cc" } == true) return true
            val url = value.toHttpUrlOrNull() ?: return true
            // Some source templates concatenate their site origin with an empty image path.
            // The resulting HTTP-200 home page must not win the artwork merge over a later
            // exact-title/author hit. A root image endpoint carrying a query remains eligible.
            if (url.encodedPath == "/" && url.query.isNullOrBlank()) return true
            // This public URL now returns imgchr's "image not found" bitmap with
            // HTTP 200. It was observed directly in the reference backup metadata.
            if (url.host == "s2.ax1x.com" && url.encodedPath == "/2019/09/23/uifQCd.jpg") return true
            val stem = url.pathSegments.lastOrNull().orEmpty().substringBefore('.')
            return stem in SOURCE_PLACEHOLDER_HASHES || stem.equals("nocover", ignoreCase = true) ||
                stem.equals("noimg", ignoreCase = true)
        }
        private const val MAX_BYTES = 512L * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        private val COVER_HOSTS = setOf("bookcover.yuewen.com", "qidian.qpic.cn", "qidian.gtimg.com")
        private val REFERENCE_HOSTS = setOf("pre-api.tuishujun.com", "m.ypshuo.com")
        private val SOURCE_PLACEHOLDER_HASHES = setOf(
            "d21ca920e63ba5e820f07ad60a3b82855680512a4c70ad683d0f6ee2e85ac972",
            "c0eb794e9a029833ceba1c53e5815dbfe67b7b3d7f59cddbe73a92833f17e3d8",
            "9d72f71ddf698ba7ef9f46798c77c573c41c7725b11ee81b403361e2bc841e75",
            "0d471edb90dcd178a3698cd43a02ace0a13c9b98e6feb9de906217fd37c7d901",
            "18a70d88dd2f8ad0fbb26fcf231b4c4cde3ae203b6b0ed68455edd4682038f5a",
            "8b39e830333ae3f3d791a29c53e3a9f52f9b2b39e048736af846af4eaf22b2e5",
        )
    }
}
