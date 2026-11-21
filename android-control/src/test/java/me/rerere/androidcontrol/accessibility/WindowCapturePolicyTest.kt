package me.rerere.androidcontrol.accessibility

import org.junit.Assert.*
import org.junit.Test

class WindowCapturePolicyTest {
    private val window = CaptureWindowGeometry(12, 0, 80, 1080, 2280)

    @Test fun vendorScaledSuccessfulWindowMayPreviewButCannotExportWrongAgentCoordinates() {
        assertTrue(captureWindowIsCurrent(window, window, 540, 1100, requireExactCoordinates = false))
        assertFalse(captureWindowIsCurrent(window, window, 540, 1100, requireExactCoordinates = true))
        assertTrue(captureWindowIsCurrent(window, window, 1080, 2200, requireExactCoordinates = true))
    }

    @Test fun movingClosingOrReplacedWindowNeverAuthorizesEvenPreview() {
        for (after in listOf(null, window.copy(id = 13), window.copy(top = 0), window.copy(right = 800))) {
            assertFalse(captureWindowIsCurrent(window, after, 1080, 2200, requireExactCoordinates = false))
        }
        assertFalse(captureWindowIsCurrent(window, window, 0, 0, requireExactCoordinates = false))
    }

    @Test fun onlyExplicitFrameworkSecureErrorIsClassifiedAsProtected() {
        assertEquals("secure_window", previewCaptureFailure(6))
        assertEquals("window_not_ready", previewCaptureFailure(5))
        assertEquals("screenshot_rate_limited", previewCaptureFailure(3))
        for (error in listOf(null, 1, 2, 3, 4, 5)) {
            val failure = previewCaptureFailure(error)
            assertNotEquals("secure_window", failure)
            assertFalse(previewCaptureMessage(failure).contains("禁止截图"))
        }
    }

    @Test fun protectedWindowRemainsARealFailureAndKeepsRetryingWithoutDemandingTakeover() {
        val failure = previewCaptureFailure(6)
        val message = previewCaptureMessage(failure)
        assertEquals("secure_window", failure)
        assertTrue(message.contains("系统"))
        assertTrue(message.contains("受保护"))
        assertTrue(message.contains("重试"))
        assertFalse(message.contains("禁止截图"))
        assertFalse(message.contains("手动接管"))
        for (elapsed in listOf(0L, 9_999L, 10_000L, 60_000L, 3_600_000L)) {
            assertEquals(1_000L, previewRetryIntervalMs(false, failure, elapsed))
        }
    }

    @Test fun startupErrorsRetryQuicklyButDoNotBecomeAnUnboundedFastLoop() {
        assertEquals(350L, previewRetryIntervalMs(false, "window_not_ready", 0))
        assertEquals(350L, previewRetryIntervalMs(false, "window_changed", 9_999))
        assertEquals(1_000L, previewRetryIntervalMs(false, "window_not_ready", 10_000))
        assertEquals(1_000L, previewRetryIntervalMs(false, "secure_window", 50))
        assertEquals(1_000L, previewRetryIntervalMs(true, null, 50))
    }

    @Test fun timeoutAndWindowChangeRetainTheirSpecificRecoverableReason() {
        assertEquals("screenshot_timeout", previewCaptureFailure(1, "screenshot_timeout"))
        assertEquals("window_changed", previewCaptureFailure(5, "window_changed"))
        assertNotEquals(previewCaptureMessage("window_changed"), previewCaptureMessage("secure_window"))
        assertTrue(previewCaptureMessage("screenshot_timeout").contains("重试"))
    }
}
