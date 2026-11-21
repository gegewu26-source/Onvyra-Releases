package com.constx.onvyra.ui.pages.novel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cross-instance contracts: a progress counter or metadata match alone is insufficient. */
class ReaderSourceDiscoveryPersistenceContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = 1_000_000L
    private fun store() = ReaderSourceDiscoveryStore(temporary.root) { clock }
    private fun book(source: String = "source-0") = NovelBook("work-$source", "持久查源验证作品", "验证作者",
        detailUrl = "https://$source.fixture.test/book/1", sourceId = source)
    private fun progress(book: NovelBook, searched: Set<String>, total: Int, started: Set<String> = emptySet()) =
        ReaderSearchProgress(listOf(book), searched.size, total, total, 0, searched.size >= total,
            searchedSourceIds = searched, startedSourceIds = started)
    private fun status(book: NovelBook, state: String, at: Long = clock, reason: String = "") =
        ReaderSourceAvailability(book.sourceId, book.detailUrl, state, at, chapterTitle = "第一章 初见",
            chapterUrl = "https://${book.sourceId}.fixture.test/chapter/1", chapterIndex = 0, reason = reason)

    @Test fun reopeningANewStoreAndSearchEngineRequestsOnlyTheActualUntouchedIdsOf433Sources() = runBlocking {
        val directory = temporary.root
        val ids = (0 until 433).map { "source-$it" }.toSet()
        // Deliberately noncontiguous: a persisted count cannot reconstruct these IDs.
        val searched = ids.filter { it.substringAfterLast('-').toInt() % 3 == 0 }.toSet()
        val started = setOf("source-1")
        val saved = book().copy(sources = listOf(book("source-3").asSource()))
        ReaderSourceDiscoveryStore(directory) { clock }.save(saved,
            progress(saved, searched, ids.size, started), ids, emptyMap())

        val restored = ReaderSourceDiscoveryStore(directory) { clock }.restore(book(), ids)
        assertEquals(searched, restored.progress.searchedSourceIds)
        assertEquals(ids.size, restored.progress.eligibleSources)
        assertFalse(restored.progress.finished)
        assertEquals(setOf("source-0", "source-3"), restored.book.sources.map { it.sourceId }.toSet())
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val freshSearch = ReaderAggregateSearch(searchSource = { _, id ->
            requests.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            emptyList()
        })
        var final: ReaderSearchProgress? = null
        freshSearch.searchAll(saved.title, 0, ids.map { ReaderSearchSource(it) }, previous = restored.progress) { final = it }
        assertEquals(ids - searched, requests.keys)
        assertTrue(requests.values.all { it.get() == 1 })
        assertEquals(ids, final!!.searchedSourceIds)
        assertTrue(final!!.finished)
    }

    @Test fun aStartedButCancelledRequestDoesNotBecomeAUsableCandidateAcrossRecreation() {
        val main = book()
        val matched = book("source-1")
        val saved = main.copy(sources = listOf(matched.asSource()))
        val ids = setOf(main.sourceId, matched.sourceId, "source-2")
        store().save(saved, progress(saved, setOf(main.sourceId), ids.size, setOf(matched.sourceId)), ids,
            mapOf(status(matched, ReaderSourceAvailability.MATCHED).let { it.routeKey to it }))

        val restored = store().restore(main, ids)
        assertEquals(setOf(main.sourceId), restored.progress.searchedSourceIds)
        assertFalse(restored.progress.finished)
        assertEquals(ReaderSourceAvailability.MATCHED,
            restored.availability.getValue(ReaderSourceAvailability.key(matched.sourceId, matched.detailUrl)).status)
        assertFalse(restored.availability.values.any { it.status == ReaderSourceAvailability.USABLE })
    }

    @Test fun disabledOrChangedRuleIdsLoseTheirCandidatesStatusesAndProgressWhileNewIdsRemainPending() {
        val main = book()
        val disabled = book("source-1")
        val changedRule = book("source-2-old-rule-hash")
        val saved = main.copy(sources = listOf(disabled.asSource(), changedRule.asSource()))
        val oldIds = setOf(main.sourceId, disabled.sourceId, changedRule.sourceId)
        val statuses = listOf(status(disabled, ReaderSourceAvailability.USABLE), status(changedRule, ReaderSourceAvailability.FAILED))
        store().save(saved, progress(saved, oldIds, oldIds.size), oldIds, statuses.associateBy { it.routeKey })

        val currentIds = setOf(main.sourceId, "source-2-new-rule-hash")
        val restored = store().restore(saved, currentIds)
        assertEquals(listOf(main.sourceId), restored.book.sources.map { it.sourceId })
        assertEquals(setOf(main.sourceId), restored.progress.searchedSourceIds)
        assertEquals(2, restored.progress.eligibleSources)
        assertFalse(restored.progress.finished)
        assertTrue(restored.availability.isEmpty())
    }

    @Test fun sameTitleDifferentAuthorAndMediaNeverReuseTheStoredSearchOrAvailability() {
        val original = book()
        val ids = setOf(original.sourceId)
        val available = status(original, ReaderSourceAvailability.USABLE)
        store().save(original, progress(original, ids, ids.size), ids, mapOf(available.routeKey to available))
        val unrelated = listOf(original.copy(author = "另一位作者"), original.copy(isComic = true), original.copy(isAudio = true))
        unrelated.forEach { requested ->
            val restored = store().restore(requested, ids)
            assertTrue(restored.progress.searchedSourceIds.isEmpty())
            assertTrue(restored.availability.isEmpty())
            assertEquals(ReaderSearchPolicy.kind(requested), ReaderSearchPolicy.kind(restored.book))
        }
    }

    @Test fun snapshotNeverOverwritesTheCurrentManualRouteOrSavedChapterAndDoesNotReviveAFailedRoute() {
        val original = book()
        val selected = book("source-1").copy(preferredSourceId = "source-1", lastReadChapterIndex = 8,
            lastReadChapterTitle = "第九章 远行", lastReadChapterUrl = "https://source-1.fixture.test/chapter/9")
        val failed = status(selected, ReaderSourceAvailability.FAILED, reason = "对应章 HTTP 404")
        val ids = setOf(original.sourceId, selected.sourceId)
        store().save(original.copy(sources = listOf(selected.asSource())), progress(original, ids, ids.size), ids,
            mapOf(failed.routeKey to failed))

        val restored = store().restore(selected, ids)
        assertEquals(selected.sourceId, restored.book.sourceId)
        assertEquals(selected.preferredSourceId, restored.book.preferredSourceId)
        assertEquals(selected.lastReadChapterIndex, restored.book.lastReadChapterIndex)
        assertEquals(selected.lastReadChapterTitle, restored.book.lastReadChapterTitle)
        assertEquals(selected.lastReadChapterUrl, restored.book.lastReadChapterUrl)
        assertEquals(ReaderSourceAvailability.FAILED, restored.availability.getValue(failed.routeKey).status)
        assertEquals("对应章 HTTP 404", restored.availability.getValue(failed.routeKey).reason)
    }

    @Test fun validationExpiryRequiresARecheckWithoutRepeatingFreshMetadataAndScanExpiryKeepsCandidates() {
        val main = book()
        val ids = setOf(main.sourceId)
        val available = status(main, ReaderSourceAvailability.USABLE)
        store().save(main, progress(main, ids, ids.size), ids, mapOf(available.routeKey to available))
        clock += 6L * 60 * 60 * 1_000
        val needsValidation = store().restore(main, ids)
        assertEquals(ids, needsValidation.progress.searchedSourceIds)
        assertEquals(ReaderSourceAvailability.MATCHED, needsValidation.availability.getValue(available.routeKey).status)

        clock += 18L * 60 * 60 * 1_000
        val needsMetadata = store().restore(main, ids)
        assertTrue(needsMetadata.progress.searchedSourceIds.isEmpty())
        assertEquals(listOf(main.sourceId), needsMetadata.book.sources.map { it.sourceId })
        assertFalse(needsMetadata.progress.finished)
    }

    @Test fun aLateOlderSnapshotCannotRollBackCompletedIdsCandidatesOrANewerFailedVerification() {
        val main = book()
        val alternative = book("source-1")
        val ids = setOf(main.sourceId, alternative.sourceId, "source-2")
        val cached = store()
        val newer = status(alternative, ReaderSourceAvailability.FAILED, at = clock + 100, reason = "真实目录失败")
        val older = status(alternative, ReaderSourceAvailability.USABLE, at = clock)
        val latestBook = main.copy(sources = listOf(alternative.asSource()))
        cached.save(latestBook, progress(latestBook, setOf(main.sourceId, alternative.sourceId), ids.size), ids,
            mapOf(newer.routeKey to newer))
        cached.save(main, progress(main, setOf(main.sourceId), ids.size), ids, mapOf(older.routeKey to older))

        val restored = store().restore(main, ids)
        assertEquals(setOf(main.sourceId, alternative.sourceId), restored.progress.searchedSourceIds)
        assertTrue(restored.book.sources.any { it.sourceId == alternative.sourceId })
        assertEquals(ReaderSourceAvailability.FAILED, restored.availability.getValue(newer.routeKey).status)
    }

    @Test fun anExplicitResetDiscardsCompletedIdsAndFailureStatusForANewScan() {
        val main = book()
        val ids = setOf(main.sourceId, "source-1")
        val failure = status(main, ReaderSourceAvailability.FAILED)
        val cached = store()
        cached.save(main, progress(main, ids, ids.size), ids, mapOf(failure.routeKey to failure))
        cached.save(main, progress(main, emptySet(), ids.size), ids, emptyMap(), reset = true)
        val restored = store().restore(main, ids)
        assertTrue(restored.progress.searchedSourceIds.isEmpty())
        assertTrue(restored.availability.isEmpty())
        assertFalse(restored.progress.finished)
    }
}
