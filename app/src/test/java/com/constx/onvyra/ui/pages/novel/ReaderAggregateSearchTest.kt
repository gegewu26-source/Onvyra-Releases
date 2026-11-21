package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ReaderAggregateSearchTest {
    @Test fun defaultForegroundSearchReallyUsesTwelveSharedWorkers() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val full = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            if (count == 12) full.complete(Unit)
            try { release.await(); listOf(book(id)) } finally { active.decrementAndGet() }
        })
        val job = async { search.search("同名作品", 0, (0 until 24).map { ReaderSearchSource("source$it") }) }
        try { withTimeout(2_000) { full.await() } } finally { release.complete(Unit) }
        withTimeout(2_000) { job.await() }
        assertEquals(12, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun backgroundWorkerOverrideStaysAtTwoWithTheLargerForegroundPool() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { delay(10); listOf(book(id)) } finally { active.decrementAndGet() }
        })
        search.search("同名作品", 0, (0 until 12).map { ReaderSearchSource("source$it") }, maxWorkers = 2)
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun waitingForAnOverlappingHostsAliasDoesNotStarveAnUnrelatedFastSource() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fastPublished = CompletableDeferred<Unit>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            if (id == "held") { entered.complete(Unit); release.await() }
            listOf(book(id))
        }, maxParallel = 2, sourceTimeoutMs = 2_000, totalTimeoutMs = 3_000)
        val held = launch { search.search("同名作品", 0, listOf(ReaderSearchSource("held", host = "shared.example"))) }
        try {
            withTimeout(1_000) { entered.await() }
            val nextRound = async {
                search.search("同名作品", 0, listOf(
                    ReaderSearchSource("alias", priority = 20, host = "shared.example"),
                    ReaderSearchSource("fast", priority = 10, host = "independent.example")), maxWorkers = 1) {
                    if (it.books.any { result -> result.sources.any { source -> source.sourceId == "fast" } }) fastPublished.complete(Unit)
                }
            }
            // The shared host is still held. The only round worker can serve a
            // different host instead of blocking behind the earlier alias.
            withTimeout(1_000) { fastPublished.await() }
            assertFalse(release.isCompleted)
            release.complete(Unit)
            assertEquals(2, withTimeout(2_000) { nextRound.await() }.single().sourceCount)
        } finally { release.complete(Unit); held.cancelAndJoin() }
    }

    @Test fun backgroundSourceDiscoveryUsesTwoWorkersAndStillFindsAllActualSources() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { delay(10); listOf(book(id)) } finally { active.decrementAndGet() }
        })
        val found = search.searchAll("同名作品", 0,
            (1..12).map { ReaderSearchSource("s$it", host = "h$it.example") }, maxWorkers = 2)
        assertEquals(12, found.single().sourceCount)
        assertTrue("Background scanning must leave room for foreground content", peak.get() <= 2)
    }

    @Test fun explicitSourceChoiceIsBackwardCompatibleAndSurvivesCandidateAndJsonRoundTrips() {
        val legacy = """{"id":"old","title":"作品","author":"作者","detailUrl":"https://old.example/book/1","sourceId":"old"}"""
        assertNull(Json.decodeFromString<NovelBook>(legacy).preferredSourceId)
        val chosen = book("selected").copy(preferredSourceId = "selected")
        assertEquals("selected", chosen.asSource().asBook().preferredSourceId)
        assertEquals("selected", Json.decodeFromString<NovelBook>(Json.encodeToString(NovelBook.serializer(), chosen)).preferredSourceId)
        assertEquals("selected", ReaderBookIdentity.merge(chosen.title, listOf(chosen, book("other"))).single().preferredSourceId)
    }
    @Test fun openingAnotherSearchRoutePreservesTheSameWorksSavedPreferenceAndChapter() {
        val selected = book("selected").copy(preferredSourceId = "selected", lastReadChapterIndex = 8,
            lastReadChapterTitle = "第九章 初见", lastReadChapterUrl = "https://selected.example/chapter/9")
        val searchHit = book("search").copy(coverUrl = "https://image.example/cover.jpg")
        val resolved = ReaderBookIdentity.preferredRoute(searchHit, listOf(selected))
        assertEquals("selected", resolved.sourceId); assertEquals("selected", resolved.preferredSourceId)
        assertEquals(8, resolved.lastReadChapterIndex); assertEquals(selected.lastReadChapterUrl, resolved.lastReadChapterUrl)
        assertEquals(setOf("selected", "search"), resolved.sources.map { it.sourceId }.toSet())
        assertEquals(searchHit.coverUrl, resolved.coverUrl)
        assertEquals(searchHit, ReaderBookIdentity.preferredRoute(searchHit, listOf(selected.copy(author = "另一位作者"))))
        assertEquals(searchHit, ReaderBookIdentity.preferredRoute(searchHit, listOf(selected.copy(isComic = true))))
        assertEquals(searchHit, ReaderBookIdentity.preferredRoute(searchHit, listOf(selected.copy(preferredSourceId = null))))
    }
    private fun book(source: String, title: String = "同名作品", author: String = "真实作者") =
        NovelBook("${source}_book", title, author, detailUrl = "https://$source.example/book/1", sourceId = source)

    @Test fun exactIdentityMergesOnlyActualDistinctSourcesAndKeepsReadingRouteAndCoverHeaders() {
        val primary = book("first").copy(requestHeaders = mapOf("Cookie" to "text-source-only"))
        val actualCover = book("second").copy(coverUrl = "https://images.example/actual.jpg",
            coverReferer = "https://second.example/", requestHeaders = mapOf("Referer" to "https://second.example/"),
            coverRequestHeaders = emptyMap())
        val merged = ReaderBookIdentity.merge("同名作品", listOf(primary, actualCover, actualCover.copy(id = "duplicate"))).single()
        assertEquals("first", merged.sourceId)
        assertEquals(primary.id, merged.id)
        assertEquals(2, merged.sourceCount)
        assertEquals(listOf("first", "second"), merged.sources.map { it.sourceId })
        assertEquals(actualCover.coverUrl, merged.coverUrl)
        assertEquals(primary.requestHeaders, merged.requestHeaders)
        assertEquals(emptyMap<String, String>(), merged.artworkHeaders)
    }

    @Test fun collidingTitlesUnknownAuthorsAndMediaNeverBecomeAlternativeRoutes() {
        val books = listOf(book("one"), book("two", author = "另一位作者"),
            book("three", author = ""), book("four", author = ""),
            book("comic").copy(isComic = true), book("audio").copy(isAudio = true))
        val merged = ReaderBookIdentity.merge("同名作品", books)
        assertEquals(6, merged.size)
        assertTrue(merged.all { it.sourceCount == 1 })
        assertFalse(ReaderBookIdentity.matches(book("one", title = "同名·作品"), book("two", title = "同名作品")))
        assertTrue(ReaderBookIdentity.matches(book("one", title = " 同名作品 "), book("two")))
    }

    @Test fun progressPublishesFirstSourceBeforeSlowSourceCompletesAndIncreasesActualCount() = runBlocking {
        val releaseSlow = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<ReaderSearchProgress>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            if (id == "slow") releaseSlow.await()
            listOf(book(id))
        }, sourceTimeoutMs = 1_000, totalTimeoutMs = 2_000)
        val result = withTimeout(2_000) {
            search.search("同名作品", 0, listOf(ReaderSearchSource("fast"), ReaderSearchSource("slow"))) { progress ->
                snapshots += progress
                if (progress.books.isNotEmpty() && progress.completedSources == 1 && !progress.finished) {
                    assertEquals(1, progress.books.single().sourceCount)
                    releaseSlow.complete(Unit)
                }
            }
        }
        assertEquals(2, result.single().sourceCount)
        assertTrue(snapshots.any { it.books.isNotEmpty() && it.completedSources == 1 && !it.finished })
        assertEquals(2, snapshots.last().completedSources)
        assertTrue(snapshots.last().finished)
        assertEquals(0, snapshots.last().activeSources)
    }

    @Test fun relatedTitlesPublishBeforeSlowSourcesAndRemainSeparateFromTheExactWorkInCache() = runBlocking {
        val query = "我有一群地球人"
        val relatedTitle = "我有一群地球玩家"
        val releaseSlow = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls.incrementAndGet()
            if (id == "slow") releaseSlow.await()
            listOf(
                book(id, relatedTitle).copy(id = "${id}_related", detailUrl = "https://$id.example/book/2"),
                book(id, query).copy(id = "${id}_exact"),
                book(id, "广告推广").copy(id = "${id}_advert", detailUrl = "https://$id.example/book/3"),
            )
        }, sourceTimeoutMs = 2_000, totalTimeoutMs = 3_000)
        val sources = listOf(ReaderSearchSource("fast"), ReaderSearchSource("slow"))
        var early: ReaderSearchProgress? = null
        val found = withTimeout(3_000) {
            search.search(query, 0, sources) { progress ->
                if (progress.books.any { it.title == relatedTitle } && progress.completedSources == 1) {
                    assertFalse(progress.finished)
                    early = progress
                    releaseSlow.complete(Unit)
                }
            }
        }
        assertNotNull("Related results must become usable while other sources are pending", early)
        assertEquals(listOf(query, relatedTitle), found.map { it.title })
        assertTrue(found.all { it.sourceCount == 2 })
        assertFalse(ReaderBookIdentity.matches(found[0], found[1]))
        val cached = mutableListOf<ReaderSearchProgress>()
        assertEquals(found, search.search(query, 0, sources) { cached += it })
        assertEquals(2, calls.get())
        assertEquals(found, cached.first().books)
        assertTrue(cached.first().finished)
    }

    @Test fun wrongMediaWrongSourceUnrelatedAndUnsafeMetadataCannotPolluteResultsOrCounts() = runBlocking {
        val search = ReaderAggregateSearch(searchSource = { _, id -> listOf(
            book(id), book(id).copy(isComic = true), book(id).copy(isAudio = true),
            book(id).copy(isComic = true, isAudio = true), book("impersonated"),
            book(id, title = "广告作品"), book(id).copy(detailUrl = "file:///private/book"),
        ) })
        val result = search.search("同名作品", 0, listOf(ReaderSearchSource("actual")))
        assertEquals(1, result.size)
        assertEquals(1, result.single().sourceCount)
        assertFalse(result.single().isComic || result.single().isAudio)
    }

    @Test fun untrustedMetadataCannotKeepWholeBodiesOrUnboundedArtworkUrlsInSearchCache() = runBlocking {
        val search = ReaderAggregateSearch(searchSource = { _, id -> listOf(book(id).copy(
            description = "正文".repeat(10_000), latestChapter = "章".repeat(2_000),
            canonicalTitle = "外站不能自行声明另一本书的身份",
            coverUrl = "https://images.example/" + "x".repeat(5_000),
        )) })
        val result = search.search("同名作品", 0, listOf(ReaderSearchSource("actual"))).single()
        assertEquals(2_000, result.description.length)
        assertEquals(500, result.latestChapter.length)
        assertTrue(result.coverUrl.isBlank())
        assertTrue(result.canonicalTitle.isBlank())
        assertEquals(1, result.sourceCount)
    }

    @Test fun realPostDetailAddressesAreAllowedButEmptyIdentityAndMalformedOptionsAreNotHits() = runBlocking {
        val post = "https://actual.example/catalog,{\"method\":\"POST\",\"body\":\"bookId=321\"}"
        val search = ReaderAggregateSearch(searchSource = { _, id -> listOf(
            book(id).copy(detailUrl = post),
            book(id).copy(id = "missing", detailUrl = "https://actual.example/book?resourceId="),
            book(id).copy(id = "null", detailUrl = "https://actual.example/book?bookId=null"),
            book(id).copy(id = "bad", detailUrl = "https://actual.example/book,{invalid}"),
        ) })
        assertEquals(post, search.search("同名作品", 0, listOf(ReaderSearchSource("actual"))).single().detailUrl)
    }

    @Test fun workersAndPerHostRequestsAreBoundedAndEachRoundHasFiniteBudget() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val hostActive = ConcurrentHashMap<String, AtomicInteger>()
        val hostMaximum = ConcurrentHashMap<String, AtomicInteger>()
        val descriptors = (0 until 24).map { ReaderSearchSource("s$it", host = "host${it % 3}.example") }
        val byId = descriptors.associateBy { it.id }
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            val host = byId.getValue(id).host
            val current = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, current) }
            val currentHost = hostActive.getOrPut(host) { AtomicInteger() }.incrementAndGet()
            hostMaximum.getOrPut(host) { AtomicInteger() }.updateAndGet { maxOf(it, currentHost) }
            try { delay(200); listOf(book(id)) }
            finally { active.decrementAndGet(); hostActive.getValue(host).decrementAndGet() }
        }, maxParallel = 3, sourceTimeoutMs = 30, totalTimeoutMs = 110)
        var final: ReaderSearchProgress? = null
        withTimeout(1_500) { search.search("同名作品", 0, descriptors) { final = it } }
        assertTrue(maximum.get() <= 3)
        assertTrue(hostMaximum.values.all { it.get() <= 1 })
        assertEquals(0, active.get())
        assertTrue(final!!.finished)
        assertEquals(24, final!!.eligibleSources)
        assertEquals(24, final!!.scheduledSources)
        assertTrue(final!!.completedSources < final!!.scheduledSources)
    }

    @Test fun callerCancellationStopsOutstandingSearchesAndDoesNotPublishFinishedSnapshot() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val finished = AtomicInteger()
        val search = ReaderAggregateSearch(searchSource = { _, _ ->
            active.incrementAndGet()
            started.complete(Unit)
            try { delay(5_000); emptyList() } finally { active.decrementAndGet() }
        })
        val job = launch {
            search.search("同名作品", 0, (0 until 20).map { ReaderSearchSource("s$it") }) {
                if (it.finished) finished.incrementAndGet()
            }
        }
        withTimeout(1_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(0, active.get())
        assertEquals(0, finished.get())
    }

    @Test fun overlappingRoundsShareTheSameRequestLimit() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try { delay(30); listOf(book(id)) } finally { active.decrementAndGet() }
        }, maxParallel = 2)
        val sources = (0 until 4).map { ReaderSearchSource("s$it") }
        val first = async { search.search("同名", 0, sources) }
        val second = async { search.search("作品", 0, sources) }
        withTimeout(2_000) { first.await(); second.await() }
        assertTrue(maximum.get() <= 2)
        assertEquals(0, active.get())
    }

    @Test fun failedSourcesCoolDownThenRecoverAndSuccessfulSearchesUseMetadataCache() = runBlocking {
        var now = 1_000L
        var broken = true
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls += id
            if (broken) throw IOException("unavailable")
            listOf(book(id))
        }, clock = { now })
        val sources = listOf(ReaderSearchSource("test"))
        assertTrue(search.search("同名作品", 0, sources).isEmpty())
        broken = false
        assertTrue(search.search("同名作品", 0, sources).isEmpty())
        assertEquals(1, calls.size)
        now += 10_001
        assertEquals(1, search.search("同名作品", 0, sources).size)
        assertEquals(2, calls.size)
        assertEquals(1, search.search("同名作品", 0, sources).size)
        assertEquals(2, calls.size)
    }

    @Test fun learnedPriorityChoosesAnActualMatchAheadOfFasterEmptySourcesForTheNextQuery() = runBlocking {
        val now = AtomicLong(1_000)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val search = ReaderAggregateSearch(searchSource = { query, id ->
            calls += id
            now.addAndGet(if (id == "useful") 200 else 1)
            if (id == "useful") listOf(book(id, query)) else emptyList()
        }, clock = now::get, maxParallel = 1, maxSourcesPerSearch = 1)
        val ids = (0 until 12).map { "empty$it" } + "useful"
        ids.forEach { search.search("预热作品", 0, listOf(ReaderSearchSource(it))) }
        assertEquals(100, search.learnedPriority("useful"))
        assertTrue(ids.filter { it != "useful" }.all { search.learnedPriority(it) == 10 })
        calls.clear()
        val candidates = ids.map { ReaderSearchSource(it, search.learnedPriority(it)) }
        val result = search.search("下次查询", 0, candidates)
        assertEquals(listOf("useful"), calls.toList())
        assertEquals("useful", result.single().sourceId)
    }

    @Test fun successfulEmptySourcesStillRotateAndRegainFullPriorityWhenAnotherQueryHasResults() = runBlocking {
        val now = AtomicLong(1_000)
        val calls = ConcurrentHashMap<String, MutableSet<String>>()
        var newlyUseful: String? = null
        val search = ReaderAggregateSearch(searchSource = { query, id ->
            calls.getOrPut(query) { ConcurrentHashMap.newKeySet() } += id
            now.addAndGet(if (id == "useful") 200 else 1)
            if (id == "useful" || id == newlyUseful) listOf(book(id, query)) else emptyList()
        }, clock = now::get, maxParallel = 1, maxSourcesPerSearch = 9)
        val emptyIds = (0 until 12).map { "empty$it" }
        val ids = emptyIds + "useful"
        ids.forEach { search.search("预热作品", 0, listOf(ReaderSearchSource(it))) }
        val candidates = ids.map { ReaderSearchSource(it, search.learnedPriority(it)) }
        val rounds = (0 until 5).map { round ->
            val query = "后续查询$round"
            assertEquals("useful", search.search(query, 0, candidates).single().sourceId)
            calls.getValue(query).toSet()
        }
        assertTrue(rounds.all { it.size == 9 && "useful" in it })
        assertEquals(emptyIds.toSet(), rounds.flatten().filter { it != "useful" }.toSet())
        assertTrue(emptyIds.all { search.learnedPriority(it) == 10 })
        val recoveredId = emptyIds.last()
        newlyUseful = recoveredId
        val recovered = search.search("另一查询", 0, listOf(ReaderSearchSource(recoveredId,
            search.learnedPriority(recoveredId)))).single()
        assertEquals(recoveredId, recovered.sourceId)
        assertEquals(100, search.learnedPriority(recoveredId))
    }

    @Test fun largeLibrariesRotateWithoutClaimingUnsearchedSourcesWereSearched() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val search = ReaderAggregateSearch(searchSource = { _, id -> calls += id; emptyList() }, maxSourcesPerSearch = 3)
        val sources = (0 until 12).map { ReaderSearchSource("s$it") }
        val snapshots = mutableListOf<ReaderSearchProgress>()
        repeat(4) { round -> search.search("作品$round", 0, sources) { if (it.finished) snapshots += it } }
        assertEquals(12, calls.distinct().size)
        assertTrue(snapshots.all { it.eligibleSources == 12 && it.scheduledSources == 3 && it.completedSources == 3 })
        assertTrue(snapshots.all { it.searchedSourceIds.size == 3 })
    }

    @Test fun automaticRoundsReachTheWholeLibraryWithoutDependingOnThe96EntryCache() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet(); listOf(book(id))
        }, maxSourcesPerSearch = 7)
        val snapshots = mutableListOf<ReaderSearchProgress>()
        val result = withTimeout(5_000) {
            search.searchAll("同名作品", 0, (0 until 120).map { ReaderSearchSource("s$it") }) { snapshots += it }
        }
        assertEquals(120, calls.size)
        assertTrue(calls.values.all { it.get() == 1 })
        assertEquals(120, result.single().sourceCount)
        assertTrue(snapshots.all { it.eligibleSources == 120 && it.scheduledSources == 120 })
        assertTrue(snapshots.any { it.books.isNotEmpty() && it.completedSources < 7 && !it.finished })
        assertEquals(120, snapshots.last().completedSources)
        assertEquals(1, snapshots.filter { it.finished }.size)
        assertEquals(1, snapshots.filter { it.books.isNotEmpty() }.map { it.books.single().id }.distinct().size)
    }

    @Test fun roundDeadlinesCountStartedRequestsAndUntouchedTailStillRunsOnce() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet(); delay(5_000); emptyList()
        }, maxParallel = 1, sourceTimeoutMs = 4_000, totalTimeoutMs = 200, maxSourcesPerSearch = 3)
        var final: ReaderSearchProgress? = null
        withTimeout(2_000) {
            search.searchAll("同名作品", 0, (0 until 4).map { ReaderSearchSource("s$it") }) { final = it }
        }
        assertEquals(4, calls.size)
        assertTrue(calls.values.all { it.get() == 1 })
        assertEquals(4, final!!.completedSources)
        assertEquals(4, final!!.errors.size)
        assertTrue(final!!.finished)
    }

    @Test fun pausedSearchResumesTheUnfinishedRequestAndUntouchedSourcesWhileKeepingCompletedHits() = runBlocking {
        val startedSlow = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        var snapshot: ReaderSearchProgress? = null
        val sources = (0 until 5).map { ReaderSearchSource("s$it", priority = 10 - it) }
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            if (id == "s1" && calls.getValue(id).get() == 1) { startedSlow.complete(Unit); delay(5_000) }
            listOf(book(id))
        }, maxParallel = 1, sourceTimeoutMs = 4_000, totalTimeoutMs = 8_000, maxSourcesPerSearch = 2)
        val job = launch { search.searchAll("同名作品", 0, sources) { snapshot = it } }
        withTimeout(1_000) {
            startedSlow.await()
            while (snapshot?.startedSourceIds?.contains("s1") != true) delay(1)
        }
        job.cancelAndJoin()
        assertNotNull(snapshot)
        val result = withTimeout(2_000) { search.searchAll("同名作品", 0, sources, previous = snapshot) }
        assertEquals(5, calls.size)
        assertEquals(2, calls.getValue("s1").get())
        assertTrue(calls.filterKeys { it != "s1" }.values.all { it.get() == 1 })
        // A canceled in-flight request stays pending; its successful retry is a real hit.
        assertEquals(5, result.single().sourceCount)
    }

    @Test fun deferredHealthIsReportedAsPendingAndExplicitContinueCanRetryOnlyThatTail() = runBlocking {
        var broken = true
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            if (id == "bad" && broken) throw IOException("unavailable")
            listOf(book(id))
        }, clock = { 1_000L }, maxSourcesPerSearch = 1)
        search.search("先前关键词", 0, listOf(ReaderSearchSource("bad")))
        broken = false
        val sources = listOf(ReaderSearchSource("bad"), ReaderSearchSource("good"))
        var final: ReaderSearchProgress? = null
        search.searchAll("同名作品", 0, sources) { final = it }
        assertEquals(1, final!!.completedSources)
        assertEquals(2, final!!.eligibleSources)
        val result = search.searchAll("同名作品", 0, sources, previous = final, retryDeferred = true)
        assertEquals(2, result.single().sourceCount)
        assertEquals(1, calls.getValue("good").get())
        assertEquals(2, calls.getValue("bad").get())
    }
}
