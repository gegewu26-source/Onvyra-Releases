package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class AndroidControlProtectionPolicyTest {
    @Test fun aTaskCannotKeepProtectionAfterAnyRequiredPermissionIsRemoved() {
        val denied = listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))
        denied.forEach { (enabled, authorized, notifications) ->
            assertEquals(ControlProtectionMode.OFF, controlProtectionMode(enabled, authorized, notifications, true))
            assertEquals(ControlProtectionMode.OFF, controlProtectionMode(enabled, authorized, notifications, false))
        }
    }

    @Test fun stoppingATaskRetainsStandbyButDisableOrSystemRevokeEndsIt() {
        assertEquals(ControlProtectionMode.TASK, controlProtectionMode(true, true, true, true))
        assertEquals(ControlProtectionMode.STANDBY, controlProtectionMode(true, true, true, false))
        assertEquals(ControlProtectionMode.OFF, controlProtectionMode(false, true, true, false))
        assertEquals(ControlProtectionMode.OFF, controlProtectionMode(true, false, true, false))
    }

    @Test fun anOldInstanceNeverCountsAsALiveFrameworkConnection() {
        var probes = 0
        assertFalse(accessibilityConnectionIsLive(false) { probes++; true })
        assertEquals(0, probes)
        assertFalse(accessibilityConnectionIsLive(true) { false })
        assertFalse(accessibilityConnectionIsLive(true) { error("Binder died") })
        assertTrue(accessibilityConnectionIsLive(true) { true })
    }

    @Test fun anRpcObjectWithoutTheSystemBoundServiceIsNotALiveConnection() {
        assertFalse(accessibilityConnectionIsLive(true, { true }, { false }))
        assertFalse(accessibilityConnectionIsLive(true, { true }, { error("System registry unavailable") }))
        assertTrue(accessibilityConnectionIsLive(true, { true }, { true }))
    }
}
