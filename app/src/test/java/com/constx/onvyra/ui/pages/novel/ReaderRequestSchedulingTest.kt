package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Scheduling guarantees rather than assertions about a particular book/site. */
class ReaderRequestSchedulingTest {
    @Test fun verifiedCandidateRunsBeforeAlreadyQueuedSpeculativeSearches() = runBlocking {
        val gate = ReaderPriorityGate(1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val occupying = launch { gate.withPermit(false) { started.complete(Unit); release.await() } }
        started.await()
        val discovery = launch { gate.withPermit(false) { order += "discovery" } }
        yield()
        val verification = launch { gate.withPermit(true) { order += "verification" } }
        yield()
        release.complete(Unit)
        joinAll(occupying, discovery, verification)
        assertEquals(listOf("verification", "discovery"), order)
    }

    @Test fun cancelledQueuedChecksDoNotLeakASlotOrBlockFollowingRequests() = runBlocking {
        val gate = ReaderPriorityGate(1)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val occupying = launch { gate.withPermit(false) { started.complete(Unit); release.await() } }
        started.await()
        val cancelled = launch { gate.withPermit(true) { fail("Cancelled queued request must not execute") } }
        yield(); cancelled.cancelAndJoin()
        val next = async { gate.withPermit(false) { 42 } }
        yield(); release.complete(Unit)
        assertEquals(42, withTimeout(1_000) { next.await() })
        occupying.join()
        assertEquals(43, withTimeout(1_000) { gate.withPermit(true) { 43 } })
    }

    @Test fun overlappingVerificationAndMetadataNeverExceedTheTenSharedSlots() = runBlocking {
        val gate = ReaderPriorityGate(10)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val full = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val requests = List(60) { index -> launch(Dispatchers.Default) {
                gate.withPermit(index % 3 == 0) {
                    val value = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, value) }
                    if (value == 10) full.complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
            } }
            try { withTimeout(2_000) { full.await() } }
            finally { release.complete(Unit) }
            requests.joinAll()
        }
        assertEquals(10, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun aSourcesExecutionBudgetDoesNotExpireWhileItsCheckWaitsInTheQueue() = runBlocking {
        var now = 0L
        val budget = ReaderActiveBudget(6_000) { now }
        val gate = ReaderPriorityGate(1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val occupying = launch { gate.withPermit(false) { started.complete(Unit); release.await() } }
        started.await()
        val candidate = async { gate.withPermit(true) {
            assertEquals("Waiting behind unrelated HTTP must not exhaust this source's budget", 6_000L, budget.remaining())
            budget.enter()
            try { now += 4_000; budget.remaining() } finally { budget.leave() }
        } }
        yield()
        now += 8_000
        release.complete(Unit)
        assertEquals(2_000L, candidate.await())
        occupying.join()
        now += 20_000
        assertEquals("Inactive time is bounded by the outer work deadline, not charged twice", 2_000L, budget.remaining())
    }

    @Test fun parallelCandidateBudgetCountsElapsedExecutionOnceAndStillExpires() {
        var now = 0L
        val budget = ReaderActiveBudget(6_000) { now }
        budget.enter(); now = 1_000
        budget.enter(); now = 4_000
        budget.leave()
        assertEquals(2_000L, budget.remaining())
        now = 6_100; budget.leave()
        assertEquals(0L, budget.remaining())
    }

    @Test fun phaseTraceIsBoundedAndContainsOnlyExplicitRedactedFields() {
        val trace = ReaderPhaseTrace()
        repeat(300) { trace.record(ReaderSourcePhase("legado_fingerprint", "http", executionMs = it.toLong())) }
        val entries = trace.snapshot()
        assertEquals(256, entries.size)
        assertEquals(44L, entries.first().executionMs)
        assertEquals(299L, entries.last().executionMs)
        trace.clear()
        assertTrue(trace.snapshot().isEmpty())
    }
}
