package me.rerere.androidcontrol.accessibility

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AccessibilitySnapshotStoreTest {
    @Test fun disconnectDoesNotWaitForAnInFlightTargetAppAndRejectsItsLateNodes() {
        val store = AccessibilitySnapshotStore<Int, String>(2)
        val started = CountDownLatch(1)
        val releaseTarget = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        var published = true
        val worker = thread {
            val read = store.begin(4)
            started.countDown()
            check(releaseTarget.await(5, TimeUnit.SECONDS))
            published = store.publish(4, read, "old nodes")
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val lifecycle = thread { store.disconnect(); cleared.countDown() }
        try {
            assertTrue("Main-thread disconnect must finish while the target read is still blocked",
                cleared.await(1, TimeUnit.SECONDS))
            assertNull(store.get(4))
        } finally {
            releaseTarget.countDown(); worker.join(2_000); lifecycle.join(2_000)
        }
        assertFalse(published)
        assertNull(store.get(4))
    }

    @Test fun previousGenerationFailureCannotRemoveNewConnectionNodes() {
        val store = AccessibilitySnapshotStore<Int, String>(2)
        val oldRead = store.begin(4)
        store.disconnect()
        val fresh = store.begin(4)
        assertTrue(store.publish(4, fresh, "new nodes"))
        store.remove(4, oldRead)
        assertFalse(store.publish(4, oldRead, "stale nodes"))
        assertEquals("new nodes", store.get(4))
    }

    @Test fun snapshotsRemainBoundedAndTheCurrentGenerationCanReplaceItsOwnEntry() {
        val store = AccessibilitySnapshotStore<Int, String>(2)
        for (id in 1..3) assertTrue(store.publish(id, store.begin(id), "node$id"))
        assertNull(store.get(1)); assertEquals("node3", store.get(3))
        val read = store.begin(3)
        assertEquals("node3", read.previous)
        assertTrue(store.publish(3, read, "updated"))
        assertEquals("updated", store.get(3))
    }

    @Test fun overlappingSlowReadCannotReplaceNewerCompletedNodes() {
        val store = AccessibilitySnapshotStore<Int, String>(2)
        val older = store.begin(4)
        val newer = store.begin(4)
        assertTrue(store.publish(4, newer, "new page"))
        assertFalse(store.publish(4, older, "stale page"))
        store.remove(4, older)
        assertEquals("new page", store.get(4))
    }
}
