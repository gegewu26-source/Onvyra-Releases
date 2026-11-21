package com.constx.onvyra.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WorkspaceFileListingLoaderTest {
    private fun entry(name: String) = WorkspaceFileEntry(name, name, false, 1, 0)
    // A callback-style provider does not automatically stop when its caller is cancelled.
    private class LateReply<T> {
        private lateinit var continuation: Continuation<T>
        suspend fun await(): T = suspendCoroutine { continuation = it }
        fun complete(value: T) = continuation.resume(value)
    }

    @Test fun `late old folder result cannot replace a newer listing`() = runTest {
        val state = MutableStateFlow(WorkspaceDetailState(path = "old"))
        val old = LateReply<List<WorkspaceFileEntry>>()
        val recent = CompletableDeferred<List<WorkspaceFileEntry>>()
        val requests = mutableListOf<String>()
        var oldActuallyReturned = false
        val loader = WorkspaceFileListingLoader(this, state) { _, path ->
            requests += path
            // A disk/provider request can finish even after its caller was cancelled.
            if (path == "old") old.await().also { oldActuallyReturned = true } else recent.await()
        }
        loader.refresh(); runCurrent()
        state.value = state.value.copy(path = "new", entries = emptyList())
        loader.refresh(); runCurrent()
        recent.complete(listOf(entry("new.txt"))); runCurrent()
        old.complete(listOf(entry("old.txt"))); runCurrent()
        assertTrue("The stale provider must really return after cancellation", oldActuallyReturned)
        assertEquals(listOf("old", "new"), requests)
        assertEquals(listOf(entry("new.txt")), state.value.entries)
        assertFalse(state.value.loading)
        assertNull(state.value.error)
    }

    @Test fun `late failure from old storage area cannot clear a successful new listing`() = runTest {
        val state = MutableStateFlow(WorkspaceDetailState())
        val releaseOld = LateReply<Unit>()
        var oldActuallyFailed = false
        val loader = WorkspaceFileListingLoader(this, state) { area, _ ->
            if (area == WorkspaceStorageArea.FILES) {
                releaseOld.await(); oldActuallyFailed = true; throw IOException("old area failed")
            } else listOf(entry("bin"))
        }
        loader.refresh(); runCurrent()
        state.value = state.value.copy(area = WorkspaceStorageArea.LINUX)
        loader.refresh(); runCurrent()
        releaseOld.complete(Unit); runCurrent()
        assertTrue("The stale provider must really fail after cancellation", oldActuallyFailed)
        assertEquals(WorkspaceStorageArea.LINUX, state.value.area)
        assertEquals(listOf(entry("bin")), state.value.entries)
        assertFalse(state.value.loading)
        assertNull(state.value.error)
    }

    @Test fun `new refresh of the same path invalidates an older request`() = runTest {
        val state = MutableStateFlow(WorkspaceDetailState(path = "same"))
        val old = LateReply<Unit>()
        var oldActuallyReturned = false
        var call = 0
        val loader = WorkspaceFileListingLoader(this, state) { _, _ ->
            if (++call == 1) {
                old.await(); oldActuallyReturned = true; listOf(entry("stale.txt"))
            } else listOf(entry("fresh.txt"))
        }
        loader.refresh(); runCurrent()
        loader.refresh(); runCurrent()
        old.complete(Unit); runCurrent()
        assertTrue(oldActuallyReturned)
        assertEquals(listOf(entry("fresh.txt")), state.value.entries)
    }

    @Test fun `location is checked even before navigation starts its next refresh`() = runTest {
        val state = MutableStateFlow(WorkspaceDetailState(path = "old"))
        val old = CompletableDeferred<List<WorkspaceFileEntry>>()
        val loader = WorkspaceFileListingLoader(this, state) { _, _ -> old.await() }
        loader.refresh(); runCurrent()
        state.value = state.value.copy(path = "other", entries = emptyList())
        old.complete(listOf(entry("wrong.txt"))); runCurrent()
        assertEquals("other", state.value.path)
        assertTrue(state.value.entries.isEmpty())
    }

    @Test fun `current listing error remains visible`() = runTest {
        val state = MutableStateFlow(WorkspaceDetailState(entries = listOf(entry("previous.txt"))))
        val loader = WorkspaceFileListingLoader(this, state) { _, _ -> throw IOException("access denied") }
        loader.refresh(); runCurrent()
        assertEquals("access denied", state.value.error)
        assertTrue(state.value.entries.isEmpty())
        assertFalse(state.value.loading)
    }

    @Test fun `cancellation is not shown as a file listing failure`() = runTest {
        val ownScope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = MutableStateFlow(WorkspaceDetailState())
        var cancelled = false
        val loader = WorkspaceFileListingLoader(ownScope, state) { _, _ ->
            try { awaitCancellation() } catch (error: CancellationException) {
                cancelled = true; throw error
            }
        }
        loader.refresh(); runCurrent()
        ownScope.cancel(); runCurrent()
        assertTrue(cancelled)
        assertNull(state.value.error)
    }
}
