package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes the original imported rule objects. Selecting a source never selects another provider. */
class LegadoSourceAdapter(private val client: OkHttpClient, private val library: ReaderSourceLibrary,
    private val phaseObserver: (ReaderSourcePhase) -> Unit = {}) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; allowTrailingComma = true }
    private val ruleDataJson = Json { allowTrailingComma = true }
    private data class Page(val text: String, val url: String, val mediaType: String = "")
    private data class CachedCatalog(val time: Long, val chapters: List<NovelChapter>)
    private data class CachedPage(val time: Long, val page: Page)
    private val catalogs = ConcurrentHashMap<String, CachedCatalog>()
    private val details = ConcurrentHashMap<String, CachedPage>()
    private val bookVariables = ConcurrentHashMap<String, Map<String, String>>()
    // Source scripts keep dynamic-domain/ad-filter state across operations,
    // while never sharing variables with a different imported source ID.
    private val sourceVariables = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val chapterBooks = ConcurrentHashMap<String, String>()
    private val chapterTitles = ConcurrentHashMap<String, String>()
    private val catalogLocations = ConcurrentHashMap<String, String>()

    private suspend fun source(id: String): ImportedReaderSource = library.find(id)
        ?.also { if (!it.enabled) throw IOException("此书源已停用，请重新选择。") }
        ?: throw IOException("书源库中未找到所选来源，请重新选择。")

    private inner class Session(val source: ImportedReaderSource, val variables: MutableMap<String, String>,
        val jobContext: kotlin.coroutines.CoroutineContext) {
        val runtime = LegadoRuleRuntime(ajax = { request ->
            runBlocking(jobContext) { fetch(request, source.url, this@Session).text }
        }, jsLib = source.rules.text("jsLib"),
            sourceVariables = sourceVariables.getOrPut(source.id) { ConcurrentHashMap() })
        val vars get() = mapOf("sourceKey" to source.url, "source.bookSourceUrl" to source.url,
            "source.bookSourceName" to source.name, "page" to "1") + variables
        suspend fun field(content: String, rule: String, base: String, extra: Map<String, String> = emptyMap()): String =
            if (rule.isBlank()) "" else runtime.string(content, rule, base, vars + extra).trim()
        suspend fun template(content: String, rule: String, base: String, extra: Map<String, String> = emptyMap()): String =
            runtime.template(content, rule, base, vars + extra).trim()
    }

    private suspend fun session(id: String, variables: Map<String, String> = emptyMap()) =
        Session(source(id), variables.toMutableMap(), currentCoroutineContext())

    suspend fun headersForSource(id: String, referer: String = ""): Map<String, String> {
        val session = session(id)
        return sourceHeaders(session, referer.ifBlank { session.source.url })
    }

    suspend fun search(keyword: String, id: String): List<NovelBook> = withContext(Dispatchers.IO) {
        val session = session(id, mapOf("key" to keyword.trim()))
        val expression = session.source.rules.text("searchUrl")
        if (expression.isBlank()) throw IOException("${session.source.name}未提供搜索规则。")
        val page = fetch(expression, session.source.url, session)
        parseBooks(page, session.source.rules.obj("ruleSearch"), session)
    }

    suspend fun discover(id: String): List<NovelBook> = withContext(Dispatchers.IO) {
        val session = session(id)
        val expression = session.source.rules.text("exploreUrl").ifBlank { session.source.url }
        val expanded = session.template("", expression, session.source.url)
        val candidates = runCatching { json.parseToJsonElement(expanded) as? JsonArray }.getOrNull()
        val address = candidates?.mapNotNull { (it as? JsonObject)?.text("url")?.takeIf(String::isNotBlank) }?.firstOrNull()
            ?: expanded.lineSequence().map { it.trim().substringAfter("::", it.trim()) }
                .firstOrNull { it.isNotBlank() && (it.startsWith("http") || it.startsWith("/")) }
            ?: throw IOException("${session.source.name}未提供可打开的书城分类。")
        val page = fetch(address, session.source.url, session)
        val rules = session.source.rules.obj("ruleExplore").takeIf { it.text("bookList").isNotBlank() }
            ?: session.source.rules.obj("ruleSearch")
        val listed = parseBooks(page, rules, session)
        if (listed.isNotEmpty() || session.source.type != 0) return@withContext listed
        // Traditional novel homepages use illustrated .item cards, while their
        // search page uses .bookbox. Read the actual homepage cards if supplied
        // search selectors do not apply; keep the same imported rule/source ID.
        val headers = sourceHeaders(session, page.url)
        Jsoup.parse(page.text, page.url).select(".item:has(dl dt a[href]):has(img)").take(36).mapNotNull { row ->
            val title = row.selectFirst("dt a[href]") ?: return@mapNotNull null
            val link = address(title.attr("href"), page.url)
            if (!validBookAddress(link) || title.text().isBlank()) return@mapNotNull null
            val cover = address(row.selectFirst("img")?.attr("src").orEmpty(), page.url)
            NovelBook(stableId(session.source.id, link),title.text(),author = row.selectFirst("dt span")?.text().orEmpty(),
                coverUrl = splitOptions(cover).first,detailUrl = link,sourceId = session.source.id,
                description = row.selectFirst("dd")?.text().orEmpty(),coverReferer = page.url,
                requestHeaders = headers,coverRequestHeaders = headers).also(::rememberBook)
        }.distinctBy { it.detailUrl }
    }

    private suspend fun parseBooks(page: Page, rules: JsonObject, session: Session): List<NovelBook> =
        measured(session.source.id, "search_rules") { parseBookRows(page, rules, session) }

    private suspend fun parseBookRows(page: Page, rules: JsonObject, session: Session): List<NovelBook> {
        val listRule = rules.text("bookList")
        if (listRule.isBlank()) throw IOException("${session.source.name}缺少作品列表规则。")
        val nodes = session.runtime.elements(page.text, listRule, page.url, session.vars)
        val headers = sourceHeaders(session, page.url)
        val books = linkedMapOf<String, NovelBook>()
        // A malicious or broken list rule must not fill the mobile heap during search.
        for (node in nodes.take(100)) {
            currentCoroutineContext().ensureActive()
            val rowVariables = mutableMapOf<String, String>()
            suspend fun field(key: String) = session.field(node, rules.text(key), page.url, rowVariables)
            val name = plain(field("name"))
            rowVariables["book.name"] = name
            val author = plain(field("author"))
            rowVariables["book.author"] = author
            val kind = plain(field("kind"))
            if (kind.length > 4_096) continue
            rowVariables["book.kind"] = kind
            val link = address(field("bookUrl"), page.url)
            if (name.isBlank() || !validBookAddress(link)) continue
            val cover = coverAddress(node, rules.text("coverUrl"), page.url, session, rowVariables)
            val book = NovelBook(id = stableId(session.source.id, link), title = name,
                author = author, coverUrl = splitOptions(cover).first,
                detailUrl = link, sourceId = session.source.id, latestChapter = plain(field("lastChapter")),
                updateTime = plain(field("updateTime")), category = displayKind(kind),
                status = plain(field("status")), description = plain(field("intro")),
                isComic = session.source.type == 2, isAudio = session.source.type == 1,
                coverReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty(),
                requestHeaders = headers,
                coverRequestHeaders = headers + optionHeaders(splitOptions(cover).second),
                sourceVariables = mapOf("book.kind" to kind))
            books.putIfAbsent(link, book)
            rememberBook(book)
        }
        return books.values.toList()
    }

    suspend fun detail(book: NovelBook, requireIdentityEvidence: Boolean = false): NovelBook = withContext(Dispatchers.IO) {
        rememberBook(book)
        val session = session(book.sourceId, variables(book))
        val rules = session.source.rules.obj("ruleBookInfo")
        if (rules.isEmpty()) {
            if (requireIdentityEvidence) throw IOException("来源详情未提供可核对的书名和作者，暂不能验证正文。")
            return@withContext book
        }
        val page = detailPage(book.detailUrl, session)
        measured(book.sourceId, "detail_rules") {
            val initialized = rules.text("init").takeIf(String::isNotBlank)?.let {
                session.field(page.text, it, page.url)
            }?.takeIf(String::isNotBlank) ?: page.text
            suspend fun field(key: String) = session.field(initialized, rules.text(key), page.url)
            val kind = plain(field("kind")).ifBlank { book.sourceVariables["book.kind"].orEmpty() }
            session.variables["book.kind"] = kind
            val toc = resolveToc(initialized, page.url, session)
            catalogLocations[cacheKey(book.detailUrl, book.sourceId)] = toc
            val cover = coverAddress(initialized, rules.text("coverUrl"), page.url, session)
            val verifiedTitle = plain(field("name"))
            val verifiedAuthor = plain(field("author"))
            val declaredIntro = plain(field("intro"))
            // An older selector can survive while the site's layout changes. Read
            // the fetched page's standard metadata, never the search row's inherited
            // synopsis, when that selector supplies too little independent evidence.
            val verifiedIntro = if (declaredIntro.count { it.isLetterOrDigit() } >= 72) declaredIntro else {
                Jsoup.parse(page.text, page.url).select("meta[property=\"og:description\"], meta[name=\"description\"]")
                    .asSequence().map { plain(it.attr("content")) }
                    .firstOrNull { it.length <= 12_000 && it.count { character -> character.isLetterOrDigit() } >= 72 }
                    ?: declaredIntro
            }
            if (requireIdentityEvidence && (verifiedTitle.isBlank() || verifiedAuthor.isBlank()))
                throw IOException("来源详情缺少可核对的书名或作者，暂不能验证正文。")
            book.copy(title = verifiedTitle.ifBlank { book.title },
                author = verifiedAuthor.ifBlank { book.author },
                coverUrl = splitOptions(cover).first.ifBlank { book.coverUrl },
                description = verifiedIntro.ifBlank { book.description },
                latestChapter = plain(field("lastChapter")).ifBlank { book.latestChapter },
                category = displayKind(kind).ifBlank { book.category },
                status = plain(field("status")).ifBlank { book.status },
                requestHeaders = book.requestHeaders + sourceHeaders(session, page.url),
                coverRequestHeaders = sourceHeaders(session, page.url) + optionHeaders(splitOptions(cover).second),
                isComic = session.source.type == 2, isAudio = session.source.type == 1,
                sourceVariables = book.sourceVariables + ("book.kind" to kind)).also(::rememberBook)
        }
    }

    private suspend fun detailPage(url: String, session: Session): Page {
        val key = cacheKey(url, session.source.id)
        details[key]?.takeIf { System.currentTimeMillis() - it.time in 0..300_000 }?.let { return it.page }
        return fetch(url, session.source.url, session).also {
            if (details.size >= 24) details.entries.minByOrNull { it.value.time }?.let { old -> details.remove(old.key) }
            details[key] = CachedPage(System.currentTimeMillis(), it)
        }
    }

    private suspend fun resolveToc(content: String, baseUrl: String, session: Session): String {
        val tocRule = session.source.rules.obj("ruleBookInfo").text("tocUrl")
        if (tocRule.isBlank() || tocRule == session.source.rules.obj("ruleToc").text("chapterList")) return baseUrl
        return address(session.field(content, tocRule, baseUrl), baseUrl).ifBlank { baseUrl }
    }

    fun invalidateCatalog(url: String, sourceId: String) {
        val key = cacheKey(url, sourceId)
        catalogs.remove(key); details.remove(key); catalogLocations.remove(key)
    }

    suspend fun catalog(bookUrl: String, id: String, progress: ((List<NovelChapter>) -> Unit)? = null): List<NovelChapter> =
        withContext(Dispatchers.IO) {
        val cacheKey = cacheKey(bookUrl, id)
        catalogs[cacheKey]?.takeIf { System.currentTimeMillis() - it.time in 0..300_000 }?.let {
            progress?.invoke(it.chapters); return@withContext it.chapters
        }
        val session = session(id, bookVariables[cacheKey].orEmpty())
        val rules = session.source.rules.obj("ruleToc")
        val listExpression = rules.text("chapterList")
        if (listExpression.isBlank()) throw IOException("${session.source.name}缺少目录规则。")
        val reverse = listExpression.startsWith("-")
        val listRule = listExpression.removePrefix("-").removePrefix("+")
        val detailPage = detailPage(bookUrl, session)
        val initRule = session.source.rules.obj("ruleBookInfo").text("init")
        val firstPage = if (initRule.isBlank()) detailPage else detailPage.copy(text =
            session.field(detailPage.text, initRule, detailPage.url).ifBlank { detailPage.text })
        val initialAddress = catalogLocations[cacheKey] ?: resolveToc(firstPage.text, firstPage.url, session)
        val pending = ArrayDeque<Pair<String, Int>>().apply { add(initialAddress to 1) }
        val visited = mutableSetOf<String>()
        val found = linkedMapOf<String, NovelChapter>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (url, pageNumber) = pending.removeFirst()
            if (!visited.add(url)) continue
            if (visited.size > 1000) throw IOException("目录分页超过保护上限，未返回截断目录。")
            val page = if (url == firstPage.url || url == bookUrl) firstPage else fetch(url, firstPage.url, session,
                extra = mapOf("page" to pageNumber.toString()))
            val extra = mapOf("page" to pageNumber.toString())
            val chapterNameRule = rules.text("chapterName")
            val chapterUrlRule = rules.text("chapterUrl")
            val fieldRules = listOf(chapterNameRule, chapterUrlRule)
            val fastRows = if (chapterUrlRule.isNotBlank()) session.runtime.simpleCssRows(page.text,
                listRule, fieldRules, page.url, session.vars + extra) else null
            val nodes = if (fastRows == null) session.runtime.elements(page.text, listRule, page.url, session.vars + extra) else emptyList()
            val rowCount = fastRows?.size ?: nodes.size
            if (rowCount == 0) throw IOException("${session.source.name}返回空目录，可能需要登录或规则已变化。")
            var added = 0
            val pageChapters = linkedMapOf<String, String>()
            for (rowIndex in 0 until rowCount) {
                currentCoroutineContext().ensureActive()
                val fields = fastRows?.get(rowIndex) ?: session.runtime.strings(nodes[rowIndex], fieldRules, page.url, session.vars + extra)
                val title = plain(fields[0])
                val rawAddress = if (chapterUrlRule.isNotBlank()) fields[1]
                    else Jsoup.parse(nodes[rowIndex], page.url).selectFirst("a[href]")?.attr("href")
                        ?: if (nodes.size == 1) page.url else ""
                val chapterAddress = address(rawAddress, page.url)
                if (title.isNotBlank() && chapterAddress.isNotBlank()) {
                    // The same page may put a latest-chapters preview before
                    // the complete directory. Keep the occurrence in the full
                    // directory, preserving its order rather than reading the
                    // latest chapter as chapter one.
                    pageChapters.remove(chapterAddress)
                    pageChapters[chapterAddress] = title
                }
            }
            for ((chapterAddress, title) in pageChapters) {
                if (!found.containsKey(chapterAddress)) {
                    found[chapterAddress] = NovelChapter(found.size, title, chapterAddress); added++
                    if (chapterBooks.size >= 8192) chapterBooks.keys.firstOrNull()?.let {
                        chapterBooks.remove(it); chapterTitles.remove(it)
                    }
                    chapterBooks[cacheKey(chapterAddress, id)] = cacheKey
                    chapterTitles[cacheKey(chapterAddress, id)] = title
                }
            }
            if (added == 0) throw IOException("${session.source.name}目录无新章节，未返回不完整目录。")
            val current = (if (reverse) found.values.reversed() else found.values.toList()).mapIndexed { i, c -> c.copy(index = i) }
            if (!reverse) progress?.invoke(current)
            val nextRule = rules.text("nextTocUrl")
            if (nextRule.isNotBlank()) {
                session.runtime.elements(page.text, nextRule, page.url, session.vars + extra).forEach { raw ->
                    val next = address(raw, page.url)
                    if (next.isNotBlank() && next !in visited && pending.none { it.first == next }) pending += next to (pageNumber + 1)
                }
            }
        }
        val result = (if (reverse) found.values.reversed() else found.values.toList()).mapIndexed { i, c -> c.copy(index = i) }
        if (result.isEmpty()) throw IOException("${session.source.name}目录为空。")
        if (reverse) progress?.invoke(result)
        if (catalogs.size >= 12) catalogs.entries.minByOrNull { it.value.time }?.let { old -> catalogs.remove(old.key) }
        catalogs[cacheKey] = CachedCatalog(System.currentTimeMillis(), result)
        result
    }

    suspend fun content(chapterUrl: String, id: String): String = withContext(Dispatchers.IO) {
        val expectedChapterTitle = chapterTitles[cacheKey(chapterUrl, id)].orEmpty()
        val session = session(id, chapterBooks[cacheKey(chapterUrl, id)]?.let(bookVariables::get).orEmpty() +
            mapOf("chapter.url" to chapterUrl, "chapter.title" to chapterTitles[cacheKey(chapterUrl, id)].orEmpty()))
        if (session.source.type == 1 && looksAudio(splitOptions(chapterUrl).first)) return@withContext splitOptions(chapterUrl).first
        val rules = session.source.rules.obj("ruleContent")
        val rule = rules.text("content")
        if (rule.isBlank()) throw IOException("${session.source.name}没有正文规则。")
        val pending = ArrayDeque<String>().apply { add(chapterUrl) }
        val visited = mutableSetOf<String>()
        val parts = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val url = pending.removeFirst()
            if (!visited.add(url)) continue
            if (visited.size > 100) throw IOException("正文分页超过保护上限，未返回截断内容。")
            val page = fetch(url, chapterUrl, session)
            if (session.source.type == 1 && page.mediaType.startsWith("audio/")) return@withContext page.url
            var actualChapterTitle = ""
            rules.text("title").takeIf(String::isNotBlank)?.let { titleRule ->
                session.field(page.text, titleRule, page.url).takeIf(String::isNotBlank)?.let {
                    actualChapterTitle = plain(it)
                    session.variables["chapter.title"] = plain(it)
                }
            }
            val parsed = session.field(page.text, rule, page.url,
                if (session.source.type == 0) mapOf("_onvyra_content_lines" to "1") else emptyMap())
            if (session.source.type == 1) {
                val audio = audioAddress(parsed, page.text, page.url, rules.text("sourceRegex"))
                if (audio.isBlank()) throw IOException("${session.source.name}未返回可播放音频；网页嗅探或登录规则尚未满足。")
                return@withContext audio
            }
            val replaceRule = rules.text("replaceRegex")
            // Imported replacement expressions already contain their ## prefix.
            // Prefixing it a second time makes an empty regex whose replacement
            // is the advert pattern itself. JavaScript replacements are rules too.
            val replacementExpression = when {
                replaceRule.startsWith("##") || replaceRule.startsWith("@js:", true) ||
                    replaceRule.startsWith("<js>", true) -> replaceRule
                else -> "##$replaceRule"
            }
            val replaced = if (replaceRule.isBlank()) parsed else session.field(parsed, replacementExpression, page.url)
            if (replaced.isBlank()) throw IOException("${session.source.name}未取得正文，可能规则已变化。")
            val clean = if (session.source.type == 2) replaced else NovelHtmlParser.cleanText(replaced, page.url)
            if (session.source.type == 0) ReaderSourceContentValidator.requireReadable(clean, expectedChapterTitle, actualChapterTitle)
            parts += clean
            val nextRule = rules.text("nextContentUrl")
            // Some supplied rules mistakenly select 下一章 as a content page.
            // Never concatenate subsequent catalogue entries into this chapter.
            if (nextRule.isNotBlank() && !nextRule.contains("下一章")) {
                val next = address(session.field(page.text, nextRule, page.url), page.url)
                if (next.isNotBlank() && next !in visited && chapterTitles[cacheKey(next, id)] == null) pending.add(next)
            }
        }
        val result = parts.joinToString("\n\n").ifBlank { throw IOException("${session.source.name}正文为空。") }
        if (session.source.type == 0) ReaderSourceContentValidator.requireReadable(result, expectedChapterTitle) else result
    }

    suspend fun comicPages(chapterUrl: String, id: String): List<String> {
        if (looksImage(splitOptions(chapterUrl).first)) return listOf(splitOptions(chapterUrl).first)
        val raw = content(chapterUrl, id)
        val doc = Jsoup.parse(raw, splitOptions(chapterUrl).first)
        val fromHtml = doc.select("img").mapNotNull { image ->
            address(image.attr("src").ifBlank { image.attr("data-src") }, chapterUrl).takeIf(String::isNotBlank)
        }
        val images = (fromHtml + raw.lineSequence().map { it.trim() }.filter {
            it.startsWith("https://") || it.startsWith("http://")
        }).map { splitOptions(it).first }.distinct()
        if (images.isEmpty()) throw IOException("漫画规则未返回图片地址，未生成空白章节。")
        return images
    }

    private suspend fun sourceHeaders(session: Session, base: String): Map<String, String> {
        val raw = session.source.rules.text("header")
        val referer = cleanReferer(base)
        if (raw.isBlank()) return mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
        val expanded = session.template("", raw, base)
        val obj = try { ruleJson(expanded) as? JsonObject } catch (e: Exception) {
            throw IOException("${session.source.name}请求头规则不是有效JSON。", e)
        } ?: throw IOException("${session.source.name}请求头规则必须返回JSON对象。")
        return (mapOf("User-Agent" to USER_AGENT, "Referer" to referer) + obj.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content.orEmpty()
        }).mapValues { (key, value) -> if (key.equals("Referer", true)) cleanReferer(value) else value }
    }

    private suspend fun fetch(expression: String, base: String, session: Session,
        extra: Map<String, String> = emptyMap()): Page {
        // Request/parse rules may themselves use java.ajax. Their duration is
        // inclusive; nested http entries distinguish that IO from interpreter work.
        val request = measured(session.source.id, "request_rules") { request(expression, base, session, extra) }
        val limitedClient = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        return measured(session.source.id, "http") { suspendCancellableCoroutine { continuation ->
            val call = limitedClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("${session.source.name}：${e.message ?: "网络请求失败"}", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val page = response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val body = it.body
                            val mediaType = body.contentType()?.toString().orEmpty()
                            if (mediaType.startsWith("audio/") || mediaType.startsWith("video/")) {
                                Page("", it.request.url.toString(), mediaType)
                            } else {
                                val byteLimit = if (session.variables.containsKey("key")) 2 * 1024 * 1024 else MAX_BYTES
                                if (body.contentLength() > byteLimit) throw IOException("书源响应过大，未返回截断数据。")
                                val bytes = body.byteStream().use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (output.size() <= byteLimit) {
                                        if (!continuation.isActive) throw IOException("请求已取消")
                                        val count = input.read(buffer, 0, minOf(buffer.size, byteLimit + 1 - output.size()))
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                if (bytes.size > byteLimit) throw IOException("书源响应过大，未返回截断数据。")
                                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
                                val charset = body.contentType()?.charset() ?: runCatching {
                                    Charset.forName(Regex("(?i)charset\\s*=\\s*['\"]?([\\w-]+)").find(head)?.groupValues?.get(1)
                                        ?: request.tag(Charset::class.java)?.name() ?: "UTF-8")
                                }.getOrDefault(Charsets.UTF_8)
                                Page(String(bytes, charset).removePrefix("\uFEFF"), it.request.url.toString(), mediaType)
                            }
                        }
                        if (continuation.isActive) continuation.resume(page)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("${session.source.name}：${e.message}", e))
                    }
                }
            })
        } }
    }

    private suspend fun <T> measured(sourceId: String, phase: String, block: suspend () -> T): T {
        val start = System.nanoTime() / 1_000_000
        var status = "cancelled"
        try { return block().also { status = "ok" } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status = "error"; throw error }
        finally { phaseObserver(ReaderSourcePhase(sourceId, phase,
            executionMs = System.nanoTime() / 1_000_000 - start, status = status, startedAtMs = start)) }
    }

    private suspend fun request(expression: String, base: String, session: Session, extra: Map<String, String>): Request {
        var raw = expression.trim()
        val scripted = raw.startsWith("@js:", true) || raw.startsWith("<js>", true)
        val rawOptions = if (scripted) JsonObject(emptyMap()) else splitOptions(raw).second
        val charsetName = rawOptions.text("charset").ifBlank { "UTF-8" }
        val charset = try { Charset.forName(charsetName) } catch (e: Exception) { throw IOException("不支持的书源编码：$charsetName", e) }
        val variables = session.vars + extra
        // Legado's key is text inside JavaScript, and URL/form encoded in literal templates.
        val encoded = variables["key"]?.let { URLEncoder.encode(it, charset.name()) }.orEmpty()
        raw = if (scripted) session.template("", raw, base, extra)
        else session.template("", raw, base, extra + (if (variables.containsKey("key")) mapOf("key" to encoded) else emptyMap()))
        val pageNumber = variables["page"]?.toIntOrNull() ?: 1
        raw = Regex("<([^<>]*?),([^<>]*?)>").replace(raw) { if (pageNumber <= 1) it.groupValues[1] else it.groupValues[2] }
        val (urlText, options) = splitOptions(raw)
        val resolvedUrl = base.toHttpUrlOrNull()?.resolve(urlText) ?: urlText.toHttpUrlOrNull()
            ?: throw IOException("${session.source.name}规则未返回有效的HTTP地址。")
        val url = canonicalSourceUrl(resolvedUrl)
        if (url.host.endsWith(".local") && !url.host.equals("localhost", true)) throw IOException("此书源需要动态域名脚本；脚本未解析出真实服务地址。")
        val headers = sourceHeaders(session, base) + optionHeaders(options)
        val builder = Request.Builder().url(url).tag(Charset::class.java, charset)
        headers.forEach { (key, value) -> if (key.isNotBlank()) builder.header(key, value) }
        val method = options.text("method").ifBlank { "GET" }.uppercase()
        val body = options["body"]
        if (method != "GET" && method != "HEAD") {
            val content = if (body is JsonPrimitive) body.content else body?.toString().orEmpty()
            val media = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?: if (body is JsonObject || body is JsonArray) "application/json; charset=${charset.name()}"
                else "application/x-www-form-urlencoded; charset=${charset.name()}"
            builder.method(method, content.toByteArray(charset).toRequestBody(media.toMediaTypeOrNull()))
        } else builder.method(method, null)
        return builder.build()
    }

    private fun splitOptions(value: String): Pair<String, JsonObject> {
        val match = Regex(",\\s*(?=\\{)").find(value) ?: return value.trim() to JsonObject(emptyMap())
        val options = runCatching { ruleJson(value.substring(match.range.last + 1).trim()) as? JsonObject }.getOrNull()
            ?: throw IOException("书源地址附加选项不是有效JSON。")
        return value.substring(0, match.range.first).trim() to options
    }
    private fun optionHeaders(options: JsonObject): Map<String, String> =
        (options["headers"] as? JsonObject)?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()

    /** Old source files use single-quoted data objects; parse data without evaluating JavaScript. */
    private fun ruleJson(raw: String): JsonElement {
        try { return ruleDataJson.parseToJsonElement(raw) } catch (_: kotlinx.serialization.SerializationException) { }
        val normalized = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            when (val char = raw[index]) {
                '"' -> {
                    // Standard JSON strings pass through verbatim, including apostrophes.
                    normalized.append(char); index++
                    while (index < raw.length) {
                        val next = raw[index++]; normalized.append(next)
                        if (next == '\\' && index < raw.length) normalized.append(raw[index++])
                        else if (next == '"') break
                    }
                }
                '\'' -> {
                    index++
                    val value = StringBuilder()
                    var closed = false
                    while (index < raw.length) {
                        val next = raw[index++]
                        if (next == '\'') { closed = true; break }
                        if (next != '\\') { value.append(next); continue }
                        if (index >= raw.length) throw IOException("书源JSON单引号字符串未闭合。")
                        when (val escape = raw[index++]) {
                            '\'', '"', '\\', '/' -> value.append(escape)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000c')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> {
                                if (index + 4 > raw.length) throw IOException("书源JSON Unicode转义不完整。")
                                val code = raw.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IOException("书源JSON Unicode转义无效。")
                                value.append(code.toChar()); index += 4
                            }
                            else -> throw IOException("书源JSON包含不支持的单引号转义。")
                        }
                    }
                    if (!closed) throw IOException("书源JSON单引号字符串未闭合。")
                    normalized.append(JsonPrimitive(value.toString()).toString())
                }
                else -> { normalized.append(char); index++ }
            }
        }
        return ruleDataJson.parseToJsonElement(normalized.toString())
    }

    private suspend fun coverAddress(content: String, rule: String, base: String, session: Session,
        extra: Map<String, String> = emptyMap()): String {
        val plain = rule.trim()
        // Some bundled HTML rules still read img@src while the site moved the
        // actual cover into a lazy attribute (xs8 serves the same SVG placeholder
        // for every src). Reuse the declared selector, so another image in the row
        // cannot supply unrelated artwork. Script/JSON/replacement rules keep
        // their original semantics and source IDs remain unchanged.
        if (content.trimStart().startsWith("<") && plain.endsWith("@src") &&
            !Regex("@(?:js|json|xpath):|<js>|##|\\|\\||&&", RegexOption.IGNORE_CASE).containsMatchIn(plain) &&
            !plain.startsWith("//")) {
            for (attribute in listOf("data-src", "data-original", "data-lazy-src")) {
                if (!content.contains(attribute, ignoreCase = true)) continue
                val value = session.field(content, plain.removeSuffix("src") + attribute, base, extra)
                address(value, base).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return address(session.field(content, rule, base, extra), base)
    }

    private fun address(raw: String, base: String): String {
        val value = raw.lineSequence().map { it.trim() }.firstOrNull(String::isNotBlank).orEmpty()
        if (value.isBlank() || value == "null" || value.startsWith("javascript:", true) || value == "#") return ""
        val (url, options) = splitOptions(value)
        val link = if (url.startsWith("<")) Jsoup.parse(url, base).selectFirst("a[href],img[src]")?.let {
            it.attr(if (it.tagName() == "img") "src" else "href")
        }.orEmpty() else url
        if (link.isBlank()) return ""
        val resolved = base.toHttpUrlOrNull()?.resolve(link) ?: link.toHttpUrlOrNull() ?: return ""
        return canonicalSourceUrl(resolved).toString() + if (options.isNotEmpty()) "," + options.toString() else ""
    }
    private fun audioAddress(parsed: String, raw: String, base: String, sourceRegex: String): String {
        val direct = parsed.trim().takeIf { !it.contains('\n') && !it.contains('<') }?.let { address(it, base) }.orEmpty()
        if (direct.isNotBlank() && (looksAudio(direct) || parsed.trim().startsWith("http"))) return splitOptions(direct).first
        val doc = Jsoup.parse(parsed + "\n" + raw, base)
        doc.select("audio[src],audio source[src],video source[src]").forEach {
            val link = address(it.attr("src"), base)
            if (link.isNotBlank()) return splitOptions(link).first
        }
        val urls = Regex("https?://[^\\s\"'<>\\\\]+", RegexOption.IGNORE_CASE).findAll(parsed + "\n" + raw)
            .map { it.value.replace("&amp;", "&") }
        val required = sourceRegex.takeIf(String::isNotBlank)?.let { runCatching { Regex(it) }.getOrNull() }
        return urls.firstOrNull { looksAudio(it) && (required == null || required.containsMatchIn(it)) }.orEmpty()
    }
    private fun rememberBook(book: NovelBook) {
        if (bookVariables.size >= 256) bookVariables.keys.firstOrNull()?.let(bookVariables::remove)
        bookVariables[cacheKey(book.detailUrl, book.sourceId)] = variables(book)
    }
    private fun variables(book: NovelBook) = book.sourceVariables + mapOf("book.name" to book.title, "book.author" to book.author,
        "book.kind" to book.sourceVariables["book.kind"].orEmpty().ifBlank { book.category },
        "book.bookUrl" to book.detailUrl, "book.coverUrl" to book.coverUrl, "book.intro" to book.description)
    /** These exact legacy hosts returned HTTP 301 to these hosts, verified 2026-11-04.
     * Keep the supplied source/rule ID unchanged; avoid a dead redirect hop on mobile networks. */
    private fun canonicalSourceUrl(url: okhttp3.HttpUrl): okhttp3.HttpUrl {
        val host = when (url.host) {
            "www.rulianshi.org" -> "www.rulianshi.cc"
            "m.rulianshi.la" -> "m.rulianshi.cc"
            "www.yetianlian.info" -> "www.xyetianlian.com"
            else -> return url
        }
        return url.newBuilder().host(host).build()
    }
    private fun cleanReferer(value: String): String = value.toHttpUrlOrNull()?.let(::canonicalSourceUrl)?.newBuilder()?.fragment(null)?.build()?.toString()
        ?: value.substringBefore('#')
    private fun displayKind(value: String): String = value.takeUnless { it.matches(Regex("[0-9a-fA-F-]{8,}")) }.orEmpty()
    private fun validBookAddress(value: String): Boolean {
        if (value.isBlank()) return false
        val (raw, options) = splitOptions(value)
        val url = raw.toHttpUrlOrNull() ?: return false
        if (url.encodedPath.contains("%7B", true) || url.encodedPath.contains("%7D", true)) return false
        val identityNames = setOf("id", "bookid", "novelid", "resourceid", "bid", "aid", "comicid", "albumid")
        if (url.queryParameterNames.any { name ->
                val identity = url.queryParameter(name)
                name.lowercase() in identityNames && (identity.isNullOrBlank() ||
                    identity.equals("null", true) || identity.equals("undefined", true) ||
                    identity.contains('{') || identity.contains('}'))
            }) return false
        val body = options.text("body")
        if (Regex("(?i)(?:^|[&\\s])(?:bookid|novelid|resourceid|bid|aid|comicid|albumid)=(?=&|$)").containsMatchIn(body)) return false
        return true
    }
    private fun plain(raw: String): String {
        if (!raw.contains('<')) return org.jsoup.parser.Parser.unescapeEntities(raw, false).trim()
        val doc = Jsoup.parseBodyFragment(raw)
        doc.outputSettings(Document.OutputSettings().prettyPrint(false))
        doc.select("script,style").remove()
        doc.select("br").append("\n")
        doc.select("p,div,li").prepend("\n").append("\n")
        return doc.body().wholeText().replace('\u00a0', ' ').replace(Regex("[\\t ]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }
    private fun stableId(source: String, url: String) = source + "_" + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    private fun cacheKey(url: String, source: String) = "$source:$url"
    private fun looksAudio(url: String) = Regex("(?i)\\.(mp3|m4a|m4b|aac|ogg|wav|flac|m3u8)(?:[?#,]|$)").containsMatchIn(url)
    private fun looksImage(url: String) = Regex("(?i)\\.(jpe?g|png|webp|gif|avif)(?:[?#,]|$)").containsMatchIn(url)
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
