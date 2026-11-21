package com.constx.onvyra.data.media

import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

internal data class MoviePlaybackSelection(
    val item: MovieCatalogItem,
    val source: VideoPlaybackSource,
    val episode: Int,
    val lineName: String,
    val searchReport: MovieCatalogSearchReport? = null,
    val contentTypeVerified: Boolean = false,
    val contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
)

internal class MovieTitleAmbiguousException : IOException("找到多个同名影片，请补充上映年份后重试。")

/** Search catalog metadata first, then verify actual media instead of guessing a player URL. */
internal class MoviePlaybackResolver(
    private val catalog: MovieCatalogClient,
    private val resolveMedia: suspend (String) -> VideoPlaybackSource,
) {
    private data class CachedOptions(val options: List<MovieSourceOption>, val createdAt: Long)
    private val sourceOptionsCache = object : LinkedHashMap<String, CachedOptions>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedOptions>?): Boolean = size > 8
    }

    private data class CachedItem(val item: MovieCatalogItem, val createdAt: Long)
    private val itemCache = object : LinkedHashMap<Pair<String, String>, CachedItem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, CachedItem>?): Boolean = size > 64
    }

    private fun rememberItem(item: MovieCatalogItem) {
        if (item.lines.isEmpty()) return
        synchronized(itemCache) { itemCache[item.sourceId to item.id] = CachedItem(item, System.nanoTime()) }
    }

    private fun cachedItem(sourceId: String, itemId: String): MovieCatalogItem? = synchronized(itemCache) {
        itemCache[sourceId to itemId]?.takeIf {
            System.nanoTime() - it.createdAt in 0 until SOURCE_OPTIONS_TTL_NANOS
        }?.item
    }

    suspend fun searchAndResolve(title: String, year: Int? = null, episode: Int = 1, sourceIds: Set<String>? = null,
                                 contentType: MovieContentType = MovieContentType.GENERAL_VIDEO): MoviePlaybackSelection = bounded {
        require(title.isNotBlank() && title.length <= 160 && episode > 0)
        var latest: MovieCatalogSearchResult? = null
        val tried = HashSet<Pair<String, String>>()
        suspend fun confirmedEdition(candidate: MovieCatalogItem): MovieCatalogItem? {
            if (year == null || candidate.year == year) return candidate
            if (candidate.year != null) return null
            val detailed = withTimeoutOrNull(3_000) { catalog.getItem(candidate.sourceId, candidate.id) }
            return detailed?.takeIf { it.year == year && isEligibleCatalogWork(it, contentType) &&
                isMatchingCatalogSearchResult(title, it) }
        }
        // An explicit edition can resolve as soon as it arrives. Without a requested year,
        // only the preferred catalog's exact title with one fully known year gets the fast path;
        // unknown or conflicting editions still require the normal ambiguity checks.
        val early = catalog.searchReportUpdates(title, year, sourceIds).transform { lookup ->
            latest = lookup
            val matches = matchingItems(lookup.items, title, contentType)
            val preferred = matches.filter { it.sourceId == "wsyzy" && isMatchingMovieTitle(title, it.title) }
            val preferredYears = preferred.mapNotNull { it.year }.distinct()
            if (year == null && preferredYears.size > 1) throw MovieTitleAmbiguousException()
            val candidates = when {
                year != null -> matches
                preferred.isNotEmpty() && preferred.all { it.year != null } && preferredYears.size == 1 -> preferred
                else -> emptyList()
            }
            for (candidate in candidates) {
                if (!tried.add(candidate.sourceId to candidate.id)) continue
                try {
                    val item = confirmedEdition(candidate) ?: continue
                    emit(resolveItem(item, episode, contentType = contentType).copy(searchReport = lookup.report,
                        contentTypeVerified = verifiesMovieContentType(contentType, item.category.orEmpty())))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Later independently responding catalogs can still succeed. */ }
            }
        }.firstOrNull()
        if (early != null) return@bounded early
        val lookup = latest ?: catalog.searchWithReport(title, year, sourceIds)
        if (lookup.report.successfulSources == 0) throw MovieCatalogSearchException(lookup.report)
        val matches = matchingItems(lookup.items, title, contentType)
        val strictMatches = matches.filter { isMatchingMovieTitle(title, it.title) }
        if (matches.isEmpty()) throw MovieCatalogNoMatchException(lookup.report)
        if (year == null && strictMatches.isNotEmpty() && strictMatches.mapNotNull { it.year }.distinct().size > 1) {
            throw MovieTitleAmbiguousException()
        }
        for (candidate in matches) {
            if (!tried.add(candidate.sourceId to candidate.id)) continue
            try {
                val item = confirmedEdition(candidate) ?: continue
                return@bounded resolveItem(item, episode, contentType = contentType).copy(searchReport = lookup.report,
                    contentTypeVerified = verifiesMovieContentType(contentType, item.category.orEmpty()))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Try an independent source/line, not the same failed address. */ }
        }
        throw MovieCatalogPlaybackException(lookup.report)
    }

    private fun matchingItems(items: List<MovieCatalogItem>, title: String, type: MovieContentType): List<MovieCatalogItem> {
        val eligible = items.filter { isEligibleCatalogWork(it, type) }
        val strict = eligible.filter { isMatchingMovieTitle(title, it.title) }
        return if (strict.isNotEmpty()) (strict + eligible.filter {
            it.sourceId in EXTERNAL_VIDEO_SITE_IDS && isMatchingCatalogSearchResult(title, it)
        }).distinctBy { it.sourceId to it.id } else eligible.filter { isMatchingCatalogSearchResult(title, it) }
    }

    suspend fun refresh(sourceId: String, itemId: String, episode: Int, lineName: String?,
                        contentType: MovieContentType = MovieContentType.GENERAL_VIDEO): MoviePlaybackSelection = bounded {
        resolveItem(catalog.getItem(sourceId, itemId), episode, lineName, contentType)
    }

    /** Only catalog metadata is fetched here; preparing a menu never downloads media tracks. */
    suspend fun listOptions(
        sourceId: String,
        itemId: String,
        episode: Int,
        currentLine: String? = null,
        title: String? = null,
        year: Int? = null,
        contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
    ): List<MovieSourceOption> = bounded {
        require(episode > 0)
        val cached = cachedOptions(optionsKey(sourceId, itemId, episode, title, year, contentType))
        if (cached != null) return@bounded cached.markSelected(sourceId, itemId, currentLine)
        optionUpdates(sourceId, itemId, episode, currentLine, title, year, contentType = contentType).lastOrNull().orEmpty()
    }

    /** Current/cached lines arrive first, followed by each independently responding catalog. */
    fun optionUpdates(
        sourceId: String,
        itemId: String,
        episode: Int,
        currentLine: String? = null,
        title: String? = null,
        year: Int? = null,
        sourceIds: Set<String>? = null,
        currentItem: MovieCatalogItem? = null,
        contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
    ): Flow<List<MovieSourceOption>> = channelFlow {
        require(episode > 0)
        val key = optionsKey(sourceId, itemId, episode, title, year, contentType)
        var current = currentItem ?: cachedItem(sourceId, itemId)?.takeIf {
            isEligibleCatalogWork(it, contentType) &&
                (title.isNullOrBlank() || isMatchingCatalogSearchResult(title, it)) &&
                (year == null || it.year == year)
        }
        val knownTitle = title?.takeIf(String::isNotBlank)
        // With a known title, the current provider can fail or stall without blocking alternatives.
        if (current == null && knownTitle == null) current = catalog.getItem(sourceId, itemId)
        val requestedTitle = knownTitle ?: current!!.title
        val requestedYear = year ?: current?.year
        val hasCurrentDetails = current?.lines?.isNotEmpty() == true
        if (current == null) current = MovieCatalogItem(itemId, requestedTitle, requestedYear, sourceId = sourceId, sourceName = sourceId)
        val items = LinkedHashMap<Pair<String, String>, MovieCatalogItem>()
        val updateMutex = Mutex()
        var lastOptions = cachedOptions(key).orEmpty().markSelected(sourceId, itemId, currentLine)
        if (lastOptions.isNotEmpty()) send(lastOptions)
        suspend fun publish(newItems: List<MovieCatalogItem>, refreshedCurrent: MovieCatalogItem? = null) = updateMutex.withLock {
            refreshedCurrent?.let {
                require(isEligibleCatalogWork(it, contentType) && isMatchingCatalogSearchResult(requestedTitle, it)) { "片源与当前影片不一致" }
                require(requestedYear == null || it.year == null || it.year == requestedYear) { "片源与当前影片年份不一致" }
                current = it
            }
            newItems.forEach { incoming ->
                val identity = incoming.sourceId to incoming.id
                val known = items[identity]
                // An older aggregate snapshot cannot erase a year just proven by detail.
                if (known?.year == null || incoming.year != null || !isMatchingMovieTitle(known.title, incoming.title))
                    items[identity] = incoming
            }
            val cacheCandidates = listOf(current!!) + newItems
            cacheCandidates.filter {
                isEligibleCatalogWork(it, contentType) && isMatchingCatalogSearchResult(requestedTitle, it) &&
                    (requestedYear == null || it.year == requestedYear)
            }.forEach(::rememberItem)
            val built = buildMovieSourceOptions(items.values.toList(), current!!, episode, currentLine, requestedTitle, requestedYear, contentType)
            // Keep already published matching choices when a later batch only queries new catalogs.
            val options = mergeMovieSourceOptions(lastOptions, built, sourceId, itemId, episode,
                requestedTitle, requestedYear ?: current!!.year, contentType).markSelected(sourceId, itemId, currentLine)
            if (options != lastOptions) {
                lastOptions = options
                synchronized(sourceOptionsCache) { sourceOptionsCache[key] = CachedOptions(options, System.nanoTime()) }
                send(options)
            }
        }
        publish(emptyList())
        coroutineScope {
            if (!hasCurrentDetails) launch {
                try { publish(emptyList(), catalog.getItem(sourceId, itemId)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Search other catalogs even if this one is unavailable. */ }
            }
            launch {
                val yearChecks = Semaphore(2)
                val yearChecked = HashSet<Pair<String, String>>()
                val searchQuery = if (sourceId in EXTERNAL_VIDEO_SITE_IDS) {
                    extractJavCode(requestedTitle, itemId) ?: movieCatalogQueryTitle(requestedTitle)
                } else {
                    movieCatalogQueryTitle(requestedTitle)
                }
                try { catalog.searchUpdates(searchQuery, requestedYear, sourceIds).collect { rows ->
                    publish(rows) // Never block current/cached choices behind missing-year metadata.
                    val expectedYear = requestedYear ?: current?.year
                    if (expectedYear != null) rows.asSequence().filter {
                        it.year == null && isEligibleCatalogWork(it, contentType) &&
                            isMatchingCatalogSearchResult(requestedTitle, it) && (it.sourceId to it.id) !in yearChecked
                    }.distinctBy { it.sourceId to it.id }.take((8 - yearChecked.size).coerceAtLeast(0)).forEach { candidate ->
                        yearChecked += candidate.sourceId to candidate.id
                        launch { yearChecks.withPermit {
                            try {
                                val detailed = withTimeoutOrNull(3_000) { catalog.getItem(candidate.sourceId, candidate.id) }
                                if (detailed != null && detailed.year == expectedYear && isEligibleCatalogWork(detailed, contentType) &&
                                    isMatchingCatalogSearchResult(requestedTitle, detailed)) publish(listOf(detailed))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* A missing edition stays unverified, never accepted as this year. */ }
                        } }
                    }
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the current/previously published choices. */ }
            }
        }
        if (lastOptions.isEmpty()) throw IOException("暂时未找到可用的同片片源，请稍后重试")
        send(lastOptions)
    }.flowOn(Dispatchers.IO)

    private fun optionsKey(sourceId: String, itemId: String, episode: Int, title: String?, year: Int?, contentType: MovieContentType): String =
        listOf(sourceId, itemId, episode.toString(), title.orEmpty(), year?.toString().orEmpty(),
            contentType.wireValue, catalog.sourceIdsForSearch().sorted().joinToString(",")).joinToString("\u0000")

    private fun cachedOptions(key: String): List<MovieSourceOption>? = synchronized(sourceOptionsCache) {
        sourceOptionsCache[key]?.takeIf { System.nanoTime() - it.createdAt < SOURCE_OPTIONS_TTL_NANOS }?.options
    }

    /** A manual choice must resolve precisely that line, with fresh URLs and no silent fallback. */
    suspend fun resolveOption(option: MovieSourceOption): MoviePlaybackSelection = bounded {
        require(option.episode > 0 && option.lineIndex >= 0)
        require(option.id == movieSourceOptionId(option.sourceId, option.itemId, option.episode, option.lineIndex, option.lineName, option.contentType)) {
            "片源选项无效，请重新选择"
        }
        val cached = cachedItem(option.sourceId, option.itemId)
        val item = cached ?: catalog.getItem(option.sourceId, option.itemId)
        fun exactPart(value: MovieCatalogItem): Pair<MoviePlaybackLine, MovieEpisode> {
            require(value.sourceId == option.sourceId && value.id == option.itemId) { "片源目录编号已变化" }
            require(isEligibleCatalogWork(value, option.contentType) && isMatchingCatalogSearchResult(option.title, value)) { "该片源已不再对应当前影片" }
            require(option.year == null || value.year == option.year) { "该片源已不再对应当前影片年份" }
            val indexedLine = value.lines.getOrNull(option.lineIndex)?.takeIf { it.name == option.lineName }
            val line = indexedLine ?: value.lines.filter { it.name == option.lineName }.singleOrNull()
                ?: throw IOException("所选播放线路已不可用，请重新选择")
            return line to (line.episodeAt(option.episode) ?: throw IOException("该线路没有所选集数"))
        }
        suspend fun resolveExact(value: MovieCatalogItem): MoviePlaybackSelection {
            val (line, part) = exactPart(value)
            val source = resolveMedia(part.url)
            if (source.kind != VideoPlaybackKind.DIRECT) throw IOException("该线路没有返回可直接播放的视频")
            if (hasNonWorkLabel(source.title.orEmpty())) throw IOException("该线路返回了解说、预告或剪辑")
            rememberItem(value)
            return MoviePlaybackSelection(value, source.forItem(value), option.episode, line.name,
                contentTypeVerified = verifiesMovieContentType(option.contentType, value.category.orEmpty()), contentType = option.contentType)
        }
        // Identity/edition/line validation runs before the network retry path. A stale
        // media URL can refresh exactly this catalog locator once; it cannot switch lines.
        exactPart(item)
        try { resolveExact(item) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (cached == null) throw error
            val refreshed = catalog.getItem(option.sourceId, option.itemId)
            resolveExact(refreshed)
        }
    }

    private suspend fun resolveItem(item: MovieCatalogItem, episode: Int, preferredLine: String? = null,
                                    contentType: MovieContentType = MovieContentType.GENERAL_VIDEO): MoviePlaybackSelection {
        require(isEligibleCatalogWork(item, contentType)) { "片源作品类型与请求不一致" }
        rememberItem(item)
        val candidates = item.lines.mapNotNull { line ->
            line.episodeAt(episode)?.let { line to it }
        }.sortedByDescending { (line, part) ->
            (if (parseVideoLink(part.url)?.platform == VideoPlatform.DIRECT) 10 else 0) +
                (if (line.name == preferredLine) 1 else 0)
        }.distinctBy { it.second.url }
        if (candidates.isEmpty()) throw IOException("该影片没有所选集数的播放地址。")
        for ((line, part) in candidates) {
            try {
                val resolved = resolveMedia(part.url)
                if (resolved.kind != VideoPlaybackKind.DIRECT) continue
                if (hasNonWorkLabel(resolved.title.orEmpty())) continue
                return MoviePlaybackSelection(item, resolved.forItem(item), episode, line.name,
                    contentTypeVerified = verifiesMovieContentType(contentType, item.category.orEmpty()), contentType = contentType)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Other lines can use a different CDN and certificate. */ }
        }
        throw IOException("该片源的播放线路暂时不可用。")
    }

    private suspend fun <T> bounded(block: suspend () -> T): T = try {
        withTimeout(60_000) { block() }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("影视搜索或播放线路验证超时，请稍后重试。")
    }
}

private const val SOURCE_OPTIONS_TTL_NANOS = 5L * 60L * 1_000_000_000L

private fun List<MovieSourceOption>.markSelected(sourceId: String, itemId: String, lineName: String?): List<MovieSourceOption> {
    val selectedId = firstOrNull { it.sourceId == sourceId && it.itemId == itemId && it.lineName == lineName }?.id
    return map { it.copy(selected = it.id == selectedId) }
}

private fun VideoPlaybackSource.forItem(item: MovieCatalogItem): VideoPlaybackSource = copy(
    title = "${item.title} · ${item.sourceName}", durationSeconds = durationSeconds ?: item.durationSeconds,
)

/** Keep a trailer/commentary with a similar title from standing in for the requested film. */
internal fun isMatchingMovieTitle(requested: String, actual: String): Boolean {
    fun raw(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "")
    fun base(value: String): String {
        var result = raw(value)
        repeat(6) {
            val next = result.replace(MOVIE_VARIANT_SUFFIX, "")
            if (next.isEmpty() || next == result) return result
            result = next
        }
        return result
    }
    return raw(requested) == raw(actual) || base(requested).let { it.isNotEmpty() && it == base(actual) }
}

private const val MOVIE_SUFFIX_LABELS =
    "8k|4k|4320p|2160p|1080p|720p|uhd|hdr|蓝光|高清|超清|国语版?|粤语版?|普通话版?|川语版?|国粤双语|中字|完整版|修复版"
private val MOVIE_VARIANT_SUFFIX = Regex("(?:$MOVIE_SUFFIX_LABELS)$")
private val MOVIE_QUERY_SUFFIX = Regex(
    "[\\s_\\-·]*[（(\\[【]?(?:$MOVIE_SUFFIX_LABELS)[）)\\]】]?\\s*$", RegexOption.IGNORE_CASE,
)

/** Search the film itself so a selected4K/dubbed version does not hide other providers' versions. */
internal fun movieCatalogQueryTitle(value: String): String {
    var result = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().removeSurrounding("《", "》").trim()
    repeat(6) {
        val next = result.replace(MOVIE_QUERY_SUFFIX, "").trim()
        if (next.isEmpty() || next == result) return result
        result = next
    }
    return result
}
