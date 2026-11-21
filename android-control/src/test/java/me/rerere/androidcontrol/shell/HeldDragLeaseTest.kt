package me.rerere.androidcontrol.shell

import me.rerere.androidcontrol.ui.PreviewDisplayGeometry
import org.junit.Assert.*
import org.junit.Test

class HeldDragLeaseTest {
    private val portrait = PreviewDisplayGeometry(720, 1280, 0)
    private val lease = HeldDragLease(41, 8, portrait, 1_000)

    @Test fun staleTokensAndOtherDisplaysCannotOperateTheCurrentFinger() {
        assertTrue(lease.matches(8, 41))
        assertFalse(lease.matches(0, 41))
        assertFalse(lease.matches(9, 41))
        assertFalse(lease.matches(8, 40))
        assertFalse(lease.matches(8, 42))
    }

    @Test fun rotationOrDisplayResizeInvalidatesTheSnapshotEvenForAnInBoundsPoint() {
        assertTrue(lease.mayContinue(portrait, 2_000, 100, 100))
        assertFalse(lease.mayContinue(PreviewDisplayGeometry(720, 1280, 2), 2_000, 100, 100))
        assertFalse(lease.mayContinue(PreviewDisplayGeometry(1280, 720, 1), 2_000, 100, 100))
        assertFalse(lease.mayContinue(PreviewDisplayGeometry(721, 1280, 0), 2_000, 100, 100))
        assertFalse(lease.mayContinue(null, 2_000, 100, 100))
    }

    @Test fun observationDoesNotRenewTheDeadlineAndEveryViewportEdgeIsChecked() {
        assertTrue(lease.mayContinue(portrait, 60_999, 719, 1279))
        assertFalse(lease.mayContinue(portrait, 61_000, 719, 1279))
        assertFalse(lease.mayContinue(portrait, 999, 0, 0))
        for ((x, y) in listOf(-1 to 0, 0 to -1, 720 to 0, 0 to 1280)) {
            assertFalse(lease.mayContinue(portrait, 2_000, x, y))
        }
    }

    @Test fun thePrimaryDisplayCannotReceiveAHeldPointerLease() {
        assertThrows(IllegalArgumentException::class.java) { HeldDragLease(1, 0, portrait, 0) }
        assertThrows(IllegalArgumentException::class.java) { HeldDragLease(0, 8, portrait, 0) }
    }
}
