package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public, anonymous comic pages only. Missing/blocked content is an error, never a sample comic. */
class ComicSourceEngine(private val client: OkHttpClient) {
    companion object {
        val SOURCES: List<ComicSource> = listOf(
            ComicSource(
                "dmmh", "咚漫漫画", true, "https://www.dongmanmanhua.cn",
                "官方公开漫画：真实搜索、分页目录与免费章节；登录或付费章节不支持",
            ),
            ComicSource(
                "kbmh", "拷贝漫画（暂不可用）", false, "https://api.copymanga.tv",
                "原接口当前无法建立可信连接，未启用；不生成搜索或章节占位数据",
            ),
            ComicSource(
                "dmzj", "动漫之家（未适配）", false, "https://v3api.dmzj.com",
                "原接口当前无法建立可信连接，未启用",
            ),
            ComicSource(
                "webtoon", "WEBTOON（未验证）", false, "https://www.webtoons.com",
                "当前网络无法验证公开阅读链路，未启用",
            ),
        )

        private const val MAX_HTML_CHARS = 8 * 1024 * 1024
        private const val MAX_CATALOG_PAGES = 1000
    }

    private val httpClient by lazy {
        client.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
    private data class CachedCatalog(val time: Long, val chapters: List<NovelChapter>)
    private val catalogCache = ConcurrentHashMap<String, CachedCatalog>()
    private fun catalogKey(url: HttpUrl, sourceId: String) = "$sourceId:${ComicHtmlParser.titleId(url)}"

    fun invalidateCatalog(comicUrl: String, sourceId: String) {
        comicUrl.toHttpUrlOrNull()?.let { catalogCache.remove(catalogKey(it, sourceId)) }
    }

    suspend fun searchComics(keyword: String, sourceId: String = "kbmh"): List<NovelBook> =
        withContext(Dispatchers.IO) {
            val source = supportedSource(sourceId)
            if (keyword.isBlank()) return@withContext emptyList()
            val url = source.baseUrl.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("keyword", keyword.trim())
                .build()
            val page = fetch(url, source)
            ComicHtmlParser.search(page.html, page.url.toString(), source)
        }

    suspend fun getDiscoverComics(sourceId: String): List<NovelBook> = withContext(Dispatchers.IO) {
        val source = supportedSource(sourceId)
        val page = fetch(source.baseUrl.toHttpUrlOrNull()!!, source)
        ComicHtmlParser.discover(page.html, page.url.toString(), source)
    }

    suspend fun getComicCatalog(comicUrl: String, sourceId: String,
        onProgress: ((List<NovelChapter>) -> Unit)? = null): List<NovelChapter> =
        withContext(Dispatchers.IO) {
            val source = supportedSource(sourceId)
            var next = ComicHtmlParser.validateUrl(comicUrl, source)
            val titleId = ComicHtmlParser.titleId(next)
                ?: throw ComicSourceException("漫画地址缺少真实作品编号，请重新搜索并打开作品。")
            val cacheKey = catalogKey(next, sourceId)
            catalogCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.time in 0..300_000 }?.let {
                onProgress?.invoke(it.chapters)
                return@withContext it.chapters
            }
            next = next.newBuilder().setQueryParameter("page", "1").build()
            val visited = mutableSetOf<String>()
            val chapters = linkedMapOf<Int, NovelChapter>()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!visited.add(next.toString())) {
                    throw ComicSourceException("漫画目录分页出现循环，目录未完整加载，请稍后重试。")
                }
                if (visited.size > MAX_CATALOG_PAGES) {
                    throw ComicSourceException("漫画目录页数超过保护上限，未返回截断目录。")
                }
                val page = fetch(next, source)
                if (ComicHtmlParser.titleId(page.url) != titleId) {
                    throw ComicSourceException("漫画源返回了其他作品，目录加载已停止。")
                }
                val parsed = ComicHtmlParser.catalog(page.html, page.url.toString(), source)
                if (parsed.chapters.isEmpty()) {
                    throw ComicSourceException("漫画源没有返回公开章节；作品可能需登录、已下架或网站结构已改变。")
                }
                val oldCount = chapters.size
                parsed.chapters.forEach { chapter ->
                    val id = chapter.url.toHttpUrlOrNull()?.queryParameter("episode_no")?.toIntOrNull()
                        ?: throw ComicSourceException("漫画源返回了无效章节编号。")
                    chapters.putIfAbsent(id, chapter)
                }
                if (chapters.size == oldCount) {
                    throw ComicSourceException("漫画源重复返回同一目录页，目录未完整加载。")
                }
                next = parsed.nextUrl?.let { ComicHtmlParser.validateUrl(it, source) } ?: break
            }
            chapters.toSortedMap().values.mapIndexed { index, chapter -> chapter.copy(index = index) }.also {
                // Source pages list newest episodes first. Publishing an early page as
                // chapter zero would jump the reader to the latest episode.
                onProgress?.invoke(it)
                if (catalogCache.size >= 12) catalogCache.entries.minByOrNull { entry -> entry.value.time }
                    ?.let { entry -> catalogCache.remove(entry.key) }
                catalogCache[cacheKey] = CachedCatalog(System.currentTimeMillis(), it)
            }
        }

    suspend fun getComicChapterPages(chapterUrl: String, sourceId: String): List<String> =
        withContext(Dispatchers.IO) {
            val source = supportedSource(sourceId)
            val url = ComicHtmlParser.validateUrl(chapterUrl, source)
            if (ComicHtmlParser.titleId(url) == null || url.queryParameter("episode_no")?.toIntOrNull() == null) {
                throw ComicSourceException("章节地址无效，请从真实目录重新选择章节。")
            }
            val page = fetch(url, source)
            if (ComicHtmlParser.titleId(page.url) != ComicHtmlParser.titleId(url) ||
                page.url.queryParameter("episode_no") != url.queryParameter("episode_no")
            ) {
                throw ComicSourceException("漫画源将章节重定向到其他页面；可能需要登录或该章节不可公开阅读。")
            }
            ComicHtmlParser.chapterPages(page.html, page.url.toString())
        }

    private fun supportedSource(sourceId: String): ComicSource {
        val source = SOURCES.firstOrNull { it.id == sourceId }
            ?: throw ComicSourceException("该漫画源尚未适配，请切换到咚漫漫画。")
        if (!source.enable) throw ComicSourceException("${source.name}：${source.description}。请切换到咚漫漫画。")
        return source
    }

    private data class HtmlPage(val html: String, val url: HttpUrl)

    private suspend fun fetch(url: HttpUrl, source: ComicSource): HtmlPage {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", source.baseUrl + "/")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val error = if (e is SSLException) ComicSourceException(
                        "${source.name} 无法建立可信 HTTPS 连接，请检查网络后重试。", e)
                        else ComicSourceException("${source.name} 连接失败或超时，请检查网络后重试。", e)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val page = response.use {
                            if (!continuation.isActive) throw IOException("请求已取消")
                            if (!response.isSuccessful) {
                                val reason = when (response.code) {
                                    401, 403 -> "需要登录、验证或不允许匿名访问"
                                    404, 410 -> "作品或章节不存在或已下架"
                                    429 -> "请求过于频繁，请稍后重试"
                                    else -> "漫画源暂时不可用"
                                }
                                throw ComicSourceException("${source.name}：$reason（HTTP ${response.code}）。")
                            }
                            ComicHtmlParser.validateUrl(response.request.url.toString(), source)
                            val body = response.body
                            val text = body.charStream().use { reader ->
                                val output = StringBuilder()
                                val buffer = CharArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("请求已取消")
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    if (output.length + count > MAX_HTML_CHARS) {
                                        throw ComicSourceException("漫画页面过大，加载已停止；未返回不完整内容。")
                                    }
                                    output.append(buffer, 0, count)
                                }
                                output.toString()
                            }
                            HtmlPage(text, response.request.url)
                        }
                        if (continuation.isActive) continuation.resume(page)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is ComicSourceException) e else ComicSourceException(
                                "${source.name} 连接失败或超时，请检查网络后重试。", e))
                    }
                }
            })
        }
    }
}

class ComicSourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** No Android dependencies: fixtures and real public HTML exercise the same parser. */
internal object ComicHtmlParser {
    data class CatalogPage(val chapters: List<NovelChapter>, val nextUrl: String?)

    fun validateUrl(raw: String, source: ComicSource): HttpUrl {
        val url = raw.toHttpUrlOrNull()
            ?: throw ComicSourceException("漫画源返回了无效网页地址。")
        val base = source.baseUrl.toHttpUrlOrNull()!!
        if (url.host != base.host || url.scheme != "https" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) throw ComicSourceException("漫画地址与所选来源不一致，请重新搜索。")
        return url
    }

    fun titleId(url: HttpUrl): String? =
        (url.queryParameter("title_no") ?: url.queryParameter("titleNo"))?.takeIf { it.toLongOrNull() != null }

    fun search(html: String, pageUrl: String, source: ComicSource): List<NovelBook> {
        val document = document(html, pageUrl)
        // Recommendations outside the search container must never become fabricated matches.
        val cards = document.select(".card_wrap.search .card_lst a[href]")
        if (cards.isEmpty() && document.selectFirst(".ico_nosearch, .search_result, .search_none") == null) {
            throw ComicSourceException("漫画搜索页面结构已改变或需要验证，未生成替代搜索结果。")
        }
        return parseCards(cards, pageUrl, source)
    }

