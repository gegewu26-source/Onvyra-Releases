package com.constx.onvyra.ui.components.message

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SharedVideoSourceLookupTest {
    private val first = listOf(VideoSourceOption("a", "A", true))
    private val second = first + VideoSourceOption("b", "B")

    @Test fun menuAndRecoverySubscribersShareOneProducerAndCompletedSnapshotIsReusable() = runBlocking {
        val starts = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val lookup = SharedVideoSourceLookup(this, flow {
            starts.incrementAndGet()
            emit(first)
            release.await()
            emit(second)
        })
        try {
            assertEquals(first, withTimeout(2_000) { lookup.updates.first() })
            val menu = async { lookup.updates.lastOrNull() }
            val recovery = async { lookup.updates.lastOrNull() }
            release.complete(Unit)
            assertEquals(second, withTimeout(2_000) { menu.await() })
            assertEquals(second, withTimeout(2_000) { recovery.await() })
            assertEquals(second, lookup.updates.lastOrNull())
            assertEquals(1, starts.get())
        } finally { lookup.cancel() }
    }

    @Test fun timeoutAfterPartialResultsIsVisibleToAllSubscribersRatherThanReportedComplete() = runBlocking {
        val lookup = SharedVideoSourceLookup(this, flow { emit(first); awaitCancellation() }, budgetMs = 80)
        try {
            assertEquals(first, withTimeout(2_000) { lookup.updates.first() })
            repeat(2) {
                val failure = runCatching { withTimeout(2_000) { lookup.updates.lastOrNull() } }.exceptionOrNull()
                assertTrue("Partial lookup timeout must offer retry", failure is IOException)
            }
        } finally { lookup.cancel() }
    }

    @Test fun upstreamFailurePreservesOriginalCauseAndDoesNotRestartOnAnotherSubscriber() = runBlocking {
        val starts = AtomicInteger()
        val expected = IOException("fixture failure")
        val lookup = SharedVideoSourceLookup(this, flow {
            starts.incrementAndGet(); emit(first); throw expected
        })
        try {
            repeat(2) {
                val failure = runCatching { lookup.updates.lastOrNull() }.exceptionOrNull()
                // Coroutine stacktrace recovery may copy an IOException across suspensions.
                assertTrue(failure is IOException)
                assertEquals(expected.message, failure?.message)
            }
            assertEquals(1, starts.get())
        } finally { lookup.cancel() }
    }
}
