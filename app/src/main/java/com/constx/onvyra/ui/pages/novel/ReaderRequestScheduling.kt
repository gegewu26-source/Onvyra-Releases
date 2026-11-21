package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CompletableDeferred

/** Redacted timing evidence only: source fingerprints and fixed phase names. */
data class ReaderSourcePhase(val sourceId: String, val phase: String,
    val queueMs: Long = 0, val executionMs: Long = 0, val status: String = "ok", val startedAtMs: Long = 0)

internal class ReaderPhaseTrace {
    private val entries = ArrayDeque<ReaderSourcePhase>()
    @Synchronized fun record(value: ReaderSourcePhase) {
        if (entries.size == 256) entries.removeFirst()
        entries.addLast(value)
    }
    @Synchronized fun snapshot(): List<ReaderSourcePhase> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}

/** A strict shared IO limit with verification ahead of further speculative discovery. */
internal class ReaderPriorityGate(private val limit: Int) {
    private class Waiter(val verification: Boolean) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }
    private val lock = Any()
    private var active = 0
    private val verification = ArrayDeque<Waiter>()
    private val discovery = ArrayDeque<Waiter>()
    init { require(limit > 0) }

    suspend fun <T> withPermit(verify: Boolean, block: suspend () -> T): T {
        val waiter = synchronized(lock) {
            if (active < limit && verification.isEmpty() && discovery.isEmpty()) { active++; null }
            else Waiter(verify).also { (if (verify) verification else discovery).addLast(it) }
        }
        if (waiter != null) {
            try { waiter.ready.await() }
            catch (error: Throwable) {
                val granted = synchronized(lock) {
                    (if (waiter.verification) verification else discovery).remove(waiter)
                    waiter.granted
                }
                if (granted) release()
                throw error
            }
        }
        try { return block() } finally { release() }
    }

    private fun release() {
        val next = synchronized(lock) {
            active--
            (if (verification.isNotEmpty()) verification.removeFirst() else discovery.removeFirstOrNull())?.also {
                active++; it.granted = true
            }
        }
        next?.ready?.complete(Unit)
    }
}

/** Counts the union of active phases; queued time never consumes a source's IO budget. */
internal class ReaderActiveBudget(private val totalMs: Long, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var active = 0
    private var started = 0L
    private var consumed = 0L
    init { require(totalMs > 0) }
    @Synchronized fun remaining(): Long = (totalMs - consumed - if (active > 0) (clock() - started).coerceAtLeast(0) else 0).coerceAtLeast(0)
    @Synchronized fun enter() { if (active++ == 0) started = clock() }
    @Synchronized fun leave() {
        check(active > 0)
        if (--active == 0) consumed += (clock() - started).coerceAtLeast(0)
    }
}
