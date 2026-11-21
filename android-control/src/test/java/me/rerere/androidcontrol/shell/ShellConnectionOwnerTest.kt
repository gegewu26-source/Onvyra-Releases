package me.rerere.androidcontrol.shell

import me.rerere.androidcontrol.adb.AdbIoCancelledException
import me.rerere.androidcontrol.adb.AdbIoSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShellConnectionOwnerTest {
    @Test fun oldServiceCancellationDoesNotCloseANewerRequest() {
        val slot = ShellConnectionIoSlot(Any())
        val oldOwner = ShellConnectionOwner()
        val newOwner = ShellConnectionOwner()
        val oldIo = AdbIoSession()
        val newIo = AdbIoSession()
        slot.register(oldIo, oldOwner) { true }
        val detached = slot.detachOwned(oldOwner)
        slot.register(newIo, newOwner) { true }

        // Resource closing can complete after a new request is registered.
        detached?.close()
        assertTrue(oldIo.cancelled)
        assertFalse(newIo.cancelled)
        assertNull(slot.detachOwned(oldOwner))
        assertSame(newIo, slot.detachOwned(newOwner))
    }

    @Test fun anOldFinallyCannotRemoveTheNewRequestsCancellationHandle() {
        val slot = ShellConnectionIoSlot(Any())
        val oldIo = AdbIoSession()
        val newIo = AdbIoSession()
        val newOwner = ShellConnectionOwner()
        slot.register(oldIo, ShellConnectionOwner()) { true }
        slot.detachAll()
        slot.register(newIo, newOwner) { true }

        slot.finished(oldIo)
        assertSame(newIo, slot.detachOwned(newOwner))
    }

    @Test fun cancelledOwnersCannotRegisterWorkAfterWaitingForTheMutex() {
        val slot = ShellConnectionIoSlot(Any())
        val owner = ShellConnectionOwner()
        assertNull(slot.detachOwned(owner))
        try {
            slot.register(AdbIoSession(), owner) { true }
            fail("A cancelled setup owner must not start its queued operation")
        } catch (_: AdbIoCancelledException) {
            // The production bridge closes an unregistered session on rejection.
        }
        assertNull(slot.detachAll())
    }

    @Test fun globalStopDetachesOwnedAndUnownedRequests() {
        for (owner in listOf(ShellConnectionOwner(), null)) {
            val slot = ShellConnectionIoSlot(Any())
            val io = AdbIoSession()
            slot.register(io, owner) { true }
            assertSame(io, slot.detachAll())
            io.close()
            assertTrue(io.cancelled)
            assertNull(slot.detachAll())
        }
    }

    @Test fun expiredConnectionEpochRejectsUnownedLaunchesToo() {
        val slot = ShellConnectionIoSlot(Any())
        try {
            slot.register(AdbIoSession(), null) { false }
            fail("A stale global launch ticket must not install an IO handle")
        } catch (_: AdbIoCancelledException) {
            // A newer UI stop or mode switch owns the bridge now.
        }
        assertNull(slot.detachAll())
    }
}
