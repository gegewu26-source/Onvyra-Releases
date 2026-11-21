package me.rerere.androidcontrol.adb

import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only an exact local interface address or loopback may identify this phone's adbd service. */
internal fun nsdAddressesAreLocal(addresses: List<InetAddress>, localAddresses: List<InetAddress>): Boolean =
    addresses.any { candidate -> candidate.isLoopbackAddress || localAddresses.any { it == candidate } }

internal fun nsdRemainingBudget(deadline: Long, now: Long, cap: Long): Long =
    (deadline - now).coerceAtLeast(0).coerceAtMost(cap.coerceAtLeast(0))

/** A cancelled waiter must finish backend cleanup before another legacy resolution starts. */
internal suspend fun <T> awaitNsdResolution(
    timeoutMs: Long,
    start: ((T?) -> Unit) -> Unit,
    stop: suspend () -> Unit,
    onTimeout: () -> Unit,
): T? {
    if (timeoutMs <= 0) return null
    val result = CompletableDeferred<T?>()
    var started = false
    try {
        start { result.complete(it) }
        started = true
        val value = withTimeoutOrNull(timeoutMs) { result.await() }
        if (!result.isCompleted) onTimeout()
        return value
    } finally {
        if (started && !result.isCompleted) withContext(NonCancellable) { stop() }
    }
}