    fun discover(html: String, pageUrl: String, source: ComicSource): List<NovelBook> {
        val document = document(html, pageUrl)
        val books = parseCards(document.select(".card_lst a[href], .ranking_lst a[href]"), pageUrl, source)
        if (books.isEmpty()) throw ComicSourceException("漫画首页未返回真实作品列表，请稍后重试。")
        return books
    }

    private fun parseCards(cards: Iterable<Element>, pageUrl: String, source: ComicSource): List<NovelBook> =
        cards.mapNotNull { card ->
            val url = absoluteUrl(pageUrl, card.attr("href"))?.let { validateUrl(it, source) } ?: return@mapNotNull null
            val id = titleId(url) ?: return@mapNotNull null
            val title = card.selectFirst(".subj")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val image = card.selectFirst("img")
            val cover = image?.let { absoluteUrl(pageUrl, it.attr("data-url").ifBlank { it.attr("src") }) }.orEmpty()
            NovelBook(
                id = "${source.id}_$id", title = title,
                author = card.selectFirst(".author")?.text()?.trim().orEmpty(),
                coverUrl = cover, detailUrl = url.toString(), sourceId = source.id,
                category = card.selectFirst(".genre")?.text()?.trim().orEmpty(),
                status = "", isComic = true, coverReferer = pageUrl,
            )
        }.distinctBy { it.id }

    fun catalog(html: String, pageUrl: String, source: ComicSource): CatalogPage {
        val document = document(html, pageUrl)
        val page = validateUrl(pageUrl, source)
        val comicId = titleId(page)
        val chapters = document.select("#_listUl li a[href]").mapNotNull { link ->
            val url = absoluteUrl(pageUrl, link.attr("href"))?.let { validateUrl(it, source) } ?: return@mapNotNull null
            if (titleId(url) != comicId) return@mapNotNull null
            val episode = url.queryParameter("episode_no")?.toIntOrNull() ?: return@mapNotNull null
            val title = link.selectFirst(".subj")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            NovelChapter(index = episode, title = title, url = url.toString())
        }.distinctBy { it.url }
        val currentPage = page.queryParameter("page")?.toIntOrNull() ?: 1
        val next = document.select(".paginate a[href]").mapNotNull { link ->
            val href = link.attr("href")
            if (href.isBlank() || href.startsWith("#")) return@mapNotNull null
            val url = absoluteUrl(pageUrl, href)?.let { validateUrl(it, source) } ?: return@mapNotNull null
            val number = url.queryParameter("page")?.toIntOrNull() ?: return@mapNotNull null
            if (titleId(url) != comicId || number <= currentPage) null else number to url.toString()
        }.minByOrNull { it.first }?.second
        return CatalogPage(chapters, next)
    }

    fun chapterPages(html: String, pageUrl: String): List<String> {
        val document = document(html, pageUrl)
        // Sidebar/episode thumbnails and transparent placeholders are not comic pages.
        val images = document.select("#_imageList img._images").mapNotNull { image ->
            absoluteUrl(pageUrl, image.attr("data-url"))
        }.distinct()
        if (images.isEmpty()) {
            throw ComicSourceException("该章节没有可匿名读取的正文图片；可能需登录、付费或网站结构已改变，请在源站确认。")
        }
        return images
    }

    private fun document(html: String, url: String): Document {
        val document = Jsoup.parse(html, url)
        if (document.selectFirst("#cf-chl-widget, .g-recaptcha, #challenge-form") != null ||
            document.title().contains("Just a moment", ignoreCase = true)
        ) throw ComicSourceException("漫画源要求网页验证，请在浏览器完成后重试；应用不会绕过验证。")
        return document
    }

    private fun absoluteUrl(pageUrl: String, raw: String): String? {
        if (raw.isBlank() || raw.startsWith("#")) return null
        val resolved = pageUrl.toHttpUrlOrNull()?.resolve(raw) ?: return null
        if (resolved.scheme != "https" && resolved.scheme != "http") return null
        // Some official canonical links use http despite HTTPS being supported.
        return resolved.newBuilder().scheme("https").build().toString()
    }
}
