package me.rerere.androidcontrol.accessibility

import org.junit.Assert.*
import org.junit.Test

class PreviewRetryPolicyTest {
    @Test fun aLaterRefreshStartsItsOwnBoundedRecoveryAfterASuccessfulCapture() {
        val policy = PreviewCaptureRetryPolicy()
        policy.onCaptureResult(false, 1_000)
        assertEquals(350L, policy.intervalMs(false, "empty_frame", 1_000))
        assertEquals(350L, policy.intervalMs(false, "empty_frame", 10_999))
        assertEquals(1_000L, policy.intervalMs(false, "empty_frame", 11_000))

        policy.onCaptureResult(true, 60_000)
        assertEquals(1_000L, policy.intervalMs(true, null, 60_000))
        policy.onCaptureResult(false, 120_000)
        assertEquals(350L, policy.intervalMs(false, "empty_frame", 120_000))
        assertEquals(350L, policy.intervalMs(false, "empty_frame", 129_999))
        assertEquals(1_000L, policy.intervalMs(false, "empty_frame", 130_000))
    }

    @Test fun repeatedOrChangingFailuresCannotExtendTheSameFastRecoveryEpisode() {
        val policy = PreviewCaptureRetryPolicy()
        val failures = listOf("empty_frame", "window_changed", "screenshot_unavailable", "screenshot_timeout")
        for (second in 0..30) {
            val now = 40_000L + second * 1_000L
            val failure = failures[second % failures.size]
            policy.onCaptureResult(false, now)
            assertEquals(if (second < 10) 350L else 1_000L, policy.intervalMs(false, failure, now))
        }
    }

    @Test fun secureFailuresAlwaysUseTheNormalCadenceAndDoNotResetAnActiveEpisode() {
        val policy = PreviewCaptureRetryPolicy()
        policy.onCaptureResult(false, 5_000)
        assertEquals(350L, policy.intervalMs(false, "window_not_ready", 5_000))
        for (now in listOf(5_100L, 14_999L, 15_000L, 60_000L)) {
            policy.onCaptureResult(false, now)
            assertEquals(1_000L, policy.intervalMs(false, "secure_window", now))
        }
        assertEquals(1_000L, policy.intervalMs(false, "empty_frame", 60_001))
        policy.onCaptureResult(true, 60_100)
        policy.onCaptureResult(false, 60_200)
        assertEquals(1_000L, policy.intervalMs(false, "secure_window", 60_200))
    }

    @Test fun skippedCapturesAndExpiredGuardsAlsoHaveABoundedWaitingBudget() {
        val policy = PreviewCaptureRetryPolicy()
        // No capture result arrives while Agent screenshots occupy the capture slot.
        assertEquals(350L, policy.intervalMs(false, null, 10_000))
        assertEquals(1_000L, policy.intervalMs(false, null, 20_000))
        assertEquals(1_000L, policy.intervalMs(false, null, 90_000))
        policy.onCaptureResult(true, 100_000)
        assertEquals(1_000L, policy.intervalMs(true, null, 103_000))
        assertEquals(350L, policy.intervalMs(false, null, 103_001))
        assertEquals(1_000L, policy.intervalMs(false, null, 113_001))
    }

    @Test fun nativeOutputRequiresASuccessfulProtectedCaptureAndAValidSurface() {
        val policy = PreviewNativeRetryPolicy()
        assertFalse(policy.canAttempt(10_000, authorized = true, validSurface = true))
        policy.onProtectedCaptureSucceeded()
        assertFalse(policy.canAttempt(10_000, authorized = false, validSurface = true))
        assertFalse(policy.canAttempt(10_000, authorized = true, validSurface = false))
        assertTrue(policy.canAttempt(10_000, authorized = true, validSurface = true))
    }

    @Test fun oneRejectedBindingRequiresBothItsCooldownAndANewerProtectedCapture() {
        val policy = PreviewNativeRetryPolicy()
        policy.onProtectedCaptureSucceeded()
        policy.onAttachResult(false, 1_000)
        assertFalse(policy.retryReady(1_499))
        assertFalse("Elapsed time alone cannot reuse the rejected capture", policy.retryReady(60_000))
        policy.onProtectedCaptureSucceeded()
        assertFalse("A new capture cannot shorten the cooldown", policy.retryReady(1_499))
        assertTrue(policy.canAttempt(1_500, authorized = true, validSurface = true))
        assertFalse(policy.canAttempt(1_500, authorized = false, validSurface = true))
        assertFalse(policy.canAttempt(1_500, authorized = true, validSurface = false))
    }

    @Test fun rejectedBindingsBackOffForHalfOneTwoAndFourSecondsThenStayBounded() {
        val policy = PreviewNativeRetryPolicy()
        var now = 20_000L
        policy.onProtectedCaptureSucceeded()
        for (cooldown in listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L)) {
            assertTrue(policy.canAttempt(now, authorized = true, validSurface = true))
            policy.onAttachResult(false, now)
            assertFalse(policy.retryReady(now + cooldown))
            policy.onProtectedCaptureSucceeded()
            assertFalse(policy.retryReady(now + cooldown - 1))
            assertTrue(policy.retryReady(now + cooldown))
            now += cooldown
        }
    }

    @Test fun anAcceptedBindingResetsTheBackoffForALaterTransientRejection() {
        val policy = PreviewNativeRetryPolicy()
        policy.onProtectedCaptureSucceeded()
        policy.onAttachResult(false, 0)
        policy.onProtectedCaptureSucceeded()
        policy.onAttachResult(false, 500)
        policy.onProtectedCaptureSucceeded()
        assertTrue(policy.canAttempt(1_500, authorized = true, validSurface = true))
        policy.onAttachResult(true, 1_500)
        policy.onAttachResult(false, 2_000)
        policy.onProtectedCaptureSucceeded()
        assertFalse(policy.retryReady(2_499))
        assertTrue(policy.retryReady(2_500))
    }

    @Test fun everyCaptureFailureKeepsNativeDeniedEvenAfterAnEarlierSuccessAndCooldown() {
        for (failure in listOf("empty_frame", "secure_window", "window_not_ready", "window_changed",
            "window_geometry_mismatch", "screenshot_timeout", "screenshot_unavailable",
            "screenshot_rate_limited", "accessibility_unavailable", "display_unavailable")) {
            val captures = PreviewCaptureRetryPolicy()
            val native = PreviewNativeRetryPolicy()
            native.onProtectedCaptureSucceeded()
            native.onAttachResult(false, 0)
            captures.onCaptureResult(false, 10_000)
            captures.intervalMs(false, failure, 10_000)
            assertFalse(failure, native.canAttempt(60_000, authorized = false, validSurface = true))
        }
    }
}
