package com.constx.onvyra.data.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded discovery job per private registry, independent of a particular chat generation. */
internal object MovieCatalogWarmup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<MovieCatalogSourceRegistry, Job>()

    fun start(registry: MovieCatalogSourceRegistry, discover: suspend () -> Unit): Job? {
        if (registry.isDiscoveryCacheFresh()) return null
        val job = synchronized(running) {
            running[registry]?.takeIf { !it.isCompleted && !it.isCancelled }?.let { return it }
            // LAZY ensures the map owns this job before its finally block runs.
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val completed = withTimeoutOrNull(40_000) { discover(); true } == true
                    if (!completed && !registry.isDiscoveryCacheFresh()) registry.recordDiscoveryAttempt(false, emptyList())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Failure must neither break a working search nor cause a
                    // fresh scan for every subsequent chat message.
                    registry.recordDiscoveryAttempt(false, emptyList())
                } finally {
                    val ownJob = currentCoroutineContext()[Job]
                    synchronized(running) { if (running[registry] === ownJob) running.remove(registry) }
                }
            }.also { running[registry] = it }
        }
        job.start()
        return job
    }
}
