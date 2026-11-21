package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class HelperRecoveryPolicyTest {
    @Test fun liveForegroundAndCachedProcessesCanResumeWithoutStartingANewUid() {
        assertTrue(allowHelperResume(100, sameUid = true, stopped = false))
        assertTrue(allowHelperResume(400, sameUid = true, stopped = false))
    }

    @Test fun forceStoppedAndMissingProcessesMustNeverAcquireTheProvider() {
        assertFalse(allowHelperResume(100, sameUid = true, stopped = true))
        assertFalse(allowHelperResume(1_000, sameUid = true, stopped = false))
        assertFalse(allowHelperResume(-1, sameUid = true, stopped = false))
        assertFalse(allowHelperResume(1_001, sameUid = true, stopped = false))
    }

    @Test fun reinstalledPackageWithDifferentUidCannotResumeTheOldCapability() {
        assertFalse(allowHelperResume(100, sameUid = false, stopped = false))
    }
}
