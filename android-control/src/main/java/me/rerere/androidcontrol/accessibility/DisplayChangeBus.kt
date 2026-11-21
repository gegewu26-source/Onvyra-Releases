package me.rerere.androidcontrol.accessibility

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A replayed, display-scoped signal avoids missing an event between an action and its waiter. */
internal class DisplayChangeBus {
    private data class Change(val revision: Long, val connected: Boolean)
    private val connected = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val displays = ConcurrentHashMap<Int, MutableStateFlow<Change>>()

    fun connect() {
        connected.set(true)
        displays.values.forEach { it.value = it.value.copy(connected = true) }
    }

    fun revision(displayId: Int): Long = state(displayId).value.revision

    fun changed(displayId: Int) {
        if (displayId < 0 || !connected.get()) return
        val revision = sequence.incrementAndGet()
        state(displayId).value = Change(revision, true)
    }

    fun disconnect() {
        connected.set(false)
        val revision = sequence.incrementAndGet()
        displays.values.forEach { it.value = Change(revision, false) }
    }

    suspend fun awaitChange(displayId: Int, afterRevision: Long, timeoutMs: Long): Boolean {
        if (displayId < 0 || timeoutMs <= 0 || !connected.get()) return false
        val change = withTimeoutOrNull(timeoutMs.coerceAtMost(30_000L)) {
            state(displayId).first { !it.connected || it.revision > afterRevision }
        }
        return change != null && change.connected && connected.get() && change.revision > afterRevision
    }

    private fun state(displayId: Int): MutableStateFlow<Change> = displays.computeIfAbsent(displayId) {
        MutableStateFlow(Change(0L, connected.get()))
    }
}
