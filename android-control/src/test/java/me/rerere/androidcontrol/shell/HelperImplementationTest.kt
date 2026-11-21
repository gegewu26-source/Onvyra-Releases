package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class HelperImplementationTest {
    @Test fun legacyAndStaleHelpersCannotClaimCurrentFixes() {
        assertFalse(HelperHandoff.currentImplementation(null))
        assertFalse(HelperHandoff.currentImplementation(intArrayOf(2000, 42)))
        assertFalse(HelperHandoff.currentImplementation(intArrayOf(2000, 42, 4)))
    }
    @Test fun currentShellRevisionIsRequiredInAdditionToUidAndPid() {
        assertFalse(HelperHandoff.currentImplementation(intArrayOf(2000, 42, 198)))
        assertTrue(HelperHandoff.currentImplementation(intArrayOf(2000, 42, 199)))
        assertFalse(HelperHandoff.currentImplementation(intArrayOf(0, 42, 199)))
        assertFalse(HelperHandoff.currentImplementation(intArrayOf(2000, 0, 199)))
    }
}
