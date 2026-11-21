package com.constx.onvyra.data.files

import org.junit.Assert.*
import org.junit.Test

class FileCleanupWindowTest {
    @Test fun withinIncludesExactAgeBoundaryAndExcludesOlderOrFutureFiles() {
        val now = 2_000_000_000_000L
        listOf(1L, 3L, 7L, 30L).forEach { days ->
            val window = cleanupDurationMillis(days.toString(), FileCleanupUnit.DAYS)!!
            assertTrue(matchesCleanupWindow(now, now, window))
            assertTrue(matchesCleanupWindow(now - window, now, window))
            assertTrue(matchesCleanupWindow(now - window + 1, now, window))
            assertFalse(matchesCleanupWindow(now - window - 1, now, window))
            assertFalse(matchesCleanupWindow(now + 1, now, window))
        }
    }

    @Test fun customHoursDaysAndThirtyDayMonthsHaveDistinctDurations() {
        assertEquals(7_200_000L, cleanupDurationMillis("2", FileCleanupUnit.HOURS))
        assertEquals(172_800_000L, cleanupDurationMillis("2", FileCleanupUnit.DAYS))
        assertEquals(5_184_000_000L, cleanupDurationMillis("2", FileCleanupUnit.MONTHS))
        assertEquals(cleanupDurationMillis("30", FileCleanupUnit.DAYS), cleanupDurationMillis("1", FileCleanupUnit.MONTHS))
    }

    @Test fun rejectsInvalidAmountsAndChecksOverflowForEveryUnit() {
        FileCleanupUnit.entries.forEach { unit ->
            listOf("", "0", "-1", "1.5", "1e3", "999999999999999999999999").forEach {
                assertNull(cleanupDurationMillis(it, unit))
            }
            val largest = Long.MAX_VALUE / unit.millis
            assertEquals(largest * unit.millis, cleanupDurationMillis(largest.toString(), unit))
            assertNull(cleanupDurationMillis((largest + 1).toString(), unit))
        }
    }

    @Test fun timestampExtremesDoNotOverflowOrSelectInvalidDates() {
        assertFalse(matchesCleanupWindow(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(matchesCleanupWindow(0, -1, Long.MAX_VALUE))
        assertTrue(matchesCleanupWindow(0, Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(matchesCleanupWindow(100, 100, 0))
        assertFalse(matchesCleanupWindow(100, 100, -1))
    }
}
