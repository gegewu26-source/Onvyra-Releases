package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class OwnedProcessCleanupTest {
    private val old = OwnedProcessSnapshot(123, 10123, "fixture.game", 456L, setOf("fixture.game"))

    @Test fun exitedOrReusedPidCannotBlockANewProcess() {
        assertFalse(removedProcessStillPending(old, null, emptySet(), emptySet()))
        for (replacement in listOf(old.copy(uid = 10124), old.copy(name = "other.process"),
            old.copy(startTime = 457L), old.copy(pid = 124))) {
            assertFalse(removedProcessStillPending(old, replacement, emptySet(), emptySet()))
        }
    }

    @Test fun unchangedDyingProcessMustFinishBeforeReopen() {
        assertTrue(removedProcessStillPending(old, old, emptySet(), emptySet()))
        assertTrue(removedProcessStillPending(old, old, setOf("other.app"), setOf(124)))
    }

    @Test fun survivingMainTaskOrRunningServiceMustNotBeBlockedOrKilled() {
        assertFalse(removedProcessStillPending(old, old, setOf("fixture.game"), emptySet()))
        assertFalse(removedProcessStillPending(old, old, emptySet(), setOf(123)))
    }

    @Test fun statStartTimeIgnoresSpacesAndParenthesesInProcessName() {
        val fields = listOf("S") + List(18) { "0" } + "456" + "0"
        assertEquals(456L, processStartTime("123 (a name (with) spaces) " + fields.joinToString(" ")))
        assertNull(processStartTime("123 (incomplete) S 0"))
    }
}
