package me.rerere.androidcontrol.accessibility

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccessibilityConnectionTest {
    @Test fun transientRebindResumesWaitingOperationWithNewService() = runTest {
        val connection = AccessibilityConnection<Any>()
        val old = Any()
        val replacement = Any()
        connection.connected(old)
        connection.disconnected(old)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.awaitConnected(2_000) }
        assertFalse(pending.isCompleted)
        advanceTimeBy(150)
        connection.connected(replacement)
        assertSame(replacement, pending.await())
    }

    @Test fun oldServiceDestroyCannotRetireReplacementConnection() = runTest {
        val connection = AccessibilityConnection<Any>()
        val old = Any()
        val replacement = Any()
        connection.connected(old)
        connection.connected(replacement)
        connection.disconnected(old)
        assertSame(replacement, connection.current)
        assertSame(replacement, connection.awaitConnected(2_000))
        connection.disconnected(replacement)
        assertNull(connection.current)
    }

    @Test fun aTemporaryFailedProbeRecoversTheSameInstanceWithoutAnotherConnectedCallback() = runTest {
        val connection = AccessibilityConnection<Any>()
        val service = Any()
        var systemBound = false
        connection.connected(service)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            connection.awaitConnected(2_000) { systemBound }
        }
        advanceTimeBy(150)
        assertFalse(pending.isCompleted)
        assertSame("A temporary failed probe must retain the lifecycle owner", service, connection.current)
        systemBound = true
        advanceTimeBy(51)
        runCurrent()
        assertSame(service, pending.await())
    }

    @Test fun aFailedProbeTimesOutWithoutLosingTheInstanceOrReportingItReady() = runTest {
        val connection = AccessibilityConnection<Any>()
        val service = Any()
        connection.connected(service)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.awaitConnected(200) { false } }
        advanceTimeBy(201)
        runCurrent()
        assertNull(pending.await())
        assertSame(service, connection.current)
        assertSame(service, connection.awaitConnected(0) { true })
    }

    @Test fun missingBindTimesOutWithoutLeavingPendingWork() = runTest {
        val connection = AccessibilityConnection<Any>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.awaitConnected(500) }
        advanceTimeBy(501)
        runCurrent()
        assertNull(pending.await())
        val later = Any()
        connection.connected(later)
        assertNull(pending.await())
        assertSame(later, connection.current)
    }

    @Test fun stopCancellationDoesNotResumeTheCanceledOperationOnLateBind() = runTest {
        val connection = AccessibilityConnection<Any>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { connection.awaitConnected(2_000) }
        pending.cancelAndJoin()
        connection.connected(Any())
        runCurrent()
        assertTrue(pending.isCancelled)
        assertNotNull(connection.current)
    }
}
