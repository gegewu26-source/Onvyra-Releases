package com.constx.onvyra.data.repository

import com.constx.onvyra.data.db.entity.WorkspaceEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WorkspaceOperationRegistryTest {
    @Test fun disabledWorkspaceRejectsANewOperationBeforeItsFileOrCommandBodyRuns() = runBlocking {
        val registry = WorkspaceOperationRegistry()
        var ran = false
        try {
            registry.run("one", checkEnabled = { throw WorkspaceDisabledException() }) { ran = true }
            fail("Disabled workspace must reject the operation")
        } catch (_: WorkspaceDisabledException) { }
        assertFalse(ran)
    }

    @Test fun disableInterruptsOnlyTheSelectedWorkspacesBlockingChildAndKeepsItsCallerAlive() = runBlocking {
        withTimeout(10_000) {
            val registry = WorkspaceOperationRegistry()
            val enabled = AtomicBoolean(true)
            val blocked = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val unrelatedStarted = CompletableDeferred<Unit>()
            val selected = launch(Dispatchers.Default) {
                registry.run("one", { if (!enabled.get()) throw WorkspaceDisabledException() }) {
                    runInterruptible(Dispatchers.IO) {
                        blocked.countDown()
                        try { CountDownLatch(1).await() }
                        catch (error: InterruptedException) { interrupted.set(true); throw error }
                    }
                }
            }
            val unrelated = launch {
                registry.run("two", {}) { unrelatedStarted.complete(Unit); awaitCancellation() }
            }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            unrelatedStarted.await()
            var stoppedRoot = ""
            assertTrue(registry.setEnabled("one", false, persist = {
                enabled.set(false); true
            }, stopSessions = {
                assertFalse("Availability is persisted before closing sessions", enabled.get())
                stoppedRoot = "one"
            }))
            assertEquals("one", stoppedRoot)
            // Shutdown waits for the registered operation scope. Its caller's launch
            // receives the cancellation when its continuation is next dispatched.
            selected.join()
            assertTrue(selected.isCancelled)
            assertTrue(interrupted.get())
            assertTrue(unrelated.isActive)
            assertTrue(currentCoroutineContext().isActive)
            unrelated.cancelAndJoin()
        }
    }

    @Test fun reenableWaitsForShutdownAndRunsOnlyANewExplicitOperation() = runBlocking {
        withTimeout(5_000) {
            val registry = WorkspaceOperationRegistry()
            var enabled = true
            var starts = 0
            val started = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val finishStop = CompletableDeferred<Unit>()
            val previous = launch {
                registry.run("one", { check(enabled) }) { starts++; started.complete(Unit); awaitCancellation() }
            }
            started.await()
            val disabling = async {
                registry.setEnabled("one", false, { enabled = false; true }) {
                    stopping.complete(Unit); finishStop.await()
                }
            }
            stopping.await()
            val reenabling = async { registry.setEnabled("one", true, { enabled = true; true }) { fail("Enable must not close sessions") } }
            delay(20)
            assertFalse(enabled)
            assertFalse(reenabling.isCompleted)
            finishStop.complete(Unit)
            assertTrue(disabling.await()); assertTrue(reenabling.await())
            assertTrue(previous.isCancelled)
            assertEquals("Enable must not silently restart old commands", 1, starts)
            assertEquals("new", registry.run("one", { check(enabled) }) { starts++; "new" })
            assertEquals(2, starts)
        }
    }

    @Test fun workspaceAvailabilityDoesNotOverwriteItsRootfsReadinessOrBindings() {
        val original = WorkspaceEntity(id = "one", name = "资料", root = "kept-root", shellStatus = "READY",
            createdAt = 10L, updatedAt = 20L, toolApprovals = "{\"workspace_shell\":true}")
        assertTrue(original.enabled)
        assertEquals(original, requireEnabledWorkspace(original))
        // Shell-disabled is still an available file workspace; it must not become the availability flag.
        assertEquals("DISABLED", requireEnabledWorkspace(original.copy(shellStatus = "DISABLED")).shellStatus)
        val stopped = original.copy(enabled = false)
        assertFalse(stopped.enabled)
        try { requireEnabledWorkspace(stopped); fail("Disabled metadata must reject actual repository access") }
        catch (_: WorkspaceDisabledException) { }
        assertEquals(original.root, stopped.root)
        assertEquals(original.shellStatus, stopped.shellStatus)
        assertEquals(original.toolApprovals, stopped.toolApprovals)
        assertEquals(original, stopped.copy(enabled = true))
    }
}
