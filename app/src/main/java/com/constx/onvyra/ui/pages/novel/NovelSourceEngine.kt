package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Local adapters consume actual source data. Missing/blocked content is never fabricated. */
class NovelSourceEngine(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(true).build(),
    private val sourceCacheDir: File? = null,
    val sourceLibrary: ReaderSourceLibrary? = null,
    private val skinIndexSeed: (() -> InputStream)? = null) {
    companion object {
        const val SOURCE_MERGE = "merge"
        const val SOURCE_BQGEEE = "bqgeee"
        const val SOURCE_45ZW = "zw45"
        const val SOURCE_PIQUGE = "piquge"
        const val SOURCE_BQGNOVELS = "bqgnovels"
        const val SOURCE_SKIN = "biquge_skin"
        const val SOURCE_CATALOG = "bookstore_catalog"
        private val RULES = listOf(
            NovelHtmlSource(SOURCE_BQGNOVELS, "新笔趣阁", "https://www.bqgnovels.com", "/", "/api/query/search", "keyword"),
            NovelHtmlSource(SOURCE_PIQUGE, "笔趣阁 Piquge", "https://www.piquge.com", "/paihangbang/", "/search/", "searchkey"),
            NovelHtmlSource(SOURCE_45ZW, "四五源（旧书兼容）", "https://www.45zw.la", "/", "/search.html", "s", true),
            NovelHtmlSource(SOURCE_BQGEEE, "笔趣阁 BZ（旧书兼容）", "https://www.biquge.bz", "/", "/search.html", "s", true))
        val AVAILABLE_SOURCES = listOf(NovelSource(SOURCE_MERGE, "多源聚合", "", true,
            "自动搜索当前分类已启用书源，同名同作者合并，结果逐步显示")) + RULES.map {
            NovelSource(it.id, it.name, it.baseUrl, description = when (it.id) {
                SOURCE_PIQUGE -> "排行榜、详情、完整分页目录；搜索遇验证时可粘贴作品链接"
                SOURCE_BQGNOVELS -> "备用书源；连接失败时可使用聚合搜索匹配其他来源"
                else -> "旧书源兼容，连接失效时请换源"
            })
        }
        val AVAILABLE_COMIC_SOURCES = ComicSourceEngine.SOURCES
        private const val LIMIT = 8 * 1024 * 1024
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    }
    private val diagnostics = ConcurrentHashMap<String, String>()
    private val readingRoutes = ReaderReadingRouteCache(sourceCacheDir?.let { File(it, "reader-reading-routes-v1") })
    private val sourceDiscovery = ReaderSourceDiscoveryStore(sourceCacheDir?.let { File(it, "reader-source-discovery-v1") })
    private val sourceDiscoveryGates = List(16) { Mutex() }
    internal fun useReadingRouteDirectory(directory: File) { readingRoutes.useDirectory(directory) }
    internal fun useSourceDiscoveryDirectory(directory: File) { sourceDiscovery.useDirectory(directory) }
    internal suspend fun recordReadingRouteFailure(book: NovelBook, identityMismatch: Boolean = false) =
        withContext(Dispatchers.IO) { readingRoutes.failed(book, identityMismatch) }
    internal suspend fun recordReadingRouteSuccess(book: NovelBook) = withContext(Dispatchers.IO) {
        readingRoutes.put(book, successfulRead = true)
        recordSourceAvailability(book, ReaderSourceAvailability(book.sourceId, book.detailUrl, ReaderSourceAvailability.USABLE,
            System.currentTimeMillis(), book.lastReadChapterTitle, book.lastReadChapterUrl, book.lastReadChapterIndex))
    }
    private val routeGates = List(16) { Mutex() }
    // Shared across metadata, exact-route checks and parallel retitling checks.
    // Candidate fairness must never multiply the catalog's ten active IO slots.
    private val catalogRequests = ReaderPriorityGate(10)
    // Two independent verifications can share a host without one stalled
    // candidate holding up every other title from that provider.
    private val catalogHostGates = List(128) { kotlinx.coroutines.sync.Semaphore(2) }
    private val phaseTrace = ReaderPhaseTrace()
    internal fun sourcePhaseSnapshot(): List<ReaderSourcePhase> = phaseTrace.snapshot()
    internal fun clearSourcePhases() = phaseTrace.clear()

    private suspend fun <T> catalogRequest(sourceId: String, phase: String, budget: ReaderActiveBudget,
        verification: Boolean = true, maximumMs: Long = Long.MAX_VALUE, block: suspend () -> T): T? {
        val host = sourceLibrary?.find(sourceId)?.url?.toHttpUrlOrNull()?.host.orEmpty().ifBlank { sourceId }
        val hostGate = catalogHostGates[(host.lowercase(java.util.Locale.ROOT).hashCode() and Int.MAX_VALUE) % catalogHostGates.size]
        val queuedAt = System.nanoTime() / 1_000_000
        var acquiredAt = 0L
        var status = "cancelled"
        try {
            return hostGate.withPermit { catalogRequests.withPermit(verification) {
                acquiredAt = System.nanoTime() / 1_000_000
                if (budget.remaining() <= 0) { status = "budget_exhausted"; return@withPermit null }
                budget.enter()
                try {
                    val result = withTimeoutOrNull(minOf(maximumMs, budget.remaining().coerceAtLeast(1))) { block() }
                    status = if (result == null) "timeout" else "ok"
                    result
                } finally { budget.leave() }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status = "error"; throw error }
        finally {
            val endedAt = System.nanoTime() / 1_000_000
            phaseTrace.record(ReaderSourcePhase(sourceId, phase,
                queueMs = (if (acquiredAt == 0L) endedAt else acquiredAt) - queuedAt,
                executionMs = if (acquiredAt == 0L) 0 else endedAt - acquiredAt,
                status = status, startedAtMs = queuedAt))
        }
    }
    internal fun storeRepository(directory: File) = ReaderStoreRepository(client, directory)

    /** Curated catalog entries describe a work; only real exact-title/author hits may read it. */
    internal suspend fun resolveCatalogBook(book: NovelBook): NovelBook = withContext(Dispatchers.IO) {
        require(book.sourceId == SOURCE_CATALOG && book.title.isNotBlank() && book.author.isNotBlank()) { "书城作品缺少书名或作者，无法准确匹配来源。" }
        val identity = ReaderBookIdentity.key(book)
        requestGate(routeGates, identity).withLock {
        val enabled = sourceLibrary?.searchable(0).orEmpty()
        val enabledIds = enabled.mapTo(hashSetOf()) { it.id }
        val savedRoute = readingRoutes.get(book, enabledIds)
        // Rechecking an in-memory metadata hit shares the same total deadline
        // as discovery; a broken cached hit cannot add another full scan budget.
        val selected = savedRoute ?: withTimeoutOrNull(14_000) {
            verifiedCatalogHit(book, aggregate.cachedMatches(book, enabledIds), ReaderActiveBudget(6_000))
                ?: findCatalogRoute(book, enabled)
        } ?: throw IOException("暂未找到这本书的可读来源，请重试查找；书城信息已保留。")
        if (savedRoute == null) readingRoutes.putResolved(book, selected)
        selected.copy(coverUrl = book.coverUrl.ifBlank { selected.coverUrl },
            coverReferer = if (book.coverUrl.isNotBlank()) book.coverReferer else selected.coverReferer,
            coverRequestHeaders = if (book.coverUrl.isNotBlank()) book.artworkHeaders else selected.artworkHeaders,
            description = book.description.ifBlank { selected.description },category = book.category,
            status = book.status.ifBlank { selected.status })
        }
    }

    private suspend fun findCatalogRoute(book: NovelBook, enabled: List<ImportedReaderSource>): NovelBook {
        val ranked = enabled.sortedByDescending {
            val host = it.url.toHttpUrlOrNull()?.host.orEmpty()
            aggregate.learnedPriority(it.id) + when {
                host.contains("yetianlian") -> 30
                host.contains("rulianshi") -> 20
                !(it.rules["searchUrl"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("@js:")) -> 10
                else -> 0
            }
        }
        val hostGroups = ranked.groupByTo(linkedMapOf()) {
            it.url.toHttpUrlOrNull()?.host.orEmpty().lowercase().removePrefix("www.").ifBlank { it.id }
        }.values.map { ArrayDeque(it) }
        val rules = buildList {
            while (hostGroups.any { it.isNotEmpty() }) hostGroups.forEach { if (it.isNotEmpty()) add(it.removeFirst()) }
        }
        // Only a few preferred metadata providers get an author query, and only
        // for a catalog carrying enough synopsis evidence to verify a retitling.
        // This never doubles the requests across the entire imported library.
        val authorFallback = if (book.canonicalTitle.isBlank() && synopsisText(book.description).length >= 72)
            rules.distinctBy { it.url.toHttpUrlOrNull()?.host.orEmpty().lowercase().removePrefix("www.").ifBlank { it.id } }
                .take(3).mapTo(hashSetOf()) { it.id } else emptySet()
        val selected = withTimeoutOrNull(14_000) { coroutineScope {
            val next = java.util.concurrent.atomic.AtomicInteger()
            val found = kotlinx.coroutines.channels.Channel<NovelBook>(1)
            val workers = List(10.coerceAtMost(rules.size)) { launch {
                while (isActive) {
                    val rule = rules.getOrNull(next.getAndIncrement()) ?: break
                    try {
                        // Real canonical-title searches on these metadata sources
                        // take 3-5 seconds; retain the overall 14-second budget.
                        val host = rule.url.toHttpUrlOrNull()?.host.orEmpty()
                        val metadataBudget = if (host.contains("yetianlian") || host.contains("rulianshi")) 6_000L else 3_000L
                        val hits = catalogRequest(rule.id, "title_search", ReaderActiveBudget(metadataBudget), verification = false) {
                            importedAdapter().search(ReaderBookIdentity.searchTitle(book), rule.id)
                        }.orEmpty()
                        // Search latency and actual readability have separate budgets;
                        // a quick metadata row cannot win and cancel healthy providers.
                        val hit = verifiedCatalogHit(book, hits, ReaderActiveBudget(6_000))
                        val proven = hit ?: if (rule.id in authorFallback) {
                            val budget = ReaderActiveBudget(6_000)
                            val hits = catalogRequest(rule.id, "author_search", budget) {
                                importedAdapter().search(book.author.trim().removePrefix("作者：").removePrefix("作者:"), rule.id)
                            }.orEmpty()
                            verifiedRetitledCatalogHit(book, hits, budget)
                        } else null
                        proven?.let { found.trySend(it); return@launch }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Only proven hits can become a reading route. */ }
                }
            } }
            val completion = launch { workers.joinAll(); found.close() }
            try { found.receiveCatching().getOrNull() }
            finally { workers.forEach { it.cancel() }; completion.cancel(); found.cancel() }
        } } ?: throw IOException("暂未找到这本书的可读来源，请重试查找；书城信息已保留。")
        return selected
    }

    private fun synopsisText(value: String): String = ReaderBookIdentity.normalize(Jsoup.parse(value).text())
        .filter { it.isLetterOrDigit() }

    /** Author equality alone is never a retitling proof. Verify actual detail and body. */
    private suspend fun verifiedRetitledCatalogHit(expected: NovelBook, hits: List<NovelBook>, budget: ReaderActiveBudget): NovelBook? {
        val expectedSynopsis = synopsisText(expected.description)
        if (expectedSynopsis.length < 72) return null
        val candidates = hits.asSequence().filter {
            ReaderSearchPolicy.kind(it) == ReaderSearchPolicy.kind(expected) && it.author.isNotBlank() &&
                ReaderBookIdentity.normalizeAuthor(it.author) == ReaderBookIdentity.normalizeAuthor(expected.author)
        }.distinctBy { it.detailUrl }.sortedByDescending { candidate ->
            // Hints only prioritize independent detail checks. They never prove
            // identity or bypass the full synopsis/catalog/body validation below.
            val synopsis = synopsisText(candidate.description)
            val matchingIntro = synopsis.length >= 72 &&
                (expectedSynopsis.contains(synopsis.take(320)) || synopsis.contains(expectedSynopsis.take(320)))
            val expectedLatest = synopsisText(expected.latestChapter)
            val candidateLatest = synopsisText(candidate.latestChapter)
            val matchingLatest = minOf(expectedLatest.length, candidateLatest.length) >= 8 &&
                (expectedLatest.contains(candidateLatest) || candidateLatest.contains(expectedLatest))
            (if (matchingIntro) 100 else 0) + (if (matchingLatest) 50 else 0)
        }.take(12).toList()
        return coroutineScope {
            val next = java.util.concurrent.atomic.AtomicInteger()
            val found = kotlinx.coroutines.channels.Channel<NovelBook>(1)
            val workers = List(2.coerceAtMost(candidates.size)) { launch {
              while (isActive) {
                val candidate = candidates.getOrNull(next.getAndIncrement()) ?: break
                try {
                val detail = catalogRequest(candidate.sourceId, "retitled_detail", budget, maximumMs = 3_000) {
                    importedAdapter().detail(candidate.copy(description = "", canonicalTitle = ""), requireIdentityEvidence = true)
                } ?: continue
                if (ReaderSearchPolicy.kind(detail) != ReaderSearchPolicy.kind(expected) ||
                    ReaderBookIdentity.normalizeAuthor(detail.author) != ReaderBookIdentity.normalizeAuthor(expected.author)) continue
                val actualSynopsis = synopsisText(detail.description)
                val shorter = if (expectedSynopsis.length <= actualSynopsis.length) expectedSynopsis else actualSynopsis
                val longer = if (expectedSynopsis.length <= actualSynopsis.length) actualSynopsis else expectedSynopsis
                if (shorter.length < 72 || !longer.contains(shorter.take(320))) continue
                val chapters = catalogRequest(candidate.sourceId, "retitled_catalog", budget) { getCatalog(detail.detailUrl, detail.sourceId) } ?: continue
                val first = chapters.firstOrNull() ?: continue
                val content = catalogRequest(candidate.sourceId, "retitled_body", budget) { getChapterContent(first.url, detail.sourceId) } ?: continue
                ReaderSourceContentValidator.requireReadable(content, first.title)
                found.trySend(detail.copy(canonicalTitle = ReaderBookIdentity.searchTitle(detail.title), chapterCount = chapters.size))
                break
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Another same-author candidate still needs its own complete proof. */ }
              }
            } }
            val completion = launch { workers.joinAll(); found.close() }
            try { found.receiveCatching().getOrNull() }
            finally { workers.forEach { it.cancel() }; completion.cancel(); found.cancel() }
        }
    }
    /** Even an exact search row must independently return the right detail and readable body. */
    private suspend fun verifiedCatalogHit(book: NovelBook, hits: List<NovelBook>, budget: ReaderActiveBudget): NovelBook? {
        for (candidate in hits.asSequence().filter {
            ReaderBookIdentity.matches(book, it) || it.author.isBlank() && ReaderBookIdentity.sameTitle(book, it)
        }.take(3)) {
            try {
                val detail = catalogRequest(candidate.sourceId, "exact_detail", budget) {
                    importedAdapter().detail(candidate.copy(canonicalTitle = ""), requireIdentityEvidence = true)
                } ?: continue
                if (!ReaderBookIdentity.matches(book, detail.copy(canonicalTitle = ""))) continue
                val chapters = catalogRequest(candidate.sourceId, "exact_catalog", budget) { getCatalog(detail.detailUrl, detail.sourceId) } ?: continue
                val first = chapters.firstOrNull() ?: continue
                val content = catalogRequest(candidate.sourceId, "exact_body", budget) { getChapterContent(first.url, detail.sourceId) } ?: continue
                ReaderSourceContentValidator.requireReadable(content, first.title)
                return detail.copy(chapterCount = chapters.size)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Another exact-title detail may provide the missing author. */ }
        }
        return null
    }

    val sourceDiagnostics: Map<String, String> get() = diagnostics.toMap()
    private val comics by lazy { ComicSourceEngine(client) }
    private val imported by lazy { sourceLibrary?.let { LegadoSourceAdapter(client, it, phaseTrace::record) } }
    private val aggregate by lazy { ReaderAggregateSearch(searchSource = { query, id ->
        val result = when {
            id.startsWith("legado_") -> importedAdapter().search(query, id)
            AVAILABLE_COMIC_SOURCES.any { it.id == id } -> comics.searchComics(query, id)
            else -> searchSource(query, source(id))
        }
        diagnostics[id] = "返回 ${result.size} 项"
        result
    }) }
    private fun importedAdapter() = imported ?: throw IOException("内置书源库尚未载入。")
    suspend fun headersForSource(sourceId: String, referer: String = ""): Map<String, String> =
        if (sourceId.startsWith("legado_")) importedAdapter().headersForSource(sourceId, referer)
        else if (referer.isNotBlank()) mapOf("Referer" to referer) else emptyMap()
    private val json = Json { ignoreUnknownKeys = true }
    private data class CatalogCache(val at: Long, val chapters: List<NovelChapter>)
    private val catalogCache = ConcurrentHashMap<String, CatalogCache>()
    private data class CachedText(val time: Long, val text: String)
    private val chapterCache = object : LinkedHashMap<String, CachedText>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedText>) = size > 16
    }
    // Fixed striped locks coalesce the same foreground/prefetch request without
    // detaching its network lifetime from the callers or growing a lock map.
    private val chapterGates = List(32) { Mutex() }
    private val catalogGates = List(16) { Mutex() }
    private fun requestGate(gates: List<Mutex>, key: String) = gates[(key.hashCode() and Int.MAX_VALUE) % gates.size]

    // Compatibility entry points for saved books; retired source is never warmed.
    suspend fun warmBook(book: NovelBook) = Unit
    fun initialChapterUrl(book: NovelBook): String? = null

    fun invalidateCatalog(bookUrl: String, sourceId: String) {
        synchronized(chapterCache) { chapterCache.keys.removeAll { it.startsWith(sourceId + "\u0000") } }
        if (sourceId.startsWith("legado_")) { imported?.invalidateCatalog(bookUrl, sourceId); return }
        if (ComicSourceEngine.SOURCES.any { it.id == sourceId }) { comics.invalidateCatalog(bookUrl, sourceId); return }
        val rule = RULES.firstOrNull { it.id == sourceId } ?: return
        val book = NovelHtmlParser.bookUrl(bookUrl, rule) ?: return
        catalogCache.remove(sourceId + ":" + book)
    }

    suspend fun search(keyword: String, sourceId: String = SOURCE_MERGE): List<NovelBook> = withContext(Dispatchers.IO) {
        val query = keyword.trim()
        if (query.isEmpty()) return@withContext emptyList()
        if (sourceId.startsWith("legado_")) return@withContext importedAdapter().search(query, sourceId)
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val rule = RULES.firstOrNull { it.owns(query) && NovelHtmlParser.bookUrl(query, it) != null }
                ?: throw IOException("此链接不是已支持来源的作品页。")
            if (sourceId != SOURCE_MERGE && sourceId != rule.id) throw IOException("请切换到" + rule.name + "或多源搜索。")
            val url = NovelHtmlParser.bookUrl(query, rule)!!
            return@withContext listOf(getBookDetail(NovelBook(NovelHtmlParser.stableId(rule.id, url), "",
                detailUrl = url, sourceId = rule.id)))
        }
        if (sourceId != SOURCE_MERGE) return@withContext searchSource(query, source(sourceId))
        searchAggregate(query, 0) {}
    }

    /** Searches real adapters only. One query has a finite network and concurrency budget. */
    internal suspend fun searchAggregate(query: String, kind: Int,
        continuous: Boolean = false, previous: ReaderSearchProgress? = null, retryDeferred: Boolean = false,
        background: Boolean = false,
        onProgress: suspend (ReaderSearchProgress) -> Unit): List<NovelBook> = withContext(Dispatchers.IO) {
        require(kind in 0..2)
        if (query.startsWith("https://") || query.startsWith("http://")) {
            require(kind == 0) { "作品链接请在对应来源中打开。" }
            return@withContext search(query, SOURCE_MERGE)
        }
        val sources = eligibleSearchSources(kind)
        val publish: suspend (ReaderSearchProgress) -> Unit = { progress ->
            progress.errors.forEach { (id, message) -> diagnostics[id] = message }
            onProgress(progress)
        }
        if (continuous) aggregate.searchAll(query.trim(), kind, sources, previous, retryDeferred,
            maxWorkers = if (background) 2 else 12, onProgress = publish)
        else aggregate.search(query.trim(), kind, sources, maxWorkers = if (background) 2 else 12, onProgress = publish)
    }

    private suspend fun eligibleSearchSources(kind: Int): List<ReaderSearchSource> {
        val builtins = when (kind) {
            0 -> RULES.map { ReaderSearchSource(it.id, aggregate.learnedPriority(it.id),
                it.baseUrl.toHttpUrlOrNull()?.host.orEmpty().removePrefix("www.")) }
            2 -> AVAILABLE_COMIC_SOURCES.filter { it.enable }.map { ReaderSearchSource(it.id, 20,
                it.baseUrl.toHttpUrlOrNull()?.host.orEmpty().removePrefix("www.")) }
            else -> emptyList()
        }
        val nativeHosts = when (kind) {
            0 -> RULES.mapNotNull { it.baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") }.toSet()
            2 -> AVAILABLE_COMIC_SOURCES.mapNotNull { it.baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") }.toSet()
            else -> emptySet()
        }
        val rules = sourceLibrary?.searchable(kind).orEmpty()
            .filterNot { it.url.toHttpUrlOrNull()?.host?.removePrefix("www.") in nativeHosts }
            .distinctBy { it.url.trimEnd('/').lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.") }
            .map { rule ->
                val searchRule = (rule.rules["searchUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val simple = !searchRule.contains("@js:", true) && !searchRule.contains("<js>", true)
                val host = rule.url.toHttpUrlOrNull()?.host.orEmpty().removePrefix("www.")
                val known = if (host.contains("yetianlian") || host.contains("rulianshi")) 30 else 0
                ReaderSearchSource(rule.id, aggregate.learnedPriority(rule.id) + known + if (simple) 10 else 0, host)
            }
        return builtins + rules
    }

    private suspend fun searchSource(query: String, rule: NovelHtmlSource): List<NovelBook> {
        val url = rule.baseUrl.toHttpUrlOrNull()!!.resolve(rule.searchPath)!!.newBuilder()
        if (rule.id == SOURCE_BQGNOVELS) url.addQueryParameter("size", "50")
        if (!rule.postSearch) url.addQueryParameter(rule.searchParameter, query)
        val request = Request.Builder().url(url.build()).header("User-Agent", UA).header("Referer", rule.baseUrl + "/")
        if (rule.postSearch) request.post(FormBody.Builder().add(rule.searchParameter, query).build())
        val page = fetch(request.build(), rule, quick = true)
        if (page.text.trimStart().startsWith("{") || page.text.trimStart().startsWith("[")) return searchJson(page.text, rule)
        val doc = Jsoup.parse(page.text, page.url)
        NovelHtmlParser.checkPage(doc, rule)
        return NovelHtmlParser.books(doc, rule).filter { ReaderSearchPolicy.matchRank(query, it.title, it.author) != null }
    }
    private fun searchJson(text: String, rule: NovelHtmlSource): List<NovelBook> {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { throw IOException("搜索数据无法解析。", e) }
        val found = linkedMapOf<String, NovelBook>()
        fun value(obj: JsonObject, vararg names: String) = names.firstNotNullOfOrNull {
            (obj[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }.orEmpty()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach(::visit)
                is JsonObject -> {
                    val title = value(node, "title", "name", "bookName", "book_name", "novelName")
                    val id = value(node, "id", "bookId", "book_id", "novelId", "novel_id", "_id")
                    val link = value(node, "url", "bookUrl", "detailUrl")
                    val url = NovelHtmlParser.bookUrl(if (link.isNotBlank()) NovelHtmlParser.absolute(rule.baseUrl + "/", link)
                        else if (id.toLongOrNull() != null) rule.baseUrl + "/book/" + id else "", rule)
                    if (title.isNotBlank() && url != null) {
                        val cover = value(node, "imgUrl", "cover", "coverUrl", "cover_url", "image", "img", "pic")
                        found[url] = NovelBook(NovelHtmlParser.stableId(rule.id, url), title,
                            author = value(node, "author", "authorName", "author_name"),
                            coverUrl = if (cover.isBlank()) "" else NovelHtmlParser.absolute(rule.baseUrl + "/", cover),
                            detailUrl = url, sourceId = rule.id, coverReferer = rule.baseUrl + "/",
                            description = value(node, "intro", "description", "desc", "des"),
                            latestChapter = value(node, "update_content", "latestChapter"),
                            updateTime = value(node, "update_time", "updateTime"),
                            category = value(node, "category", "categoryName", "type"), status = "")
                    } else node.values.forEach(::visit)
                }
                else -> Unit
            }
        }
        visit(root)
        if (found.isEmpty() && (root as? JsonObject)?.get("error") != null) throw IOException("搜索源返回错误。")
        return found.values.toList()
    }

    suspend fun getDiscoverBooks(sourceId: String = SOURCE_MERGE): List<NovelBook> = withContext(Dispatchers.IO) {
        if (sourceId.startsWith("legado_")) return@withContext importedAdapter().discover(sourceId)
        if (sourceId == SOURCE_MERGE) {
            // Use the real homepage of an existing imported source, including
            // its returned images, instead of the removed Skin metadata index.
            sourceLibrary?.searchable(0)?.firstOrNull { it.url == "http://www.yetianlian.info" }?.let { rule ->
                try {
                    withTimeoutOrNull(4_000) { importedAdapter().discover(rule.id) }?.takeIf { it.isNotEmpty() }
                        ?.let { return@withContext it }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The existing remaining sources may still serve the homepage. */ }
            }
        }
        val rules = if (sourceId == SOURCE_MERGE) listOf(source(SOURCE_PIQUGE), source(SOURCE_BQGNOVELS))
                    else listOf(source(sourceId))
        var failure: Exception? = null
        for (rule in rules) {
            currentCoroutineContext().ensureActive()
            try {
                val doc = document(rule.baseUrl.toHttpUrlOrNull()!!.resolve(rule.discoverPath)!!.toString(), rule)
                val books = NovelHtmlParser.books(doc, rule)
                if (books.isNotEmpty()) return@withContext books
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failure = e; diagnostics[rule.id] = e.message.orEmpty() }
        }
        if (failure != null) throw IOException("书城暂时不可用：" + failure.message, failure)
        emptyList()
    }
    suspend fun getBookDetail(book: NovelBook): NovelBook = withContext(Dispatchers.IO) {
        val detailed = if (book.sourceId.startsWith("legado_")) importedAdapter().detail(book) else {
            val rule = source(book.sourceId)
            val url = NovelHtmlParser.bookUrl(book.detailUrl, rule) ?: throw IOException("作品链接与所选书源不一致。")
            NovelHtmlParser.detail(document(url, rule), rule, book)
        }
        if (book.title.isNotBlank() && book.author.isNotBlank() && !ReaderBookIdentity.matches(book, detailed.copy(canonicalTitle = ""))) {
            readingRoutes.failed(book, identityMismatch = true)
            throw IOException("来源详情的书名或作者与已保存作品不一致。")
        }
        detailed
    }

    /** Only inspect a few already matched hits for artwork; never download chapter bodies. */
    internal suspend fun resolveSourceCover(book: NovelBook, excluded: Set<String> = emptySet()): ResolvedCover? {
        if (book.isComic || book.author.isBlank()) return null
        val candidates = (listOf(book.asSource()) + book.sources).filterNot { it.sourceId == SOURCE_SKIN }.distinctBy { it.sourceId }.take(8)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (!NovelCoverResolver.needsCover(candidate.asBook()) && candidate.coverUrl !in excluded)
                return ResolvedCover(candidate.coverUrl, candidate.coverReferer.ifBlank { candidate.detailUrl },
                    candidate.detailUrl, candidate.coverRequestHeaders ?: candidate.requestHeaders)
            try {
                val detail = withTimeoutOrNull(3_000) { getBookDetail(candidate.asBook()) } ?: continue
                if (!ReaderBookIdentity.matches(book, detail) || NovelCoverResolver.needsCover(detail) || detail.coverUrl in excluded) continue
                return ResolvedCover(detail.coverUrl, detail.coverReferer.ifBlank { detail.detailUrl },
                    detail.detailUrl, detail.artworkHeaders)
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) { /* A different confirmed hit may contain usable metadata. */ }
        }
        // Local bookstore rows can have no artwork and no search candidates yet.
        // Search a small bounded metadata subset, matching title AND author, rather
        // than making the user open/read every source to obtain a cover.
        val providers = sourceLibrary?.searchable(ReaderSearchPolicy.kind(book)).orEmpty()
            .filter { (it.rules["ruleSearch"] as? JsonObject)?.get("coverUrl")?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
            .sortedByDescending { rule ->
                val host = rule.url.toHttpUrlOrNull()?.host.orEmpty()
                if (Regex("(?:^|\\.)(?:rulianshi|x?yetianlian)\\.").containsMatchIn(host)) 2 else 0
            }.distinctBy { it.url.substringBefore('#').trimEnd('/') }.take(8)
        val result = withTimeoutOrNull(7_000) { coroutineScope {
            val next = java.util.concurrent.atomic.AtomicInteger()
            val found = kotlinx.coroutines.channels.Channel<ResolvedCover>(1)
            val workers = List(3.coerceAtMost(providers.size)) { launch {
                while (isActive) {
                    val rule = providers.getOrNull(next.getAndIncrement()) ?: break
                    try {
                        val matches = withTimeoutOrNull(2_500) { importedAdapter().search(ReaderBookIdentity.searchTitle(book), rule.id) }.orEmpty()
                        val hit = matches.firstOrNull { ReaderBookIdentity.matches(book, it) && !NovelCoverResolver.needsCover(it) && it.coverUrl !in excluded }
                        if (hit != null) { found.trySend(ResolvedCover(hit.coverUrl, hit.coverReferer.ifBlank { hit.detailUrl }, hit.detailUrl, hit.artworkHeaders)); break }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Another metadata provider may still match. */ }
                }
            } }
            val completion = launch { workers.joinAll(); found.close() }
            try { found.receiveCatching().getOrNull() }
            finally { workers.forEach { it.cancel() }; completion.cancel(); found.cancel() }
        } }
        if (result != null) return result
        return null
    }

    internal data class BookCatalog(val book: NovelBook, val chapters: List<NovelChapter>)
    internal data class PreparedReaderSource(val book: NovelBook, val chapters: List<NovelChapter>,
        val chapterIndex: Int, val content: String, val comicPages: List<String>)
    private class MatchedAlternativeFound : RuntimeException()

    internal suspend fun restoreBookSources(book: NovelBook, force: Boolean = false): ReaderSourceDiscoveryState = withContext(Dispatchers.IO) {
        sourceDiscovery.restore(book, eligibleSearchSources(ReaderSearchPolicy.kind(book)).mapTo(hashSetOf()) { it.id }, force)
    }
    internal suspend fun recordSourceAvailability(book: NovelBook, value: ReaderSourceAvailability) = withContext(Dispatchers.IO) {
        val eligibleIds = eligibleSearchSources(ReaderSearchPolicy.kind(book)).mapTo(hashSetOf()) { it.id }
        sourceDiscovery.recordAvailability(book, eligibleIds, value)
        if (value.status == ReaderSourceAvailability.FAILED) synchronized(preparedSwitches) {
            val prefix = ReaderBookIdentity.key(book) + "\u0000" + value.sourceId + "\u0000"
            preparedSwitches.keys.removeAll { it.startsWith(prefix) }
        }
    }

    private val preparedSwitches = object : LinkedHashMap<String, Pair<Long, PreparedReaderSource>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, PreparedReaderSource>>) = size > 8
    }
    private fun preparedKey(book: NovelBook, sourceId: String, title: String, oldIndex: Int) =
        ReaderBookIdentity.key(book) + "\u0000" + sourceId + "\u0000" +
            (if (book.sourceId == sourceId) book.detailUrl else book.sources.firstOrNull { it.sourceId == sourceId }?.detailUrl.orEmpty()) +
            "\u0000" + ReaderBookIdentity.normalize(title) + "\u0000" + oldIndex

    /** Automatically resumes pending metadata within 45 seconds; at most four novel routes are sampled. */
    internal suspend fun discoverBookSources(book: NovelBook, previous: ReaderSearchProgress? = null,
        retryDeferred: Boolean = false, chapterTitle: String = "", chapterIndex: Int = 0,
        onState: suspend (ReaderSourceDiscoveryState) -> Unit = {},
        onProgress: suspend (NovelBook, ReaderSearchProgress) -> Unit) = withContext(Dispatchers.IO) {
        if (book.title.isBlank() || book.author.isBlank()) return@withContext
        requestGate(sourceDiscoveryGates, ReaderBookIdentity.key(book)).withLock {
        val kind = ReaderSearchPolicy.kind(book)
        val eligible = eligibleSearchSources(kind)
        val eligibleIds = eligible.mapTo(hashSetOf()) { it.id }
        var state = sourceDiscovery.restore(book, eligibleIds, retryDeferred)
        if (retryDeferred) {
            aggregate.invalidate(ReaderBookIdentity.searchTitle(book), kind)
            synchronized(preparedSwitches) {
                val prefix = ReaderBookIdentity.key(book) + "\u0000"
                preparedSwitches.keys.removeAll { it.startsWith(prefix) }
            }
            state.book.sources.forEach { candidate ->
                // Invalidate this book's detail/catalog and the exact sampled chapter only.
                if (candidate.sourceId.startsWith("legado_")) importedAdapter().invalidateCatalog(candidate.detailUrl, candidate.sourceId)
                else catalogCache.remove(candidate.sourceId + ":" + candidate.detailUrl)
                state.availability[ReaderSourceAvailability.key(candidate.sourceId, candidate.detailUrl)]?.chapterUrl
                    ?.takeIf(String::isNotBlank)?.let { url -> synchronized(chapterCache) { chapterCache.remove(candidate.sourceId + "\u0000" + url) } }
            }
        }
        val statusLock = Mutex()
        suspend fun publish() {
            sourceDiscovery.save(state.book, state.progress, eligibleIds, state.availability, flush = false)
            state = state.copy(availability = sourceDiscovery.restore(state.book, eligibleIds).availability)
            onState(state); onProgress(state.book, state.progress)
        }
        if (retryDeferred) sourceDiscovery.save(state.book, state.progress, eligibleIds, state.availability, reset = true)
        publish()
        try {
            val validationAttempted = mutableSetOf<String>()
            val freshRoutes = mutableSetOf<String>()
            withTimeoutOrNull(45_000) { coroutineScope {
            val validations = kotlinx.coroutines.channels.Channel<Pair<NovelBook, NovelBookSource>>(4)
            // A fast metadata hit can prove its chapter immediately. It must not
            // wait for an entire 16-second metadata round of unrelated slow sites.
            fun scheduleValidation() {
                if (kind != 0) return
                val candidates = state.book.sources.filter { candidate ->
                    if (candidate.sourceId == book.sourceId) return@filter false
                    val routeKey = ReaderSourceAvailability.key(candidate.sourceId, candidate.detailUrl)
                    if (routeKey in validationAttempted || retryDeferred && routeKey !in freshRoutes) return@filter false
                    val status = state.availability[ReaderSourceAvailability.key(candidate.sourceId, candidate.detailUrl)]
                    status == null || status.status == ReaderSourceAvailability.MATCHED ||
                        status.status == ReaderSourceAvailability.FAILED && System.currentTimeMillis() >= status.retryAfter ||
                        status.status == ReaderSourceAvailability.USABLE &&
                        ReaderSearchPolicy.matchChapter(listOf(NovelChapter(0, status.chapterTitle, "")), chapterTitle) == null
                }.take((4 - validationAttempted.size).coerceAtLeast(0))
                candidates.forEach { candidate ->
                    if (validations.trySend(state.book to candidate).isSuccess)
                        validationAttempted += ReaderSourceAvailability.key(candidate.sourceId, candidate.detailUrl)
                }
            }
            val validators = if (kind != 0) emptyList() else List(2) { launch {
                    for ((snapshot, candidate) in validations) {
                        val result = try {
                            val prepared = withTimeoutOrNull(5_000) { prepareSourceSwitch(snapshot, candidate.sourceId, chapterTitle, chapterIndex,
                                bypassValidatedCache = retryDeferred) }
                                ?: throw IOException("当前章节验证超时")
                            statusLock.withLock {
                                if (state.book.sources.any { it.sourceId == candidate.sourceId && it.detailUrl == candidate.detailUrl })
                                    synchronized(preparedSwitches) { preparedSwitches[preparedKey(snapshot, candidate.sourceId, chapterTitle, chapterIndex)] = System.currentTimeMillis() to prepared }
                            }
                            ReaderSourceAvailability(candidate.sourceId, candidate.detailUrl, ReaderSourceAvailability.USABLE,
                                System.currentTimeMillis(), prepared.chapters[prepared.chapterIndex].title,
                                prepared.chapters[prepared.chapterIndex].url, prepared.chapterIndex)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            ReaderSourceAvailability(candidate.sourceId, candidate.detailUrl, ReaderSourceAvailability.FAILED,
                                System.currentTimeMillis(), chapterTitle = chapterTitle, chapterIndex = chapterIndex,
                                reason = error.message.orEmpty().take(160), retryAfter = System.currentTimeMillis() + 600_000)
                        }
                        statusLock.withLock {
                            // A newer metadata callback may have replaced this
                            // route while detail/catalog/body validation ran.
                            if (state.book.sources.none { it.sourceId == candidate.sourceId && it.detailUrl == candidate.detailUrl }) return@withLock
                            state = state.copy(availability = state.availability + (result.routeKey to result))
                            publish()
                        }
                    }
            } }
            scheduleValidation()
            try {
            do {
            val completedBefore = state.progress.searchedSourceIds.size
            aggregate.searchAll(ReaderBookIdentity.searchTitle(book), kind, eligible,
                if (retryDeferred) state.progress.copy(books = emptyList()) else state.progress,
                retryDeferred, maxWorkers = 2, maxRounds = 1, preferLatestRoutes = true) { progress ->
                statusLock.withLock {
                    val hits = progress.books.filter { ReaderBookIdentity.matches(book, it) }
                    freshRoutes += hits.flatMap { listOf(it.asSource()) + it.sources }
                        .map { ReaderSourceAvailability.key(it.sourceId, it.detailUrl) }
                    val combined = ReaderBookIdentity.refreshSourceCandidates(state.book, hits)
                    state = state.copy(book = combined, progress = progress.copy(books = listOf(combined)))
                    publish()
                    scheduleValidation()
                }
            }
            if (state.progress.searchedSourceIds.size == completedBefore ||
                state.progress.completedSources >= state.progress.eligibleSources) break
            // Foreground chapter loading retains the first turn between bounded rounds.
            delay(200)
            } while (currentCoroutineContext().isActive)
            } finally { validations.close() }
            validators.joinAll()
            } }
            state = state.copy(progress = state.progress.copy(finished = true, activeSources = 0))
            publish()
        } finally {
            withContext(NonCancellable) { statusLock.withLock {
                sourceDiscovery.save(state.book, state.progress, eligibleIds, state.availability)
            } }
        }
        }
    }

    /** An explicit switch is committed only after this source returns readable content. */
    internal suspend fun prepareSourceSwitch(previous: NovelBook, sourceId: String,
        chapterTitle: String, oldIndex: Int, bypassValidatedCache: Boolean = false): PreparedReaderSource {
        if (sourceId.startsWith("legado_")) {
            val currentRule = sourceLibrary?.find(sourceId)
            if (currentRule == null || !currentRule.enabled || currentRule.type != ReaderSearchPolicy.kind(previous))
                throw IOException("此来源已停用、规则已变化或类型不符，请重新查找。")
        }
        (if (bypassValidatedCache || chapterTitle.isBlank() && oldIndex != 0) null else
            synchronized(preparedSwitches) { preparedSwitches[preparedKey(previous, sourceId, chapterTitle, oldIndex)] })
            ?.takeIf { System.currentTimeMillis() - it.first in 0 until 120_000 && ReaderBookIdentity.matches(previous, it.second.book) }
            ?.let { return it.second }
        val candidate = (listOf(previous.asSource()) + previous.sources)
            .firstOrNull { it.sourceId == sourceId && ReaderBookIdentity.matches(previous, it.asBook()) }
            ?: throw IOException("此来源尚未确认收录这本作品，请等待自动查源完成。")
        val hit = candidate.asBook()
        val target = when {
            hit.isComic && !hit.sourceId.startsWith("legado_") -> hit
            !hit.isComic && !hit.isAudio && hit.sourceId.startsWith("legado_") -> importedAdapter().detail(hit, requireIdentityEvidence = true)
            else -> getBookDetail(hit)
        }
        if (!ReaderBookIdentity.matches(previous, target.copy(canonicalTitle = ""))) throw IOException("新来源详情的书名或作者与当前作品不一致。")
        val chapters = if (target.isComic) getComicCatalog(target.detailUrl, target.sourceId)
            else getCatalog(target.detailUrl, target.sourceId)
        if (chapters.isEmpty()) throw IOException("新来源没有返回目录。")
        val aligned = ReaderSearchPolicy.matchChapter(chapters, chapterTitle)
            ?: if (oldIndex == 0 && chapterTitle.isBlank()) 0 else
                throw IOException("新来源没有找到当前章节，已保留原阅读位置。")
        val chapter = chapters[aligned]
        if (bypassValidatedCache) synchronized(chapterCache) { chapterCache.remove(target.sourceId + "\u0000" + chapter.url) }
        val pages = if (target.isComic) getComicChapterPages(chapter.url, target.sourceId) else emptyList()
        val text = if (target.isComic) "" else getChapterContent(chapter.url, target.sourceId)
        if (!target.isComic && !target.isAudio) ReaderSourceContentValidator.requireReadable(text, chapter.title)
        if (target.isComic && pages.isEmpty() || !target.isComic && text.isBlank())
            throw IOException("新来源未返回当前章节正文，已保留原来源。")
        return PreparedReaderSource(target.copy(sources =
            ReaderBookIdentity.merge(previous.title, listOf(previous, target)).first().sources), chapters, aligned, text, pages)
    }

    /** A result can be opened before later search hits arrive. Discover on failure only. */
    internal suspend fun discoverAlternatives(book: NovelBook): NovelBook {
        if (book.author.isBlank()) return book
        var found = book
        try {
            withTimeoutOrNull(6_000) {
                searchAggregate(ReaderBookIdentity.searchTitle(book), ReaderSearchPolicy.kind(book)) { progress ->
                    val matches = progress.books.filter { ReaderBookIdentity.matches(book, it) }
                    if (matches.isNotEmpty()) {
                        found = ReaderBookIdentity.merge(book.title, listOf(book) + matches).first()
                        if (found.sourceCount > 1) throw MatchedAlternativeFound()
                    }
                }
            }
        } catch (_: MatchedAlternativeFound) { /* Cancel remaining metadata requests as soon as a match arrives. */ }
        return found
    }
    /** Opens only actual, identity-matched hits. No title-only cross-provider guessing. */
    internal suspend fun alternativeCatalog(book: NovelBook, excluded: Set<String> = setOf(book.sourceId)): BookCatalog {
        val alternatives = book.sources.filter { it.sourceId !in excluded && it.sourceId != SOURCE_SKIN }
            .distinctBy { it.sourceId }.filter { ReaderBookIdentity.matches(book, it.asBook()) }.take(4)
        var failure: Exception? = null
        for (candidate in alternatives) {
            currentCoroutineContext().ensureActive()
            try {
                val result = withTimeoutOrNull(8_000) {
                    val detail = if (book.isComic && !candidate.sourceId.startsWith("legado_")) candidate.asBook()
                        else getBookDetail(candidate.asBook())
                    if (!ReaderBookIdentity.matches(book, detail.copy(canonicalTitle = ""))) throw IOException("来源详情的书名或作者与搜索结果不一致。")
                    val chapters = if (book.isComic) getComicCatalog(detail.detailUrl, detail.sourceId)
                        else getCatalog(detail.detailUrl, detail.sourceId)
                    if (chapters.isEmpty()) throw IOException("来源没有返回目录。")
                    BookCatalog(detail.copy(sources = book.sources,
                        coverUrl = book.coverUrl.ifBlank { detail.coverUrl },
                        coverReferer = if (book.coverUrl.isNotBlank()) book.coverReferer else detail.coverReferer,
                        coverRequestHeaders = if (book.coverUrl.isNotBlank()) book.artworkHeaders else detail.artworkHeaders), chapters)
                }
                if (result != null) return result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e; diagnostics[candidate.sourceId] = e.message.orEmpty().take(200) }
        }
        throw IOException(if (alternatives.isEmpty()) "没有其他已确认同名同作者的来源。" else "本轮其他匹配来源暂不可用。", failure)
    }
    suspend fun getCatalog(bookUrl: String, sourceId: String,
        onProgress: ((List<NovelChapter>) -> Unit)? = null): List<NovelChapter> =
        requestGate(catalogGates, "$sourceId:$bookUrl").withLock { fetchCatalog(bookUrl, sourceId, onProgress) }

    private suspend fun fetchCatalog(bookUrl: String, sourceId: String,
        onProgress: ((List<NovelChapter>) -> Unit)? = null): List<NovelChapter> = withContext(Dispatchers.IO) {
        if (sourceId.startsWith("legado_")) return@withContext importedAdapter().catalog(bookUrl, sourceId, onProgress)
        val rule = source(sourceId)
        val book = NovelHtmlParser.bookUrl(bookUrl, rule) ?: throw IOException("作品链接与所选书源不一致。")
        val cacheKey = sourceId + ":" + book
        catalogCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at in 0..300_000 }?.let {
            onProgress?.invoke(it.chapters)
            return@withContext it.chapters
        }
        val first = if (rule.id == SOURCE_BQGNOVELS) book.toHttpUrlOrNull()!!.newBuilder()
            .setQueryParameter("sort", "0").setQueryParameter("page", "1").build().toString() else book
        val pending = ArrayDeque<String>().apply { add(first) }
        val queued = mutableSetOf(NovelHtmlParser.catalogPageIdentity(first, book, rule))
        val visited = mutableSetOf<String>()
        val chapters = linkedMapOf<String, NovelChapter>()
        var declared = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val batch = mutableListOf<String>()
            while (pending.isNotEmpty() && batch.size < 4) {
                val url = pending.removeFirst()
                if (visited.add(NovelHtmlParser.catalogPageIdentity(url, book, rule))) batch += url
            }
            if (visited.size > 1000) throw IOException("目录页数超过保护上限，未返回截断目录。")
            val pages = coroutineScope { batch.map { url -> async {
                NovelHtmlParser.catalog(document(url, rule), rule, book)
            } }.awaitAll() }
            for (parsed in pages) {
            if (parsed.chapters.isEmpty()) throw IOException("目录分页为空，未返回不完整目录。")
            val count = chapters.size
            parsed.chapters.forEach { chapters.putIfAbsent(NovelHtmlParser.logicalChapter(it.url), it) }
            if (count == chapters.size) throw IOException("目录分页重复，未返回不完整目录。")
            declared = maxOf(declared, parsed.declaredCount)
            parsed.nextPages.filter { page ->
                val identity = NovelHtmlParser.catalogPageIdentity(page, book, rule)
                identity !in visited && queued.add(identity)
            }.forEach(pending::add)
            }
            onProgress?.invoke(chapters.values.mapIndexed { index, chapter -> chapter.copy(index = index) })
        }
        if (declared > chapters.size) throw IOException("来源标注" + declared + "章，仅取得" + chapters.size + "章，目录不完整。")
        chapters.values.mapIndexed { index, chapter -> chapter.copy(index = index) }.also {
            if (catalogCache.size >= 12) catalogCache.entries.minByOrNull { it.value.at }?.let { old -> catalogCache.remove(old.key) }
            catalogCache[cacheKey] = CatalogCache(System.currentTimeMillis(), it)
        }
    }
    suspend fun getChapterContent(chapterUrl: String, sourceId: String): String =
        requestGate(chapterGates, "$sourceId:$chapterUrl").withLock { cachedChapterContent(chapterUrl, sourceId) }

    private suspend fun cachedChapterContent(chapterUrl: String, sourceId: String): String = withContext(Dispatchers.IO) {
        val key = sourceId + "\u0000" + chapterUrl
        synchronized(chapterCache) { chapterCache[key] }?.takeIf { System.currentTimeMillis() - it.time in 0..300_000 }
            ?.let { return@withContext it.text }
        val text = fetchChapterContent(chapterUrl, sourceId)
        if (text.length <= 256_000) synchronized(chapterCache) { chapterCache[key] = CachedText(System.currentTimeMillis(), text) }
        text
    }
    private suspend fun fetchChapterContent(chapterUrl: String, sourceId: String): String = withContext(Dispatchers.IO) {
        if (sourceId.startsWith("legado_")) return@withContext importedAdapter().content(chapterUrl, sourceId)
        val rule = source(sourceId)
        if (!rule.owns(chapterUrl)) throw IOException("章节链接与所选书源不一致。")
        var next: String? = chapterUrl
        val visited = mutableSetOf<String>()
        val parts = mutableListOf<String>()
        while (next != null) {
            currentCoroutineContext().ensureActive()
            val current = next
            if (!visited.add(current) || visited.size > 30) throw IOException("正文分页异常，未返回截断内容。")
            val doc = document(current, rule)
            parts += NovelHtmlParser.chapterText(doc, rule)
            next = NovelHtmlParser.sameChapterNext(doc, chapterUrl, rule)
        }
        parts.joinToString("\n\n")
    }
    fun cleanNovelText(rawText: String) = NovelHtmlParser.cleanText(rawText)
    suspend fun searchComics(keyword: String, sourceId: String = "dmmh"): List<NovelBook> =
        if (sourceId.startsWith("legado_")) importedAdapter().search(keyword, sourceId) else comics.searchComics(keyword, sourceId)
    suspend fun getComicCatalog(comicUrl: String, sourceId: String,
        onProgress: ((List<NovelChapter>) -> Unit)? = null): List<NovelChapter> =
        if (sourceId.startsWith("legado_")) importedAdapter().catalog(comicUrl, sourceId, onProgress)
        else comics.getComicCatalog(comicUrl, sourceId, onProgress)
    suspend fun getComicChapterPages(chapterUrl: String, sourceId: String): List<String> =
        if (sourceId.startsWith("legado_")) importedAdapter().comicPages(chapterUrl, sourceId) else comics.getComicChapterPages(chapterUrl, sourceId)
    suspend fun getDiscoverComics(sourceId: String): List<NovelBook> =
        if (sourceId.startsWith("legado_")) importedAdapter().discover(sourceId) else comics.getDiscoverComics(sourceId)
    private fun source(id: String) = RULES.firstOrNull { it.id == id } ?: throw IOException(
        if (id == SOURCE_SKIN) "Skin 书源已移除，请使用本书的换源按钮选择其他匹配来源。" else "未知书源，请重新选择。")
    private data class Page(val text: String, val url: String)
    private suspend fun document(url: String, source: NovelHtmlSource): Document {
        val page = fetch(Request.Builder().url(url).header("User-Agent", UA).header("Referer", source.baseUrl + "/").build(), source)
        return Jsoup.parse(page.text, page.url).also { NovelHtmlParser.checkPage(it, source) }
    }
    private suspend fun fetch(request: Request, source: NovelHtmlSource, quick: Boolean = false): Page =
        suspendCancellableCoroutine { continuation ->
        val networkClient = if (quick) client.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() else client
        val call = networkClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    IOException(source.name + "：" + (e.message ?: "网络错误"), e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                val page = response.use {
                if (!response.isSuccessful) throw IOException("HTTP " + response.code + "，请重试或换源。")
                if (!source.owns(response.request.url.toString())) throw IOException("来源重定向到其他站点，已停止读取。")
                val body = response.body
                if (body.contentLength() > LIMIT) throw IOException("页面过大，未返回截断内容。")
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (output.size() <= LIMIT) {
                        if (!continuation.isActive) throw IOException("请求已取消")
                        val count = input.read(chunk, 0, minOf(chunk.size, LIMIT + 1 - output.size()))
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > LIMIT) throw IOException("页面过大，未返回截断内容。")
                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
                val charsetName = body.contentType()?.charset()?.name()
                    ?: Regex("(?i)charset\\s*=\\s*['\"]?([\\w-]+)").find(head)?.groupValues?.get(1) ?: "UTF-8"
                val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
                Page(String(bytes, charset), response.request.url.toString())
                }
                if (continuation.isActive) continuation.resume(page)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException(source.name + "：" + (e.message ?: "网络错误"), e))
                }
            }
        })
    }
}
