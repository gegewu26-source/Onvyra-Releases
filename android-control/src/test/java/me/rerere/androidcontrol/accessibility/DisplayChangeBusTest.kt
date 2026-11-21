package me.rerere.androidcontrol.accessibility

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DisplayChangeBusTest {
    @Test fun eventBeforeTheWaiterStartsIsReplayedOnlyToItsOwnDisplay() = runBlocking {
        val bus = DisplayChangeBus().apply { connect() }
        val before = bus.revision(7)
        bus.changed(7)

        assertTrue(bus.awaitChange(7, before, 200))
        assertFalse(bus.awaitChange(8, before, 30))
        assertEquals(0L, bus.revision(8))
    }

    @Test fun aMainScreenEventDoesNotWakeTheSecondaryScreenWaiter() = runBlocking {
        val bus = DisplayChangeBus().apply { connect() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { bus.awaitChange(9, bus.revision(9), 500) }
        bus.changed(0)
        assertFalse(waiting.isCompleted)
        bus.changed(9)
        assertTrue(waiting.await())
    }

    @Test fun disconnectFinishesPendingWaitsWithoutReportingAContentChange() = runBlocking {
        val bus = DisplayChangeBus().apply { connect() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { bus.awaitChange(3, bus.revision(3), 10_000) }
        bus.disconnect()

        assertFalse(waiting.await())
        assertFalse(bus.awaitChange(3, -1, 100))
    }

    @Test fun cancellingAWaiterDoesNotConsumeTheNextChange() = runBlocking {
        val bus = DisplayChangeBus().apply { connect() }
        val before = bus.revision(4)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { bus.awaitChange(4, before, 10_000) }
        waiting.cancelAndJoin()
        bus.changed(4)

        assertTrue(waiting.isCancelled)
        assertTrue(bus.awaitChange(4, before, 100))
        assertFalse(bus.awaitChange(4, bus.revision(4), 20))
    }

    @Test fun reconnectCanObserveNewEventsOnAPreviouslyRegisteredDisplay() = runBlocking {
        val bus = DisplayChangeBus().apply { connect() }
        bus.revision(6)
        bus.disconnect()
        bus.connect()
        val before = bus.revision(6)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { bus.awaitChange(6, before, 1_000) }
        assertFalse(waiting.isCompleted)
        bus.changed(6)

        assertTrue(waiting.await())
    }
}
