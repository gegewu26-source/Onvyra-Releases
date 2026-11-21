// Adapted from Mika (wangzan101/Mika), revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Discovers only this phone's adbd endpoints. Callers always connect through loopback. */
class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    @Volatile var lastDiagnostics: String = ""
        private set

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> =
        discoverPorts(serviceType, timeoutMs, firstOnly = false)

    /** A connection needs one verified local endpoint, without the list-collection settle delay. */
    suspend fun findFirstPort(serviceType: String, timeoutMs: Long): Int? =
        discoverPorts(serviceType, timeoutMs, firstOnly = true).firstOrNull()

    private suspend fun discoverPorts(serviceType: String, timeoutMs: Long, firstOnly: Boolean): List<Int> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(0,60_000)
        val counts = Counts()
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        var discoveryStarted = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { counts.found.incrementAndGet(); services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { counts.discoveryFailure = code; services.close() }
            override fun onStopDiscoveryFailed(type: String, code: Int) { counts.discoveryStopFailure = code }
        }
        val ports = LinkedHashSet<Int>()
        try {
            if (timeoutMs <= 0) return emptyList()
            nsd.discoverServices(serviceType,NsdManager.PROTOCOL_DNS_SD,listener)
            discoveryStarted = true
            var settleUntil = deadline
            while (true) {
                val left = nsdRemainingBudget(minOf(deadline,settleUntil),SystemClock.elapsedRealtime(),60_000)
                if (left <= 0) break
                val info = withTimeoutOrNull(left) { services.receiveCatching().getOrNull() } ?: break
                val port = withTimeoutOrNull(left) {
                    resolveLock.withLock {
                        // Waiting for another caller's cleanup also consumes this request's budget.
                        resolve(info,nsdRemainingBudget(minOf(deadline,settleUntil),SystemClock.elapsedRealtime(),RESOLVE_MS),counts)
                    }
                }
                if (port != null) {
                    ports += port
                    if (firstOnly) break
                    settleUntil = minOf(settleUntil,SystemClock.elapsedRealtime()+SETTLE_MS)
                }
                if (counts.cleanupUnconfirmed) break
            }
        } catch (cancelled: CancellationException) {
            counts.cancelled = true
            throw cancelled
        } catch (_: Exception) {
            counts.startOrResolveException = true
        } finally {
            if (discoveryStarted) runCatching { nsd.stopServiceDiscovery(listener) }
            services.close()
            val kind = when (serviceType) { AdbServiceTypes.PAIRING -> "PAIRING"; AdbServiceTypes.CONNECT -> "CONNECT"; else -> "OTHER" }
            lastDiagnostics = "kind=$kind found=${counts.found.get()} resolved=${counts.resolved.get()} addresses=${counts.addresses.get()} " +
                "local=${counts.local.get()} accepted=${ports.size} resolve_failures=${counts.failureCodes.sorted()} " +
                "resolve_timeouts=${counts.timeouts.get()} cleanup_unconfirmed=${counts.cleanupUnconfirmed} " +
                "discovery_failure=${counts.discoveryFailure} discovery_stop_failure=${counts.discoveryStopFailure} " +
                "exception=${counts.startOrResolveException} cancelled=${counts.cancelled} budget_exhausted=${SystemClock.elapsedRealtime()>=deadline}"
            // Counts and platform error numbers only: no host, service name, endpoint or pairing code.
            Log.i(TAG,lastDiagnostics)
        }
        return ports.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo, timeoutMs: Long, counts: Counts): Int? {
        val stopped = CompletableDeferred<Unit>()
        var finish: (Int?) -> Unit = {}
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                counts.failureCodes += errorCode
                stopped.complete(Unit)
                finish(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                counts.resolved.incrementAndGet()
                val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                counts.addresses.addAndGet(addresses.size)
                val locals = runCatching { NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }.toList() }.getOrDefault(emptyList())
                val local = nsdAddressesAreLocal(addresses,locals)
                if (local) counts.local.incrementAndGet()
                stopped.complete(Unit)
                finish(info.port.takeIf { local && it in 1..65535 })
            }
            override fun onResolutionStopped(info: NsdServiceInfo) { stopped.complete(Unit) }
            override fun onStopResolutionFailed(info: NsdServiceInfo, errorCode: Int) {
                counts.failureCodes += errorCode
                // A failure does not prove that a timed-out native request was released.
                if (!stopped.isCompleted) counts.cleanupUnconfirmed = true
                stopped.complete(Unit)
            }
        }
        return awaitNsdResolution(timeoutMs,start = { complete ->
            finish = complete
            nsd.resolveService(info,listener)
        },stop = {
            if (Build.VERSION.SDK_INT >= 34) {
                val requested = runCatching { nsd.stopServiceResolution(listener) }.isSuccess
                if (requested && withTimeoutOrNull(STOP_MS) { stopped.await(); true } != true) counts.cleanupUnconfirmed = true
                if (!requested && !stopped.isCompleted) counts.cleanupUnconfirmed = true
            } else {
                // No cancellation API before Android 14. Do not overlap a timed-out native request.
                counts.cleanupUnconfirmed = true
            }
        },onTimeout = { counts.timeouts.incrementAndGet() })
    }

    private class Counts {
        val found = AtomicInteger(); val resolved = AtomicInteger(); val addresses = AtomicInteger(); val local = AtomicInteger(); val timeouts = AtomicInteger()
        val failureCodes = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var discoveryFailure: Int? = null
        @Volatile var discoveryStopFailure: Int? = null
        @Volatile var cleanupUnconfirmed = false
        @Volatile var startOrResolveException = false
        @Volatile var cancelled = false
    }

    private companion object {
        val resolveLock = Mutex()
        const val TAG = "AndroidControlNsd"
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
        const val STOP_MS = 300L
    }
}
