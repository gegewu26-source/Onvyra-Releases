package me.rerere.androidcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityReadinessTest {
    @Test fun savedGrantDoesNotCountAsConnectedService() {
        assertEquals("accessibility_service_disconnected", accessibilityReadinessError(authorized=true, connected=false))
    }

    @Test fun missingGrantHasAnExplicitAuthorizationError() {
        assertEquals("accessibility_not_authorized", accessibilityReadinessError(authorized=false, connected=false))
    }

    @Test fun aBoundServiceIsReady() {
        assertNull(accessibilityReadinessError(authorized=true, connected=true))
    }

    @Test fun anUnbindPendingAfterGrantRevocationIsNeverReportedReady() {
        assertEquals("accessibility_not_authorized", accessibilityReadinessError(authorized=false, connected=true))
    }

    @Test fun aHelperConnectionDoesNotAdvertiseWholePhoneControlAsReady() {
        val missingService = AndroidControlReadiness(true, true, true, true, false)
        assertEquals(false, missingService.ready)
        assertEquals("accessibility_service_disconnected", missingService.error)
        val missingGrant = missingService.copy(accessibilityAuthorized=false, accessibilityConnected=true)
        assertEquals(false, missingGrant.ready)
        assertEquals("accessibility_not_authorized", missingGrant.error)
        assertEquals(true, missingService.copy(accessibilityConnected=true).ready)
    }

    @Test fun aMissingStopNotificationCannotBeAdvertisedAsReady() {
        val missingNotification = AndroidControlReadiness(true, true, true, true, true, false)
        assertEquals(false, missingNotification.ready)
        assertEquals("notification_permission_required_for_stop_control", missingNotification.error)
    }
}
