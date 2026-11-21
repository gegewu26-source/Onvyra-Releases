package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class AndroidControlApprovalModeTest {
    @Test fun readingAndStoppingNeverWaitForAnApprovalInAnyMode() {
        AndroidControlApprovalMode.entries.forEach { mode ->
            assertFalse(requiresControlApproval(mode, mutation = false, risk = true))
        }
    }
    @Test fun confirmChangesIncludesOrdinaryMutationsWhileAutonomousKeepsRiskChecks() {
        assertTrue(requiresControlApproval(AndroidControlApprovalMode.CONFIRM_CHANGES, true, false))
        assertFalse(requiresControlApproval(AndroidControlApprovalMode.AUTONOMOUS, true, false))
        assertTrue(requiresControlApproval(AndroidControlApprovalMode.AUTONOMOUS, true, true))
    }
    @Test fun fullAccessDoesNotPromptForEachCoordinateOrSensitiveMutation() {
        assertFalse(requiresControlApproval(AndroidControlApprovalMode.FULL_ACCESS, true, false))
        assertFalse(requiresControlApproval(AndroidControlApprovalMode.FULL_ACCESS, true, true))
    }
}
