package me.rerere.androidcontrol.adb

import java.net.InetAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NsdDiscoveryPolicyTest {
    private fun address(value: String) = InetAddress.getByName(value)

    @Test fun aSecondaryResolvedAddressCanIdentifyTheLocalPhone() {
        val local = address("192.168.103.227")
        assertTrue(nsdAddressesAreLocal(listOf(address("192.168.103.99"), local), listOf(local)))
        val vpn = address("10.223.78.241")
        assertTrue(nsdAddressesAreLocal(listOf(vpn), listOf(local, vpn)))
        val ipv6 = address("fe80::1234")
        assertTrue(nsdAddressesAreLocal(listOf(ipv6), listOf(ipv6)))
    }

    @Test fun arbitraryLanServicesAreRejectedAndLoopbackIsAccepted() {
        assertFalse(nsdAddressesAreLocal(listOf(address("192.168.1.88")), listOf(address("192.168.1.89"))))
        assertFalse(nsdAddressesAreLocal(emptyList(), listOf(address("192.168.1.89"))))
        assertTrue(nsdAddressesAreLocal(listOf(address("127.0.0.1")), emptyList()))
        assertTrue(nsdAddressesAreLocal(listOf(address("::1")), emptyList()))
    }

    @Test fun resolvingCannotExtendTheRemainingDiscoveryBudget() {
        assertEquals(500L, nsdRemainingBudget(1_000, 500, 1_500))
        assertEquals(1_500L, nsdRemainingBudget(5_000, 500, 1_500))
        assertEquals(0L, nsdRemainingBudget(1_000, 1_001, 1_500))
        assertEquals(0L, nsdRemainingBudget(1_000, 500, -1))
    }

    @Test fun aTerminalNativeResultDoesNotRequestCancellation() = runTest {
        var stopped = false
        var timedOut = false
        assertEquals(1234, awaitNsdResolution<Int>(500, start = { it(1234) },
            stop = { stopped = true }, onTimeout = { timedOut = true }))
        assertNull(awaitNsdResolution<Int>(500, start = { it(null) },
            stop = { stopped = true }, onTimeout = { timedOut = true }))
        assertFalse(stopped)
        assertFalse(timedOut)
    }

    @Test fun timeoutCleansUpBeforeTheNextNativeResolutionCanStart() = runTest {
        val lock = Mutex()
        val events = mutableListOf<String>()
        var late: ((Int?) -> Unit)? = null
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock {
                awaitNsdResolution<Int>(50, start = { late = it; events += "start" }, stop = {
                    events += "stop"
                    delay(20)
                    events += "stopped"
                }, onTimeout = { events += "timeout" })
            }
        }
        val second = async { lock.withLock { events += "next" } }
        assertNull(first.await())
        second.await()
        assertEquals(listOf("start", "timeout", "stop", "stopped", "next"), events)
        // A delayed platform callback cannot turn the already timed-out return into success.
        late!!(1234)
        assertNull(first.await())
    }

    @Test fun callerCancellationStillFinishesBackendCleanup() = runTest {
        var cleanupFinished = false
        var timedOut = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitNsdResolution<Int>(10_000, start = {}, stop = {
                delay(20)
                cleanupFinished = true
            }, onTimeout = { timedOut = true })
        }
        job.cancelAndJoin()
        assertTrue(cleanupFinished)
        assertFalse(timedOut)
    }
}
