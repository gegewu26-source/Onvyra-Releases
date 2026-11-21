package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A source is eligible only after the caller has checked its enabled flag and media type. */
data class ReaderSearchSource(val id: String, val priority: Int = 0, val host: String = "")

data class ReaderSearchProgress(
    val books: List<NovelBook>,
    val completedSources: Int,
    val eligibleSources: Int,
    val scheduledSources: Int,
    val activeSources: Int,
    val finished: Boolean,
    val searchedSourceIds: Set<String>,
    val errors: Map<String, String> = emptyMap(),
    /** Requests which entered the adapter, including an in-flight request on pause. */
    val startedSourceIds: Set<String> = emptySet(),
)

/** Identity is deliberately conservative: a missing author is never a cross-source wildcard. */
internal object ReaderBookIdentity {
    private val identityWhitespace = Regex("[\\s　]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(identityWhitespace, "")

    private val aliasSuffix = Regex("\\s*\\(\\s*(?:又名|原名)\\s*:\\s*[^()\\s][^()]*\\)\\s*$")

    /** Only an explicitly labelled alias suffix is metadata, not part of the work's title. */
    fun searchTitle(value: String): String {
        val title = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        return aliasSuffix.replace(title, "").trim().ifBlank { title }
    }

    /** Verified catalog retitling, scoped to a known author and media kind. */
    fun searchTitle(book: NovelBook): String {
        book.canonicalTitle.takeIf { it.isNotBlank() && it.length <= 300 }?.let { return searchTitle(it) }
        val title = searchTitle(book.title)
        // Catalog 62df4ecb31aff99597da907c shares its author, full synopsis,
        // 2040381-word length and final chapter with Fanqie 7123153807185284110.
        // https://fanqienovel.com/page/7123153807185284110
        // https://t.shuqi.com/v2/query/8701248/948046.html
        if (ReaderSearchPolicy.kind(book) != 0) return title
        return when (normalizeAuthor(book.author)) {
            normalize("朕闻上古") -> if (normalize(title) == normalize("大明铁血监国：我爹朱棣，杀穿瓦剌"))
                "大明：家父永乐，永镇山河" else title
            else -> title
        }
    }

    fun normalizeAuthor(value: String): String = normalize(value.trim()
        .removePrefix("作者：").removePrefix("作者:").removeSuffix(" 著"))

    fun sameTitle(left: NovelBook, right: NovelBook): Boolean =
        ReaderSearchPolicy.kind(left) == ReaderSearchPolicy.kind(right) &&
            normalize(searchTitle(left.title)).isNotBlank() &&
            (normalize(searchTitle(left.title)) == normalize(searchTitle(right.title)) ||
                normalize(searchTitle(left)) == normalize(searchTitle(right)))

    fun key(book: NovelBook): String {
        val title = normalize(searchTitle(book))
        val writer = normalizeAuthor(book.author)
        val kind = ReaderSearchPolicy.kind(book)
        return if (title.isNotBlank() && writer.isNotBlank()) "$kind\u0000$title\u0000$writer"
        else "$kind\u0000unknown\u0000$title\u0000${book.sourceId}\u0000${book.detailUrl}"
    }

    fun matches(left: NovelBook, right: NovelBook): Boolean = key(left) == key(right)

    /** Fresh matching metadata may update alternative URLs, never the active/pinned route or progress. */
    fun refreshSourceCandidates(current: NovelBook, fresh: List<NovelBook>): NovelBook {
        val latest = fresh.flatMap { listOf(it.asSource()) + it.sources }
            .filter { it.sourceId != current.sourceId && matches(current, it.asBook()) }
        return current.copy(sources = (listOf(current.asSource()) + latest + current.sources)
            .filter { matches(current, it.asBook()) }.distinctBy { it.sourceId })
    }

    /** Opening a fresh search hit must respect an existing explicit bookshelf choice. */
    fun preferredRoute(requested: NovelBook, shelf: List<NovelBook>): NovelBook {
        val saved = shelf.firstOrNull { stored ->
            !stored.preferredSourceId.isNullOrBlank() && ReaderSearchPolicy.kind(stored) == ReaderSearchPolicy.kind(requested) &&
                (matches(stored, requested) || stored.sourceId == requested.sourceId && stored.detailUrl == requested.detailUrl)
        } ?: return requested
        // Keep the selected route, saved chapter and explicit preference as the
        // primary; incoming metadata only contributes same-work candidates/artwork.
        return merge(saved.title, listOf(saved, requested)).first { it.sourceId == saved.sourceId && it.detailUrl == saved.detailUrl }
    }

    /** First arrival remains the reading route; artwork retains its own provider's headers. */
    fun merge(query: String, books: List<NovelBook>): List<NovelBook> {
        val groups = books.groupByTo(linkedMapOf(), ::key)
        return ReaderSearchPolicy.ranked(query, groups.map { (identity, sameBook) ->
            val primary = sameBook.first()
            val candidates = sameBook.flatMap { hit -> listOf(hit.asSource()) + hit.sources }
                .filter { key(it.asBook()) == identity }
                .distinctBy { it.sourceId }
            val artwork = sameBook.firstOrNull { it.coverUrl.isNotBlank() && !NovelCoverResolver.needsCover(it) }
                ?: candidates.asSequence().map { it.asBook() }
                    .firstOrNull { it.coverUrl.isNotBlank() && !NovelCoverResolver.needsCover(it) }
            primary.copy(
                sources = candidates,
                coverUrl = artwork?.coverUrl ?: primary.coverUrl,
                coverReferer = artwork?.coverReferer ?: primary.coverReferer,
                coverRequestHeaders = artwork?.artworkHeaders ?: primary.artworkHeaders,
                description = primary.description.ifBlank { sameBook.firstOrNull { it.description.isNotBlank() }?.description.orEmpty() },
            )
        })
    }
}

/**
 * Searches metadata only. A bounded worker pool publishes each completed source immediately.
 * Each round has a finite request/time budget; lower-priority sources rotate fairly between
 * rounds instead of making hundreds of requests for every keystroke. Cooldowns are temporary.
 */
class ReaderAggregateSearch(
    private val searchSource: suspend (query: String, sourceId: String) -> List<NovelBook>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxParallel: Int = 12,
    private val sourceTimeoutMs: Long = 3_000,
    private val totalTimeoutMs: Long = 16_000,
    private val maxSourcesPerSearch: Int = 60,
) {
    private data class CacheKey(val query: String, val kind: Int, val source: String)
    private data class Cached(val at: Long, val books: List<NovelBook>)
    private data class Health(val failures: Int = 0, val retryAt: Long = 0,
        val latency: Long = Long.MAX_VALUE, val hasResults: Boolean = false)
    private val cache = object : LinkedHashMap<CacheKey, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Cached>) = size > 96
    }
    private val health = object : LinkedHashMap<String, Health>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Health>) = size > 640
    }
    private val rotation = AtomicInteger()
    // These gates are shared by overlapping rounds too (for example, a new query
    // arriving while its predecessor is being cancelled). The host lock table is fixed.
    private val permits = Semaphore(maxParallel)
    private val hostGates = List(128) { Mutex() }
    private sealed interface Event {
        data class Started(val source: ReaderSearchSource) : Event
        data class Complete(val source: ReaderSearchSource, val books: List<NovelBook>, val error: String? = null) : Event
    }

    init {
        require(maxParallel in 1..12 && maxSourcesPerSearch in 1..100)
        require(sourceTimeoutMs > 0 && totalTimeoutMs > 0)
    }

    /** Only already accepted source hits are reusable; callers still select an enabled source. */
    internal fun cachedMatches(book: NovelBook, enabledIds: Set<String>): List<NovelBook> = synchronized(cache) {
        val now = clock()
        cache.entries.asSequence().filter { (key, value) ->
            key.kind == ReaderSearchPolicy.kind(book) && key.source in enabledIds &&
                now - value.at in 0 until cacheTtl(value.books)
        }.flatMap { it.value.books.asSequence() }.filter { ReaderBookIdentity.matches(book, it) }
            .distinctBy { it.sourceId }.toList()
    }

    internal fun learnedPriority(id: String): Int = synchronized(health) {
        val state = health[id] ?: return@synchronized 0
        when {
            state.failures > 0 -> -state.failures * 20
            state.hasResults -> 100
            // A quick empty response is healthy, but should not displace a source
            // which returned real matches. It remains eligible for every query.
            state.latency != Long.MAX_VALUE -> 10
            else -> 0
        }
    }
    internal fun invalidate(query: String, kind: Int) = synchronized(cache) {
        val key = ReaderBookIdentity.normalize(query)
        cache.keys.removeAll { it.query == key && it.kind == kind }
    }

    suspend fun search(
        query: String,
        kind: Int,
        sources: List<ReaderSearchSource>,
        retryDeferred: Boolean = false,
        maxWorkers: Int = maxParallel,
        onProgress: suspend (ReaderSearchProgress) -> Unit = {},
    ): List<NovelBook> {
        currentCoroutineContext().ensureActive()
        val keyword = query.trim()
        val queryKey = ReaderBookIdentity.normalize(keyword)
        val eligible = sources.distinctBy { it.id }
        val enabledIds = eligible.mapTo(hashSetOf()) { it.id }
        val now = clock()
        val cached = synchronized(cache) {
            cache.entries.removeAll { (_, value) -> now - value.at !in 0 until cacheTtl(value.books) }
            cache.entries.filter { (key, _) -> key.query == queryKey && key.kind == kind && key.source in enabledIds }
                .associate { it.key.source to it.value.books }
        }
        val raw = cached.values.flatten().toMutableList()
        val completed = cached.keys.toMutableSet()
        val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val errors = linkedMapOf<String, String>()
        val selected = if (queryKey.isBlank()) emptyList() else select(eligible.filter { it.id !in completed }, now, retryDeferred)
        val scheduled = completed.size + selected.size
        var active = 0
        var books = ReaderBookIdentity.merge(keyword, raw)
        suspend fun publish(finished: Boolean) {
            onProgress(ReaderSearchProgress(books, completed.size, eligible.size, scheduled, active,
                finished, completed.toSet(), errors.toMap(), started.toSet()))
        }
        publish(finished = selected.isEmpty())
        if (selected.isEmpty()) return books

        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                val events = Channel<Event>(Channel.BUFFERED)
                val roundPermits = Semaphore(minOf(maxParallel, maxWorkers.coerceAtLeast(1)))
                // Waiting for another alias of the same host must not occupy a
                // worker: otherwise one slow host can starve unrelated fast sites.
                // The selected batch is bounded (60 by default), as are active IO
                // requests across this round AND overlapping rounds.
                selected.forEach { source ->
                    launch(Dispatchers.IO) {
                            suspend fun runSource() {
                                started += source.id
                                events.send(Event.Started(source))
                                val began = clock()
                                try {
                                    val result = withTimeoutOrNull(sourceTimeoutMs) { searchSource(keyword, source.id) }
                                    if (result == null) {
                                        failed(source.id)
                                        events.send(Event.Complete(source, emptyList(), "搜索超时"))
                                    } else {
                                        val accepted = result.asSequence().filter { hit ->
                                            hit.sourceId == source.id && ReaderSearchPolicy.kind(hit) == kind &&
                                                !(hit.isComic && hit.isAudio) &&
                                                hit.title.isNotBlank() && hit.title.length <= 300 && hit.author.length <= 160 && hit.id.length <= 1_024 &&
                                                safeDetailUrl(hit.detailUrl) &&
                                                ReaderSearchPolicy.matchRank(keyword, hit.title, hit.author) != null
                                        }.distinctBy { it.detailUrl }.take(60).map { it.copy(
                                            sources = emptyList(), description = it.description.take(2_000),
                                            canonicalTitle = "", // Source metadata cannot grant itself an identity alias.
                                            latestChapter = it.latestChapter.take(500), updateTime = it.updateTime.take(100),
                                            category = it.category.take(100), status = it.status.take(100),
                                            coverUrl = it.coverUrl.takeIf { url -> url.length <= 4_096 }.orEmpty(),
                                            coverReferer = it.coverReferer.takeIf { url -> url.length <= 2_048 }.orEmpty(),
                                        ) }.toList()
                                        synchronized(health) {
                                            health[source.id] = Health(latency = (clock() - began).coerceAtLeast(0),
                                                hasResults = accepted.isNotEmpty())
                                        }
                                        synchronized(cache) { cache[CacheKey(queryKey, kind, source.id)] = Cached(clock(), accepted) }
                                        events.send(Event.Complete(source, accepted))
                                    }
                                } catch (cancelled: CancellationException) {
                                    // A caller cancellation must cancel every worker. A source
                                    // throwing its own cancellation while our job is still alive
                                    // is an isolated source failure, not a 16-second stalled round.
                                    currentCoroutineContext().ensureActive()
                                    failed(source.id)
                                    events.send(Event.Complete(source, emptyList(), "来源已取消搜索"))
                                } catch (error: Exception) {
                                    failed(source.id)
                                    events.send(Event.Complete(source, emptyList(), error.message.orEmpty().take(160).ifBlank { "来源未完成搜索" }))
                                }
                            }
                            val host = source.host.lowercase(Locale.ROOT)
                            val gate = if (host.isBlank()) null else hostGates[(host.hashCode() and Int.MAX_VALUE) % hostGates.size]
                            if (gate == null) roundPermits.withPermit { permits.withPermit { runSource() } }
                            else gate.withLock { roundPermits.withPermit { permits.withPermit { runSource() } } }
                    }
                }
                repeat(selected.size) {
                    // A source start and a completion are separate events, so collect
                    // until this iteration has consumed one completion.
                    while (true) {
                        when (val event = events.receive()) {
                            is Event.Started -> { active++; publish(finished = false) }
                            is Event.Complete -> {
                                active = (active - 1).coerceAtLeast(0)
                                completed += event.source.id
                                event.error?.let { errors[event.source.id] = it }
                                raw += event.books
                                books = ReaderBookIdentity.merge(keyword, raw)
                                publish(finished = false)
                                break
                            }
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        // A round deadline can interrupt a source before its individual deadline.
        // It was attempted: do not schedule it again ahead of the untouched tail.
        (started - completed).forEach { id ->
            failed(id)
            errors[id] = "本轮搜索超时"
            completed += id
        }
        active = 0
        publish(finished = true)
        return books
    }

    /**
     * The first finite round is fast; later rounds automatically drain the untouched
     * queue. Results remain usable throughout. The caller owns cancellation and can
     * resume from the last snapshot without depending on the small response cache.
     */
    suspend fun searchAll(
        query: String,
        kind: Int,
        sources: List<ReaderSearchSource>,
        previous: ReaderSearchProgress? = null,
        retryDeferred: Boolean = false,
        maxWorkers: Int = maxParallel,
        maxRounds: Int = Int.MAX_VALUE,
        preferLatestRoutes: Boolean = false,
        onProgress: suspend (ReaderSearchProgress) -> Unit = {},
    ): List<NovelBook> {
        val eligible = sources.distinctBy { it.id }
        val eligibleIds = eligible.mapTo(hashSetOf()) { it.id }
        val completed = previous?.searchedSourceIds.orEmpty().filterTo(linkedSetOf()) { it in eligibleIds }
        // A canceled in-flight request has no terminal result and remains pending.
        // A real per-source/round timeout is explicitly added to searchedSourceIds.
        var books = previous?.books.orEmpty().filter { ReaderSearchPolicy.kind(it) == kind }
        val errors = previous?.errors.orEmpty().toMutableMap()
        var rounds = 0
        while (rounds++ < maxRounds) {
            currentCoroutineContext().ensureActive()
            val remaining = eligible.filterNot { it.id in completed }
            if (remaining.isEmpty()) break
            val before = completed.size
            val alreadyFound = books
            var round: ReaderSearchProgress? = null
            search(query, kind, remaining, retryDeferred, maxWorkers) { progress ->
                round = progress
                books = ReaderBookIdentity.merge(query,
                    if (preferLatestRoutes) progress.books + alreadyFound else alreadyFound + progress.books).take(MAX_VISIBLE_BOOKS)
                val combined = completed + progress.searchedSourceIds
                onProgress(progress.copy(books = books, completedSources = combined.size,
                    eligibleSources = eligible.size, scheduledSources = eligible.size,
                    finished = false, searchedSourceIds = combined,
                    errors = errors + progress.errors))
            }
            val finished = round ?: break
            completed += finished.searchedSourceIds
            errors += finished.errors
            // Only temporarily cooled sources can remain after an empty round.
            // Expose them as pending; an explicit continue can retry them once.
            if (completed.size == before) break
        }
        currentCoroutineContext().ensureActive()
        onProgress(ReaderSearchProgress(books, completed.size, eligible.size, eligible.size,
            0, true, completed.toSet(), errors.toMap()))
        return books
    }

    private fun failed(sourceId: String) = synchronized(health) {
        val failures = ((health[sourceId]?.failures ?: 0) + 1).coerceAtMost(6)
        health[sourceId] = Health(failures, clock() + minOf(300_000L, 10_000L shl (failures - 1)))
    }

    private fun select(sources: List<ReaderSearchSource>, at: Long, retryDeferred: Boolean = false): List<ReaderSearchSource> {
        val states = synchronized(health) { health.toMap() }
        val ready = sources.filter { retryDeferred || (states[it.id]?.retryAt ?: 0) <= at }
        val preferred = ready.filter { it.priority > 0 }.sortedWith(
            compareByDescending<ReaderSearchSource> { it.priority }
                .thenBy { states[it.id]?.failures ?: 0 }.thenBy { states[it.id]?.latency ?: Long.MAX_VALUE }
        ).take(minOf(8, maxSourcesPerSearch))
        val preferredIds = preferred.mapTo(hashSetOf()) { it.id }
        val remaining = ready.filter { it.id !in preferredIds }.sortedWith(
            compareByDescending<ReaderSearchSource> { it.priority }.thenBy { states[it.id]?.failures ?: 0 }
        )
        val count = minOf(maxSourcesPerSearch - preferred.size, remaining.size)
        val start = if (remaining.isEmpty()) 0 else Math.floorMod(rotation.getAndAdd(count), remaining.size)
        val selected = preferred + List(count) { remaining[(start + it) % remaining.size] }
        // Avoid queueing several aliases of one host ahead of every other host.
        val groups = selected.groupByTo(linkedMapOf()) { it.host.ifBlank { it.id }.lowercase(Locale.ROOT) }
            .values.map { ArrayDeque(it) }
        return buildList {
            while (groups.any { it.isNotEmpty() }) groups.forEach { if (it.isNotEmpty()) add(it.removeFirst()) }
        }
    }

    private fun cacheTtl(books: List<NovelBook>) = if (books.isEmpty()) 60_000L else 180_000L
    private companion object { const val MAX_VISIBLE_BOOKS = 600 }

    private fun safeDetailUrl(value: String): Boolean = if (value.length > 2_048) false else try {
        val optionsAt = Regex(",\\s*(?=\\{)").find(value)?.range?.first
        val raw = if (optionsAt == null) value else value.substring(0, optionsAt)
        val validOptions = optionsAt == null ||
            kotlinx.serialization.json.Json.parseToJsonElement(value.substring(optionsAt + 1).trim()) is kotlinx.serialization.json.JsonObject
        val url = URI(raw)
        val emptyIdentity = url.rawQuery.orEmpty().split('&').any { parameter ->
            val name = parameter.substringBefore('=').lowercase(Locale.ROOT)
            name in setOf("id", "bookid", "novelid", "resourceid", "bid", "aid", "comicid", "albumid") &&
                parameter.substringAfter('=', "").lowercase(Locale.ROOT) in setOf("", "null", "undefined")
        }
        validOptions && !emptyIdentity && url.scheme in setOf("http", "https") && !url.host.isNullOrBlank() && url.userInfo == null
    } catch (_: Exception) { false }
}
