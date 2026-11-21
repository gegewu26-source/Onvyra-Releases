package com.constx.onvyra.ui.pages.novel

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

internal data class NovelHtmlSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val discoverPath: String,
    val searchPath: String,
    val searchParameter: String,
    val postSearch: Boolean = false,
) {
    fun owns(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        val domain = baseUrl.toHttpUrlOrNull()!!.host.removePrefix("www.")
        return host == domain || host == "www.$domain" || host == "m.$domain"
    }
}

internal data class NovelCatalogPage(
    val chapters: List<NovelChapter>,
    val nextPages: List<String>,
    val declaredCount: Int = 0,
)

/** DOM extraction only. No guessed covers, authors, chapter names or counts. */
internal object NovelHtmlParser {
    fun document(html: String, url: String): Document = Jsoup.parse(html, url)

    fun absolute(base: String, relative: String): String =
        base.toHttpUrlOrNull()?.resolve(relative.trim())?.let { withoutFragment(it) }.orEmpty()

    private fun linkTarget(element: Element): String {
        val href = element.attr("href").trim()
        if (href.isNotEmpty() && !href.startsWith("javascript:", ignoreCase = true)) return href
        // Some directory pages use a literal navigation assignment instead of href.
        // Read only that URL; never evaluate scripts from a source website.
        return Regex("(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(element.attr("onclick"))?.groupValues?.get(1).orEmpty()
    }

    private fun withoutFragment(url: HttpUrl): String = url.newBuilder().fragment(null).build().toString()

    fun bookUrl(url: String, source: NovelHtmlSource): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!source.owns(url)) return null
        val path = parsed.encodedPath
        val canonicalPath = when (source.id) {
            "piquge" -> Regex("^/([0-9]+/[0-9]+)/?(?:[0-9]+/|ml[0-9]+\\.html)?$")
                .matchEntire(path)?.let { "/${it.groupValues[1]}/" }
            "bqgnovels" -> Regex("^/book/([0-9]+)/?$").matchEntire(path)
                ?.let { "/book/${it.groupValues[1]}" }
            "biquge_skin" -> when {
                Regex("^/book/[0-9]+/?$").matches(path) -> path.trimEnd('/') + "/"
                path == "/novel" && parsed.queryParameter("id")?.toLongOrNull() != null ->
                    return parsed.newBuilder().query(null).addQueryParameter("id", parsed.queryParameter("id")).fragment(null).build().toString()
                else -> null
            }
            "zw45" -> Regex("^/book_[0-9]+/?$").matchEntire(path)?.value?.trimEnd('/')?.plus("/")
            "bqgeee" -> Regex("^/(?:books_[0-9]+|book/[0-9]+|[0-9]+/[0-9]+)/?(?:ml[0-9]+\\.html)?$")
                .matchEntire(path)?.value?.replace(Regex("ml[0-9]+\\.html$"), "")?.trimEnd('/')?.plus("/")
            else -> null
        } ?: return null
        return source.baseUrl.toHttpUrlOrNull()!!.newBuilder().encodedPath(canonicalPath).query(null).fragment(null).build().toString()
    }

    fun chapterBelongs(url: String, book: String, source: NovelHtmlSource): Boolean {
        if (!source.owns(url)) return false
        val target = url.toHttpUrlOrNull() ?: return false
        val root = book.toHttpUrlOrNull() ?: return false
        return when (source.id) {
            "piquge" -> target.encodedPath.startsWith(root.encodedPath) &&
                Regex("[0-9]+(?:_[0-9]+)?\\.html").matches(target.encodedPath.removePrefix(root.encodedPath))
            "bqgnovels" -> Regex(Regex.escape(root.encodedPath.trimEnd('/')) + "/[0-9]+/?").matches(target.encodedPath)
            "biquge_skin" -> target.encodedPath == "/reader" &&
                target.queryParameter("novelId") == skinId(book) && target.queryParameter("chapter")?.toIntOrNull() != null
            else -> target.encodedPath.startsWith(root.encodedPath) &&
                Regex("[0-9]+(?:_[0-9]+)?\\.html").matches(target.encodedPath.removePrefix(root.encodedPath))
        }
    }

    fun skinId(url: String): String? = url.toHttpUrlOrNull()?.let {
        it.queryParameter("id") ?: Regex("/book/([0-9]+)/?").matchEntire(it.encodedPath)?.groupValues?.get(1)
    }

    fun stableId(source: String, url: String): String = source + "_" + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    fun image(element: Element?, pageUrl: String): Pair<String, String> {
        if (element == null) return "" to ""
        val raw = sequenceOf("data-src", "data-original", "data-lazy-src", "src")
            .map { element.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.contains("nocover") }.orEmpty()
        if (raw.isBlank()) return "" to ""
        return absolute(pageUrl, raw) to if (element.attr("referrerpolicy") == "no-referrer") "" else pageUrl
    }

    fun books(doc: Document, source: NovelHtmlSource): List<NovelBook> {
        val found = linkedMapOf<String, NovelBook>()
        for (a in doc.select("a[href]")) {
            val url = bookUrl(a.absUrl("href"), source) ?: continue
            val row = a.closest("tr, li, .bookbox, .book-card, .v-card") ?: a
            val title = a.selectFirst(".book-title, .v-card__subtitle, h3, h2")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: a.ownText().takeIf { it.isNotBlank() }
                ?: a.selectFirst("img")?.attr("alt").orEmpty().removeSuffix(" 封面")
            if (title.isBlank() || title in setOf("开始阅读", "返回书页", "小说详情", "目录")) continue
            val (cover, referer) = image(row.selectFirst("img"), doc.location())
            val cells = row.select("td")
            val author = row.selectFirst(".author, .s4, [data-author]")?.text()?.removePrefix("作者：")?.removePrefix("作者:")?.trim()
                ?: if (source.id == "zw45") cells.getOrNull(2)?.text().orEmpty() else ""
            val metaParts = row.selectFirst(".book-meta")?.text()?.split('·')?.map { it.trim() }.orEmpty()
            val count = Regex("([0-9,]+)\\s*章").find(metaParts.lastOrNull().orEmpty())
                ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
            val candidate = NovelBook(
                id = stableId(source.id, url), title = title, author = author.ifBlank { metaParts.firstOrNull().orEmpty() },
                coverUrl = cover, coverReferer = referer, detailUrl = url, sourceId = source.id,
                category = metaParts.getOrNull(1).orEmpty(), status = "", chapterCount = count,
                description = row.selectFirst(".des, .intro, .description")?.text().orEmpty(),
                latestChapter = if (source.id == "zw45") cells.getOrNull(1)?.text().orEmpty() else "",
            )
            val old = found[url]
            if (old == null || (old.coverUrl.isBlank() && cover.isNotBlank())) found[url] = candidate
        }
        return found.values.toList()
    }

    private fun meta(doc: Document, vararg keys: String): String = keys.asSequence().map { key ->
        doc.selectFirst("meta[property='$key'], meta[name='$key']")?.attr("content").orEmpty().trim()
    }.firstOrNull { it.isNotEmpty() }.orEmpty()

    fun detail(doc: Document, source: NovelHtmlSource, fallback: NovelBook): NovelBook {
        val url = bookUrl(doc.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty(), source)
            ?: bookUrl(meta(doc, "og:novel:read_url", "og:url"), source)
            ?: bookUrl(doc.location(), source)
            ?: throw IllegalArgumentException("书籍详情地址不属于${source.name}")
        val title = meta(doc, "og:novel:book_name").ifBlank { doc.selectFirst("h1")?.text().orEmpty() }
        if (title.isBlank() || title.contains("小说详情")) throw IllegalStateException("${source.name}详情需要动态加载，请稍后重试或换源")
        val img = doc.selectFirst(".detail img, .cover img, #fmimg img, .book-cover img")
        val pair = image(img, url)
        val author = meta(doc, "og:novel:author").ifBlank {
            doc.selectFirst(".author, .bookdes a[href*=author], #info p:contains(作者)")?.text()
                .orEmpty().replace(Regex("^作者[：:]\\s*"), "").trim()
        }
        val count = Regex("共\\s*([0-9,]+)\\s*章|([0-9,]+)\\s*章").find(
            doc.selectFirst(".chapterlist .all h4, .info-count, .book-info-line")?.text().orEmpty())
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.replace(",", "")?.toIntOrNull() ?: 0
        return fallback.copy(
            id = stableId(source.id, url), title = title, detailUrl = url, sourceId = source.id,
            author = author.ifBlank { fallback.author },
            coverUrl = pair.first.ifBlank { meta(doc, "og:image") }.ifBlank { fallback.coverUrl },
            coverReferer = if (img != null) pair.second else url,
            category = meta(doc, "og:novel:category").ifBlank { fallback.category },
            status = meta(doc, "og:novel:status").ifBlank { doc.selectFirst(".state")?.text().orEmpty() },
            description = doc.selectFirst(".desc, .des_content, .bookintro p, #intro")?.text()
                ?.replace(Regex("^小说简介[：:]\\s*"), "")?.takeIf { it.isNotBlank() }
                ?: meta(doc, "og:description", "description"),
            latestChapter = meta(doc, "og:novel:latest_chapter_name").ifBlank {
                doc.selectFirst(".detail_bt .last a, #info p:last-child a")?.text().orEmpty()
            },
            updateTime = meta(doc, "og:novel:update_time").ifBlank {
                doc.select(".last, #info p").firstOrNull { it.text().startsWith("最后更新") }?.text()
                    ?.replace(Regex("^最后更新[：:]\\s*"), "").orEmpty()
            },
            chapterCount = count,
        )
    }

    fun catalog(doc: Document, source: NovelHtmlSource, book: String): NovelCatalogPage {
        // A selector list is evaluated in document order, not selector priority. Piquge's
        // parent .chapterlist also contains a reverse-ordered recent-updates section.
        val container = doc.selectFirst(".chapterlist .all")
            ?: (if (source.id == "bqgnovels") doc.selectFirst("main .v-list.list, .v-list.list") else null)
            ?: doc.selectFirst(".chapterlist, .chapter .v-list, #list, .listmain, .catalog-body, .list-chapter")
            ?: doc.select("dl").firstOrNull { it.select("dd a").size > 0 }
            ?: throw IllegalStateException("${source.name}未找到章节目录，网页规则可能已变化")
        val seen = linkedMapOf<String, NovelChapter>()
        for (a in container.select("a[href]")) {
            val target = linkTarget(a)
            if (target.isBlank()) continue
            val url = absolute(doc.location(), target)
            val title = a.text().trim()
            if (title.isNotEmpty() && chapterBelongs(url, book, source) && !seen.containsKey(url)) {
                seen[url] = NovelChapter(seen.size, title, url)
            }
        }
        val pages = linkedSetOf<String>()
        for (node in doc.select("a[href], select option[value]")) {
            val raw = if (node.tagName() == "option") node.attr("value") else linkTarget(node)
            if (raw.isBlank()) continue
            val target = absolute(doc.location(), raw)
            if (target.isEmpty() || !source.owns(target)) continue
            val parsed = target.toHttpUrlOrNull() ?: continue
            val root = book.toHttpUrlOrNull()!!
            val isPage = when (source.id) {
                "piquge" -> Regex(Regex.escape(root.encodedPath) + "[0-9]+/").matches(parsed.encodedPath)
                "bqgnovels" -> parsed.encodedPath.trimEnd('/') == root.encodedPath.trimEnd('/') &&
                    parsed.queryParameter("page")?.toIntOrNull() != null && parsed.queryParameter("sort") != "1"
                else -> target.startsWith(book) && Regex("ml[0-9]+\\.html").containsMatchIn(parsed.encodedPath)
            }
            if (isPage && target != doc.location()) pages.add(target)
        }
        // Only an explicit total in the selected full-catalog heading is authoritative.
        // Do not interpret page ranges or the separate recent-updates list as totals.
        val declared = container.select("h1, h2, h3, h4, .catalog-title").asSequence()
            .mapNotNull { Regex("共\\s*([0-9,]+)\\s*章").find(it.text())
                ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
            .firstOrNull() ?: 0
        return NovelCatalogPage(seen.values.toList(), pages.toList(), declared)
    }

    fun catalogPageIdentity(url: String, book: String, source: NovelHtmlSource): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val root = book.toHttpUrlOrNull() ?: return url
        if (source.id == "piquge" &&
            (parsed.encodedPath == root.encodedPath || parsed.encodedPath == root.encodedPath + "1/")) {
            // The book page and /1/ are the same first 100 chapters. Later pages link
            // back to /1/ through their previous-page link and all-pages dropdown.
            return root.newBuilder().query(null).fragment(null).build().toString()
        }
        return withoutFragment(parsed)
    }

    fun logicalChapter(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val path = parsed.encodedPath.replace(Regex("_([0-9]+)(?=\\.html$)"), "")
        return parsed.newBuilder().encodedPath(path).removeAllQueryParameters("page").fragment(null).build().toString()
    }

    fun sameChapterNext(doc: Document, firstUrl: String, source: NovelHtmlSource): String? {
        val nodes = doc.select("a[rel=next], #next_url, .next a, a:contains(下一页), a:contains(下页)")
        for (a in nodes) {
            val href = a.attr("href").takeUnless { it.startsWith("javascript:") } ?: Regex(
                "(?:location(?:\\.href)?\\s*=)\\s*['\"]([^'\"]+)['\"]"
            ).find(a.attr("onclick"))?.groupValues?.get(1).orEmpty()
            val next = absolute(doc.location(), href)
            if (next != doc.location() && source.owns(next) && logicalChapter(next) == logicalChapter(firstUrl)) return next
        }
        return null
    }

    fun chapterText(doc: Document, source: NovelHtmlSource): String {
        val content = (if (source.id == "bqgnovels") doc.selectFirst("main h1.text_tit ~ div.text") else null)
            ?: doc.selectFirst("#chaptercontent, #content, #htmlContent, .read-content, .word_read, .txt_content, .reader-content, .content-text")
            ?: throw IllegalStateException("${source.name}未返回可读取正文，可能需要验证或网页规则已变化")
        val clean = content.clone()
        clean.select("script, style, iframe, ins, .ad, .ads, #morecontent, .readpage, .readvote, .navigation").remove()
        val html = clean.html().replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        return cleanText(html, doc.location()).also { if (it.isBlank()) throw IllegalStateException("${source.name}正文为空，请重试或换源") }
    }

    fun cleanText(html: String, sourcePage: String = ""): String {
        val doc = Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n"))
        doc.select("script,style,noscript,iframe,ins.adsbygoogle,.ad,.ads,.advertisement,[id^=ads_],[class^=ads-],.readpage,.readvote").remove()
        val text = doc.wholeText()
        val sourceHost = sourcePage.toHttpUrlOrNull()?.host?.removePrefix("www.")?.removePrefix("m.")
        return text.lines().map { it.replace('\u00a0', ' ').trim() }.filterNot { line ->
            // A source appends a standalone self-link between story and footer.
            // Strip that site's link only, retaining links that are story text.
            sourceHost != null && Regex("^https?://[^\\s<>]+$").matches(line) &&
                line.toHttpUrlOrNull()?.host?.removePrefix("www.")?.removePrefix("m.") == sourceHost
        }.filter { line ->
            line.isNotEmpty() && !Regex("^(?:.*提示您：看后求收藏.*|[（(]?本章未完[，,。].*|更多内容加载中.*|本站只支持手机浏览器访问.*|微信搜索公众号.*|【领现金红包】.*|@无限好文，尽在晋江文学城|(?:请)?(?:记住|牢记|收藏)本站(?:最新)?(?:域名|网址|地址)[：: ].*|请(?:记住|牢记)本书首发(?:域名|网址)?[：: ].*|本书(?:首发|来自|由)[^。]*?(?:小说网|阅读网|飞卢|https?://).*|(?:最新网址|首发域名)[：: ].*)$").matches(line)
        }.joinToString("\n\n") { "　　$it" }
    }

    fun checkPage(doc: Document, source: NovelHtmlSource) {
        val title = doc.title()
        if (title.contains("检查您的浏览器") || title.contains("Just a moment", true) || doc.selectFirst("#cf-challenge-running, form#challenge-form") != null) {
            throw IllegalStateException("${source.name}要求浏览器验证，当前无法直接搜索；可从书城或粘贴书籍链接添加")
        }
    }
}
