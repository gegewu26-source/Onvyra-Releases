package com.constx.onvyra.data.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.constx.onvyra.data.ai.tools.PublicWebNetwork
import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class MovieCatalogSource(
    val id: String,
    val name: String,
    val apiUrl: String,
    val suggestUrl: String? = null,
)

internal data class MovieEpisode(val name: String, val url: String, val index: Int? = null)
internal data class MoviePlaybackLine(val name: String, val episodes: List<MovieEpisode>)
internal data class MovieCatalogItem(
    val id: String,
    val title: String,
    val year: Int? = null,
    val detailUrl: String? = null,
    val durationSeconds: Double? = null,
    val lines: List<MoviePlaybackLine> = emptyList(),
    val sourceId: String = "",
    val sourceName: String = "",
    val category: String? = null,
    val remarks: String? = null,
    val isClip: Boolean = false,
)

internal val DEFAULT_MOVIE_CATALOG_SOURCES = listOf(
    MovieCatalogSource("wsyzy", "无损云", "https://api.wsyzy.net/api.php/provide/vod/",
        "https://wsyzy.cc/index.php/ajax/suggest"),
    MovieCatalogSource("haohua", "豪华", "https://hhzyapi.com/api.php/provide/vod"),
) + EXTERNAL_VIDEO_CATALOG_SOURCES

