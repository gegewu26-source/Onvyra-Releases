package com.constx.onvyra.data.media

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Shared limits include overlapping searches. Host aliases wait before taking a global slot. */
internal class MovieCatalogScheduling(
    maxParallel: Int = 12,
    maxPerHost: Int = 2,
    private val preferredWindowMs: Long = 2_500,
) {
    private val requests = Semaphore(maxParallel)
    // Fixed-size host stripes keep an untrusted catalog registry from growing a lock map.
    private val hostRequests = List(128) { Semaphore(maxPerHost) }

    init {
        require(maxParallel in 1..12 && maxPerHost in 1..maxParallel)
        require(preferredWindowMs > 0)
    }

    suspend fun <T> withRequest(host: String, block: suspend () -> T): T {
        val gate = hostRequests[(host.lowercase(java.util.Locale.ROOT).hashCode() and Int.MAX_VALUE) % hostRequests.size]
        return gate.withPermit { requests.withPermit { block() } }
    }

    /**
     * Give the requested preferred catalog one short head start, never a second query.
     * A slow preferred request continues within the caller's source/round deadlines while
     * independent catalogs start after the head-start window. Explicit subsets stay exact.
     */
    suspend fun runSources(sources: List<MovieCatalogSource>, query: suspend (MovieCatalogSource) -> Unit) = coroutineScope {
        val preferred = sources.firstOrNull { it.id == "wsyzy" }
        if (preferred != null) {
            val first = launch { query(preferred) }
            withTimeoutOrNull(preferredWindowMs) { first.join() }
        }
        sources.filterNot { it === preferred }.forEach { source -> launch { query(source) } }
    }
}
