package com.constx.onvyra.data.media

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogSchedulingTest {
    private fun source(id: String) = MovieCatalogSource(id, id, "https://$id.example/api")

    @Test fun preferredCatalogPublishesBeforeIndependentSourcesAndIsQueriedOnlyOnce() = runBlocking {
        val scheduling = MovieCatalogScheduling()
        val called = mutableListOf<String>()
        scheduling.runSources(listOf(source("other"), source("wsyzy"), source("tail"))) {
            called += it.id
        }
        assertEquals("wsyzy", called.first())
        assertEquals(1, called.count { it == "wsyzy" })
        assertEquals(setOf("other", "wsyzy", "tail"), called.toSet())
    }

    @Test fun slowPreferredCatalogDoesNotBlockFallbackPastTheShortWindow() = runBlocking {
        val scheduling = MovieCatalogScheduling(preferredWindowMs = 40)
        val release = CompletableDeferred<Unit>()
        val fallbackStarted = CompletableDeferred<Unit>()
        val called = Collections.synchronizedList(mutableListOf<String>())
        val job = launch {
            scheduling.runSources(listOf(source("wsyzy"), source("other"))) {
                called += it.id
                if (it.id == "wsyzy") release.await() else fallbackStarted.complete(Unit)
            }
        }
        try {
            withTimeout(2_000) { fallbackStarted.await() }
            assertFalse("Preferred request may continue without hiding fallback", release.isCompleted)
            assertEquals(listOf("wsyzy", "other"), called.toList())
        } finally { release.complete(Unit); job.join() }
    }

    @Test fun explicitSubsetNeverAddsThePreferredCatalog() = runBlocking {
        val called = mutableListOf<String>()
        MovieCatalogScheduling().runSources(listOf(source("selected"))) { called += it.id }
        assertEquals(listOf("selected"), called)
    }

    @Test fun overlappingSearchesShareTwelveSlotsRatherThanMultiplyingTheLimit() = runBlocking {
        val scheduling = MovieCatalogScheduling()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val full = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = List(40) { index -> launch(Dispatchers.Default) {
                scheduling.withRequest(index.toString()) {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    if (count == 12) full.complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
            } }
            try { withTimeout(2_000) { full.await() } }
            finally { release.complete(Unit) }
            jobs.joinAll()
        }
        assertEquals(12, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun aliasesOfOneHostDoNotOccupyGlobalSlotsWhileTheyWait() = runBlocking {
        val scheduling = MovieCatalogScheduling(maxParallel = 3, maxPerHost = 1)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val occupying = launch { scheduling.withRequest("same.example") { held.complete(Unit); release.await() } }
        held.await()
        val alias = launch { scheduling.withRequest("same.example") { } }
        yield()
        val independent = async { scheduling.withRequest("different.example") { 42 } }
        try { assertEquals(42, withTimeout(1_000) { independent.await() }) }
        finally { release.complete(Unit); joinAll(occupying, alias) }
    }

    @Test fun sameHostRequestsAreBoundedAtTwoEvenWithDifferentCatalogIds() = runBlocking {
        val scheduling = MovieCatalogScheduling()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val full = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = List(12) { launch(Dispatchers.Default) {
                scheduling.withRequest("one.example") {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    if (count == 2) full.complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
            } }
            try { withTimeout(2_000) { full.await() } } finally { release.complete(Unit) }
            jobs.joinAll()
        }
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun waitingForHostAndGlobalSlotsDoesNotConsumeTheSourcesOwnExecutionBudget() = runBlocking {
        val scheduling = MovieCatalogScheduling(maxParallel = 1, maxPerHost = 1)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val occupying = launch { scheduling.withRequest("one.example") { held.complete(Unit); release.await() } }
        held.await()
        val waiting = async {
            scheduling.withRequest("one.example") { withTimeout(50) { delay(1); 42 } }
        }
        delay(100)
        release.complete(Unit)
        assertEquals(42, withTimeout(1_000) { waiting.await() })
        occupying.join()
    }
}