private const val CATALOG_BODY_LIMIT = 1_048_576
private const val CATALOG_CALL_TIMEOUT_MS = 8_000L
private const val CATALOG_SOURCE_TIMEOUT_MS = 16_000L
private const val CATALOG_SEARCH_TIMEOUT_MS = 24_000L
private const val CATALOG_MAX_CANDIDATES = 20
private val catalogIdPattern = Regex("[A-Za-z0-9_-]{1,80}")
private const val CATALOG_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** Reads public catalog metadata only. Media requests remain the player's responsibility. */
internal class MovieCatalogClient(
    httpClient: OkHttpClient,
    sources: List<MovieCatalogSource> = DEFAULT_MOVIE_CATALOG_SOURCES,
    private val extraSources: () -> List<MovieCatalogSource> = { emptyList() },
    context: android.content.Context? = null,
) {
    private val discoveredNetwork = PublicWebNetwork(httpClient)
    private val externalSiteClient = ExternalVideoSiteClient(httpClient, context)
    private val scheduling = MovieCatalogScheduling(maxParallel = 12, maxPerHost = 2)
    private val searchOrderLock = Any()
    private var searchOffset = 0
    private val responsiveSources = LinkedHashSet<String>()
    private val sources = sources.distinctBy { it.id }.also { list ->
        list.forEach {
            require(catalogIdPattern.matches(it.id)) { "Invalid catalog source ID" }
            require(validCatalogUrl(it.apiUrl) != null && (it.suggestUrl == null || validCatalogUrl(it.suggestUrl) != null)) {
                "Invalid public catalog URL"
            }
        }
    }
    private val client = httpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .cache(null)
        .connectTimeout(CATALOG_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CATALOG_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CATALOG_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private fun allSources(): List<MovieCatalogSource> = (sources + extraSources()).distinctBy { it.id }
        .filter { isAllowedCatalogSource(it) && !isExcludedMovieCatalogSource(it) && catalogIdPattern.matches(it.id) && validCatalogUrl(it.apiUrl) != null &&
            (it.suggestUrl == null || validCatalogUrl(it.suggestUrl) != null) }

    internal fun builtInSourceIds(): Set<String> = sources.filter { isAllowedCatalogSource(it) && !isExcludedMovieCatalogSource(it) }.mapTo(linkedSetOf()) { it.id }

    internal fun sourceIdsForSearch(): Set<String> = allSources().mapTo(linkedSetOf()) { it.id }

    suspend fun search(title: String, year: Int? = null, sourceIds: Set<String>? = null): List<MovieCatalogItem> {
        val result = searchWithReport(title, year, sourceIds)
        if (result.report.successfulSources == 0) throw MovieCatalogSearchException(result.report)
        return result.items
    }

    suspend fun searchWithReport(title: String, year: Int? = null, sourceIds: Set<String>? = null): MovieCatalogSearchResult =
        searchReportUpdates(title, year, sourceIds).lastOrNull() ?: error("catalog_search_missing_result")

    /** Keep the existing incremental menu contract while retaining per-query diagnostics. */
    fun searchUpdates(title: String, year: Int? = null, sourceIds: Set<String>? = null): Flow<List<MovieCatalogItem>> = flow {
        var final: MovieCatalogSearchResult? = null
        searchReportUpdates(title, year, sourceIds).collect { result ->
            final = result
            if (result.report.successfulSources > 0) emit(result.items)
        }
        val report = requireNotNull(final).report
        if (report.successfulSources == 0) throw MovieCatalogSearchException(report)
    }

    /** Reports belong to this invocation, never shared between chats or source-menu requests. */
    fun searchReportUpdates(title: String, year: Int? = null, sourceIds: Set<String>? = null): Flow<MovieCatalogSearchResult> = channelFlow {
        val query = title.trim().removeSurrounding("《", "》").trim()
        require(query.isNotEmpty() && query.length <= 200) { "影片名称为空或过长" }
        require(year == null || year in 1..9999) { "年份无效" }
        val eligible = allSources()
        val available = eligible.filter { sourceIds == null || it.id in sourceIds }
        val builtInIds = sources.mapTo(HashSet()) { it.id }
        val selectedSources = synchronized(searchOrderLock) {
            // Include persisted extra catalogs in the very first worker wave.
            // Interleave the two groups: a fast built-in result can otherwise
            // cancel the round before a discovered catalog is ever attempted.
            val builtIns = available.filter { it.id in builtInIds }
            val extraAvailable = available.filter { it.id !in builtInIds }
            val orderedBuiltIns = if (extractJavCode(query) != null) {
                builtIns.filter { it.id in EXTERNAL_VIDEO_SITE_IDS } + builtIns.filter { it.id !in EXTERNAL_VIDEO_SITE_IDS }
            } else {
                builtIns.filter { it.id in responsiveSources } + builtIns.filter { it.id !in responsiveSources }
            }
            val others = extraAvailable.filter { it.id !in responsiveSources }
            val offset = if (others.isEmpty()) 0 else searchOffset % others.size
            searchOffset += 12
            val orderedExtras = extraAvailable.filter { it.id in responsiveSources }.take(2) +
                others.drop(offset) + others.take(offset) +
                extraAvailable.filter { it.id in responsiveSources }.drop(2)
            buildList {
                for (index in 0 until maxOf(orderedBuiltIns.size, orderedExtras.size)) {
                    orderedBuiltIns.getOrNull(index)?.let(::add)
                    orderedExtras.getOrNull(index)?.let(::add)
                }
            }
        }
        val results = LinkedHashMap<Pair<String, String>, MovieCatalogItem>()
        val resultsLock = Any()
        val attempted = LinkedHashSet<String>()
        val succeeded = LinkedHashSet<String>()
        val empty = LinkedHashSet<String>()
        val failures = LinkedHashMap<String, MovieCatalogFailureCode>()
        fun snapshot(): MovieCatalogSearchResult {
            // Access can expire while parallel requests are running. Do not publish stale private metadata.
            val currentIds = selectedSources.filter(::isAllowedCatalogSource).mapTo(linkedSetOf()) { it.id }
            val currentEligibleIds = eligible.filter(::isAllowedCatalogSource).mapTo(linkedSetOf()) { it.id }
            val currentAttempted = attempted intersect currentIds
            val currentSucceeded = succeeded intersect currentIds
            val currentEmpty = empty intersect currentIds
            val currentFailures = failures.filterKeys { it in currentIds }
            return MovieCatalogSearchResult(
                rankMovieCatalogItems(results.values.filter(::isAllowedMovieCatalogItem), query, year),
                MovieCatalogSearchReport(currentIds.size, currentAttempted.size, currentSucceeded.size, currentEmpty.size,
                    currentFailures.map { (id, code) -> MovieCatalogSourceFailure(id, code) },
                    currentFailures.values.count { it == MovieCatalogFailureCode.TIMEOUT },
                    currentIds.size - currentAttempted.size, currentEligibleIds.size,
                    currentIds, currentAttempted, currentSucceeded, currentEmpty, currentEligibleIds),
            )
        }
        withTimeoutOrNull(CATALOG_SEARCH_TIMEOUT_MS) {
            scheduling.runSources(selectedSources) { source ->
                // Queue time does not spend a source's own 16-second execution budget.
                scheduling.withRequest(validCatalogUrl(source.apiUrl)!!.host) {
                    synchronized(resultsLock) { attempted.add(source.id) }
                    try {
                        val items = withTimeoutOrNull(CATALOG_SOURCE_TIMEOUT_MS) { searchSource(source, query) }
                        if (items != null) synchronized(searchOrderLock) { if (items.isNotEmpty()) responsiveSources.add(source.id) }
                        synchronized(resultsLock) {
                            if (items == null) failures[source.id] = MovieCatalogFailureCode.TIMEOUT
                            else {
                                succeeded.add(source.id)
                                if (items.isEmpty()) empty.add(source.id)
                                items.forEach { results[it.sourceId to it.id] = it }
                            }
                            trySend(snapshot())
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        synchronized(resultsLock) {
                            failures[source.id] = catalogFailureCode(error)
                            trySend(snapshot())
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val finalResult = synchronized(resultsLock) {
            // A round deadline cancels only this query. Queued providers were not queried;
            // started requests without an outcome timed out, rather than returning no matches.
            attempted.filterNot { it in succeeded || it in failures }.forEach { failures[it] = MovieCatalogFailureCode.TIMEOUT }
            snapshot()
        }
        send(finalResult)
    }.flowOn(Dispatchers.IO)

    suspend fun getItem(sourceId: String, itemId: String): MovieCatalogItem = withContext(Dispatchers.IO) {
        PrivateMediaAccess.requireAllowed(sourceId = sourceId)
        val source = allSources().firstOrNull { it.id == sourceId } ?: throw IOException("影片目录来源已不可用")
        require(catalogIdPattern.matches(itemId)) { "影片目录编号无效" }
        withTimeoutOrNull(CATALOG_SOURCE_TIMEOUT_MS) {
            if (isExternalVideoSiteSource(source.id)) {
                return@withTimeoutOrNull externalSiteClient.getItem(source.id, itemId)
            }
            val details = requestJson(detailRequestUrl(source, listOf(itemId)), source.id.startsWith("discovered_"))
            parseMovieCatalogItems(details, source).firstOrNull { it.id == itemId }
                ?: throw IOException("该影片已不在目录中")
        }?.also { require(isAllowedMovieCatalogItem(it)) { "影片目录来源已不可用" } }
            ?: throw IOException("影片目录请求超时")
    }

    private suspend fun searchSource(source: MovieCatalogSource, title: String): List<MovieCatalogItem> {
        requireAllowedCatalogSource(source)
        if (isExternalVideoSiteSource(source.id)) {
            return externalSiteClient.search(source.id, title)
        }
        val searchUrl = source.suggestUrl?.let { validCatalogUrl(it)!!.newBuilder()
            .setQueryParameter("mid", "1").setQueryParameter("wd", title).build() }
            ?: validCatalogUrl(source.apiUrl)!!.newBuilder()
                .setQueryParameter("ac", "videolist").setQueryParameter("wd", title).setQueryParameter("pg", "1").build()
        val apiSearchUrl = validCatalogUrl(source.apiUrl)!!.newBuilder()
            .setQueryParameter("ac", "videolist").setQueryParameter("wd", title).setQueryParameter("pg", "1").build()
        val publicOnly = source.id.startsWith("discovered_")
        var usedApiSearch = source.suggestUrl == null
        var root = try { requestJson(searchUrl, publicOnly) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: PrivateMediaAccessDeniedException) { throw denied }
        catch (error: Exception) {
            // The suggestion entrance and JSON API belong to this exact provider.
            // A broken suggest URL must not hide a healthy same-provider catalog.
            if (source.suggestUrl == null) throw error
            usedApiSearch = true
            requestJson(apiSearchUrl, publicOnly)
        }
        if (!usedApiSearch && (root["list"] as? JsonArray).isNullOrEmpty() && searchUrl != apiSearchUrl)
            root = requestJson(apiSearchUrl, publicOnly)
        val list = (root["list"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(CATALOG_MAX_CANDIDATES)
        if (list.isEmpty()) return emptyList()
        val complete = list.filter { it.value("vod_play_url")?.isNotBlank() == true }
        val missingIds = list.filterNot { it in complete }
            .filter { candidate -> (candidate.value("vod_name") ?: candidate.value("name"))?.let { isMatchingMovieTitle(title, it) } != false }
            .take(6).mapNotNull { it.value("vod_id") ?: it.value("id") }
            .filter(catalogIdPattern::matches).distinct()
        val known = parseMovieCatalogItems(JsonObject(mapOf("list" to JsonArray(complete))), source)
        if (missingIds.isEmpty()) return known.filter(::isAllowedMovieCatalogItem)
        val fetched = parseMovieCatalogItems(requestJson(detailRequestUrl(source, missingIds), source.id.startsWith("discovered_")), source)
        requireAllowedCatalogSource(source)
        return (known + fetched).filter(::isAllowedMovieCatalogItem).distinctBy { it.id }
    }

    private suspend fun requestJson(initial: HttpUrl, publicOnly: Boolean = false): JsonObject {
        PrivateMediaAccess.requireAllowed(url = initial.toString())
        if (publicOnly) {
            val page = withTimeoutOrNull(CATALOG_CALL_TIMEOUT_MS) { discoveredNetwork.read(initial.toString()) }
                ?: throw SocketTimeoutException("发现的影片目录请求超时")
            if (page.status !in 200..299) throw MovieCatalogHttpException(page.status)
            PrivateMediaAccess.requireAllowed(url = initial.toString())
            PrivateMediaAccess.requireAllowed(url = page.finalUrl)
            if (page.sourceTruncated || page.bytes.size > CATALOG_BODY_LIMIT)
                throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.INVALID_FORMAT)
            val text = page.bytes.toString(page.contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            return decodeMovieCatalogPayload(text)
        }
        var target = initial
        val visited = HashSet<String>()
        repeat(6) {
            currentCoroutineContext().ensureActive()
            PrivateMediaAccess.requireAllowed(url = target.toString())
            if (!visited.add(target.toString())) throw IOException("目录请求出现循环跳转")
            val response = readResponse(target)
            if (response.redirect != null) {
                target = validCatalogUrl(target.resolve(response.redirect)?.toString().orEmpty())
                    ?: throw IOException("目录跳转地址无效")
            } else {
                PrivateMediaAccess.requireAllowed(url = initial.toString())
                PrivateMediaAccess.requireAllowed(url = target.toString())
                // Some CMS deployments label JSON text/html. Inspect the bounded body, not the MIME label.
                val content = response.content ?: throw IOException("目录未返回内容")
                return decodeMovieCatalogPayload(content)
            }
        }
        throw IOException("目录跳转次数过多")
    }

    private suspend fun readResponse(url: HttpUrl): CatalogResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", CATALOG_USER_AGENT)
            .header("Accept", "application/json,application/xml,text/xml,text/plain,*/*").get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        when {
                            it.code in 300..399 && it.code != 304 -> CatalogResponse(redirect = it.header("Location")
                                ?: throw IOException("目录跳转缺少地址"))
                            !it.isSuccessful -> throw MovieCatalogHttpException(it.code)
                            else -> {
                                val body = it.body ?: throw IOException("目录内容为空")
                                if (body.contentLength() > CATALOG_BODY_LIMIT) throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.INVALID_FORMAT)
                                val bytes = ByteArrayOutputStream()
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(8_192)
                                    while (true) {
                                        if (call.isCanceled()) throw IOException("目录请求已取消")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (bytes.size() + count > CATALOG_BODY_LIMIT) throw MovieCatalogPayloadException(MovieCatalogPayloadFailure.INVALID_FORMAT)
                                        bytes.write(buffer, 0, count)
                                    }
                                }
                                CatalogResponse(content = bytes.toString((body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8).name()))
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

internal fun isAllowedCatalogSource(source: MovieCatalogSource): Boolean =
    PrivateMediaAccess.isAllowed(source.id, source.apiUrl) &&
        (source.suggestUrl == null || PrivateMediaAccess.isAllowed(url = source.suggestUrl))

private fun requireAllowedCatalogSource(source: MovieCatalogSource) {
    PrivateMediaAccess.requireAllowed(source.id, source.apiUrl)
    source.suggestUrl?.let { PrivateMediaAccess.requireAllowed(url = it) }
}

internal fun isAllowedMovieCatalogItem(item: MovieCatalogItem): Boolean =
    PrivateMediaAccess.isAllowed(item.sourceId, item.detailUrl) &&
        item.lines.all { line -> line.episodes.all { PrivateMediaAccess.isAllowed(url = it.url) } }

private class MovieCatalogHttpException(val status: Int) : IOException("影片目录 HTTP $status")

private fun catalogFailureCode(error: Exception): MovieCatalogFailureCode = when (error) {
    is MovieCatalogPayloadException -> if (error.reason == MovieCatalogPayloadFailure.API_ERROR)
        MovieCatalogFailureCode.API_ERROR else MovieCatalogFailureCode.INVALID_RESPONSE
    is MovieCatalogHttpException -> MovieCatalogFailureCode.HTTP
    is InterruptedIOException -> MovieCatalogFailureCode.TIMEOUT
    is IOException -> MovieCatalogFailureCode.NETWORK
    else -> MovieCatalogFailureCode.UNKNOWN
}

private data class CatalogResponse(val content: String? = null, val redirect: String? = null)
private fun validCatalogUrl(value: String): HttpUrl? = value.toHttpUrlOrNull()
    ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }

private fun detailRequestUrl(source: MovieCatalogSource, ids: List<String>): HttpUrl = validCatalogUrl(source.apiUrl)!!
    .newBuilder().setQueryParameter("ac", "detail").setQueryParameter("ids", ids.joinToString(",")).build()

internal fun parseMovieCatalogItems(content: String, source: MovieCatalogSource): List<MovieCatalogItem> =
    parseMovieCatalogItems(decodeMovieCatalogPayload(content), source)

private fun parseMovieCatalogItems(root: JsonObject, source: MovieCatalogSource): List<MovieCatalogItem> =
    (root["list"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val value = entry as? JsonObject ?: return@mapNotNull null
        val id = value.value("vod_id") ?: value.value("id") ?: return@mapNotNull null
        val title = value.value("vod_name")?.trim().orEmpty().ifEmpty { value.value("name")?.trim().orEmpty() }
        if (!catalogIdPattern.matches(id) || title.isEmpty()) return@mapNotNull null
        val lineNames = value.value("vod_play_from").orEmpty().split("$$$")
        val lines = value.value("vod_play_url").orEmpty().split("$$$").mapIndexedNotNull { index, group ->
            val episodes = group.split('#').mapIndexedNotNull { episodeIndex, raw ->
                val separator = raw.indexOf('$')
                val episodeName = if (separator >= 0) raw.substring(0, separator).trim() else ""
                val url = validCatalogUrl(if (separator >= 0) raw.substring(separator + 1).trim() else raw.trim())
                    ?: return@mapIndexedNotNull null
                MovieEpisode(episodeName, url.toString(), episodeIndex + 1)
            }.distinctBy { it.name to it.url }
            if (episodes.isEmpty()) null else MoviePlaybackLine(lineNames.getOrNull(index)?.takeIf(String::isNotBlank)
                ?: "线路${index + 1}", episodes)
        }
        val category = value.value("type_name")?.takeIf(String::isNotBlank)
        val remarks = value.value("vod_remarks")?.takeIf(String::isNotBlank)
        MovieCatalogItem(
            id = id, title = title, year = value.value("vod_year")?.toIntOrNull()?.takeIf { it in 1..9999 },
            detailUrl = detailRequestUrl(source, listOf(id)).toString(),
            durationSeconds = parseCatalogDuration(value.value("vod_duration")), lines = lines,
            sourceId = source.id, sourceName = source.name, category = category, remarks = remarks,
            isClip = catalogClipPattern.containsMatchIn(listOfNotNull(title, category, remarks).joinToString(" ")),
        )
    }.distinctBy { it.id }

private val catalogClipPattern = Regex("预告片|花絮|解说|片段|混剪|影评|短视频|\\b(?:trailer|teaser|clips?)\\b", RegexOption.IGNORE_CASE)
private val catalogSuffixPattern = Regex(
    "[\\s_\\-·]*(?:[（(\\[【]?(?:4k|8k|1080p|720p|2160p|uhd|hdr|蓝光|高清|超清|国语|粤语|国粤双语|普通话)(?:版|修复版)?[）)\\]】]?)+$",
    RegexOption.IGNORE_CASE,
)
private fun rawCatalogTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().removeSurrounding("《", "》").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
private fun baseCatalogTitle(value: String): String {
    var result = rawCatalogTitle(value)
    repeat(4) {
        val next = result.replace(catalogSuffixPattern, "").trim()
        if (next == result || next.isEmpty()) return result
        result = next
    }
    return result
}

internal fun rankMovieCatalogItems(items: List<MovieCatalogItem>, title: String, year: Int? = null): List<MovieCatalogItem> {
    val raw = rawCatalogTitle(title)
    val base = baseCatalogTitle(title)
    val queryCode = extractJavCode(title)
    val normQuery = normalizeCjkForMatch(title)
    val clipRequested = catalogClipPattern.containsMatchIn(title)
    fun score(item: MovieCatalogItem): Int {
        val itemRaw = rawCatalogTitle(item.title)
        val itemBase = baseCatalogTitle(item.title)
        val itemCode = extractJavCode(item.title, item.id)
        return when {
            itemRaw == raw -> 5
            itemBase == base -> 4
            queryCode != null && itemCode != null && queryCode.equals(itemCode, ignoreCase = true) -> 4
            itemBase.startsWith(base) -> 3
            normQuery.isNotEmpty() && normalizeCjkForMatch(item.title).contains(normQuery) -> 2
            itemBase.contains(base) || itemBase.length >= 2 && base.contains(itemBase) -> 1
            else -> 0
        }
    }
    return items.filter { year == null || it.year == null || it.year == year }
        .distinctBy { it.sourceId to it.id }
        .sortedWith(compareByDescending<MovieCatalogItem> { clipRequested || !it.isClip }
            .thenByDescending(::score).thenByDescending { year != null && it.year == year })
}

/** A bare CMS duration has no reliable unit. Accept explicit units or a clock without guessing. */
private fun parseCatalogDuration(value: String?): Double? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val clock = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})").matchEntire(text)
    val seconds = if (clock != null) {
        val hours = clock.groupValues[1].takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: 0.0
        val minutes = clock.groupValues[2].toDoubleOrNull() ?: return null
        val seconds = clock.groupValues[3].toDoubleOrNull() ?: return null
        if (minutes >= 60 || seconds >= 60) return null
        hours * 3600 + minutes * 60 + seconds
    } else {
        val units = Regex("(?:(\\d+(?:\\.\\d+)?)\\s*(?:小时|时|hours?|h))?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*(?:分钟|分|minutes?|min|m))?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*(?:秒|seconds?|sec|s))?", RegexOption.IGNORE_CASE)
            .matchEntire(text) ?: return null
        if (units.groupValues.drop(1).all(String::isEmpty)) return null
        val parts = units.groupValues.drop(1).map { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: return null }
        parts[0] * 3600 + parts[1] * 60 + parts[2]
    }
    return seconds.takeIf { it.isFinite() && it > 0 }
}

private fun JsonObject.value(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
