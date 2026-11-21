package com.constx.onvyra.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverableAccessVerifierTest {
    private class Authority(var unlocked: Boolean = false, var expiry: Long = Long.MAX_VALUE) {
        fun allows(now: Long): Boolean = unlocked && now < expiry
    }

    @Test fun failedStartupLookupCanRecoverAndSeeALaterSuccessfulUnlock() {
        val authority = Authority()
        var ready = false
        var lookups = 0
        val verify = RecoverableAccessVerifier(
            load = { lookups++; check(ready) { "Fixture authority not ready" }; authority },
            isAllowed = { it.allows(0L) },
        )
        assertFalse(verify())
        ready = true
        assertFalse("Loading successfully must not grant access without authorization", verify())
        authority.unlocked = true
        assertTrue("Use the same singleton that the dialog unlocked", verify())
        assertEquals(3, lookups)
    }

    @Test fun successfulLookupDoesNotFreezeRevocationOrExpiry() {
        val authority = Authority(unlocked = true, expiry = 100L)
        var now = 99L
        val verify = RecoverableAccessVerifier(load = { authority }, isAllowed = { it.allows(now) })
        assertTrue(verify())
        now = 100L
        assertFalse("The exact expiry boundary must be denied", verify())
        authority.expiry = 200L
        assertTrue(verify())
        authority.unlocked = false
        assertFalse("Closing the feature must invalidate the next check", verify())
    }

    @Test fun repeatedLookupFailuresRemainDeniedAndAreRetried() {
        var attempts = 0
        val verify = RecoverableAccessVerifier<Authority>(
            load = { attempts++; error("Fixture load failure") },
            isAllowed = { error("Must not inspect an authority that failed to load") },
        )
        repeat(4) { assertFalse(verify()) }
        assertEquals(4, attempts)
    }

    @Test fun authorityCheckFailureIsDeniedWithoutPoisoningTheNextCheck() {
        val authority = Authority(unlocked = true)
        var failure = true
        val verify = RecoverableAccessVerifier(load = { authority }, isAllowed = {
            if (failure) error("Fixture check failure") else it.allows(0L)
        })
        assertFalse(verify())
        failure = false
        assertTrue(verify())
        authority.unlocked = false
        assertFalse(verify())
    }
}
