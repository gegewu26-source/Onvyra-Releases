package me.rerere.androidcontrol.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidShellModeTest {
    private fun helper(pid: Int = 51) = intArrayOf(HelperHandoff.SHELL_UID, pid, HelperHandoff.IMPLEMENTATION_REVISION)

    @Test fun disconnectedOrStaleHelpersNeverShowAStartedMode() {
        assertNull(activeShellMode(null, 51, "ROOT"))
        assertNull(activeShellMode(intArrayOf(0, 51, HelperHandoff.IMPLEMENTATION_REVISION), 51, "ROOT"))
        assertNull(activeShellMode(intArrayOf(2000, 51, HelperHandoff.IMPLEMENTATION_REVISION - 1), 51, "WIRELESS"))
    }

    @Test fun onlyTheSuccessfullyStartedProcessUsesTheRememberedMode() {
        assertEquals(AndroidShellMode.ROOT, activeShellMode(helper(), 51, "ROOT"))
        assertEquals(AndroidShellMode.WIRELESS, activeShellMode(helper(), 51, "WIRELESS"))
        assertEquals(AndroidShellMode.USB, activeShellMode(helper(52), 51, "ROOT"))
    }

    @Test fun externalOrOldUntrackedHelpersDoNotPretendToHaveRootAuthorization() {
        assertEquals(AndroidShellMode.USB, activeShellMode(helper(), 0, null))
        assertEquals(AndroidShellMode.USB, activeShellMode(helper(), 51, "unknown"))
    }
}
