package com.constx.onvyra.ui.components.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorSupportScheduleTest {
    private val shownAt = 1_730_000_000_000L
    private val launcher = setOf("android.intent.category.LAUNCHER")

    @Test fun firstNormalLaunchIsDueWithoutAStoredReminderTime() {
        assertTrue(AuthorSupportSchedule.isDue(lastShownAt = null, now = shownAt))
    }

    @Test fun reminderWaitsForTheEntireTenDayInterval() {
        assertEquals(864_000_000L, AuthorSupportSchedule.INTERVAL_MILLIS)
        assertFalse(AuthorSupportSchedule.isDue(shownAt, shownAt))
        assertFalse(AuthorSupportSchedule.isDue(shownAt, shownAt + 9 * 86_400_000L))
        assertFalse(AuthorSupportSchedule.isDue(shownAt, shownAt + 864_000_000L - 1))
        assertTrue(AuthorSupportSchedule.isDue(shownAt, shownAt + 864_000_000L))
        assertTrue(AuthorSupportSchedule.isDue(shownAt, shownAt + 11 * 86_400_000L))
    }

    @Test fun clockRollbackDoesNotImmediatelyRepeatTheDialog() {
        assertFalse(AuthorSupportSchedule.isDue(shownAt, shownAt - 1))
        assertFalse(AuthorSupportSchedule.isDue(shownAt, shownAt - 20 * 86_400_000L))
        assertFalse(AuthorSupportSchedule.isDue(Long.MAX_VALUE, shownAt))
    }

    @Test fun invalidCurrentClockDoesNotShowTheDialog() {
        assertFalse(AuthorSupportSchedule.isDue(null, -1))
        assertFalse(AuthorSupportSchedule.isDue(shownAt, -1))
    }

    @Test fun plainAndLauncherActivityStartsAreNormalLaunches() {
        for (action in listOf(null, "android.intent.action.MAIN")) {
            for (categories in listOf(null, emptySet(), launcher)) {
                assertTrue(AuthorSupportSchedule.isNormalLaunch(action, categories, hasData = false, hasRoutedPayload = false))
            }
        }
    }

    @Test fun shareTranslateAndSpecialToolActionsNeverShowAStartupDialog() {
        for (action in listOf(
            "android.intent.action.SEND", "android.intent.action.PROCESS_TEXT", "android.intent.action.VIEW",
            "com.constx.onvyra.action.TRANSLATE", "com.constx.onvyra.action.MEDIA_CREATION",
        )) {
            assertFalse(AuthorSupportSchedule.isNormalLaunch(action, launcher, hasData = false, hasRoutedPayload = false))
        }
    }

    @Test fun deepLinkPayloadAndNonLauncherCategoriesAreExcluded() {
        assertFalse(AuthorSupportSchedule.isNormalLaunch("android.intent.action.MAIN", launcher, hasData = true, hasRoutedPayload = false))
        assertFalse(AuthorSupportSchedule.isNormalLaunch(null, null, hasData = false, hasRoutedPayload = true))
        assertFalse(AuthorSupportSchedule.isNormalLaunch("android.intent.action.MAIN", setOf("android.intent.category.BROWSABLE"), hasData = false, hasRoutedPayload = false))
    }
}
