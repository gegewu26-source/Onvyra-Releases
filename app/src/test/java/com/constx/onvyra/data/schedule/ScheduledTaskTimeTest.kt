package com.constx.onvyra.data.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ScheduledTaskTimeTest {
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun task(first: Long, repeat: ScheduledTaskRepeat = ScheduledTaskRepeat.ONCE, zone: String = "Asia/Shanghai") =
        ScheduledTask(title = "测试", assistantId = "assistant", prompt = "test", firstRunAtMillis = first, repeat = repeat, zoneId = zone)

    @Test fun futureOneTimeKeepsTheChosenInstant() {
        assertEquals(200L, nextScheduledTaskTime(task(200), 100))
    }

    @Test fun oneTimeAtOrBeforeNowDoesNotRepeat() {
        assertNull(nextScheduledTaskTime(task(100), 100))
        assertNull(nextScheduledTaskTime(task(100), 101))
    }

    @Test fun dailySkipsMissedOccurrencesWithoutBurstReplay() {
        val first = at("2026-11-01T09:15:00+08:00[Asia/Shanghai]")
        val now = at("2026-11-06T10:00:00+08:00[Asia/Shanghai]")
        assertEquals(at("2026-11-07T09:15:00+08:00[Asia/Shanghai]"), nextScheduledTaskTime(task(first, ScheduledTaskRepeat.DAILY), now))
    }

    @Test fun weeklyKeepsTheOriginalWeekdayAndTime() {
        val first = at("2026-11-02T09:15:00+08:00[Asia/Shanghai]")
        val now = at("2026-11-16T09:15:00+08:00[Asia/Shanghai]")
        assertEquals(at("2026-11-23T09:15:00+08:00[Asia/Shanghai]"), nextScheduledTaskTime(task(first, ScheduledTaskRepeat.WEEKLY), now))
    }

    @Test fun springDstDoesNotTurnDailyScheduleIntoFixedTwentyFourHours() {
        val first = at("2026-03-07T09:00:00-05:00[America/New_York]")
        val actual = nextScheduledTaskTime(task(first, ScheduledTaskRepeat.DAILY, "America/New_York"), first)
        assertEquals(at("2026-03-08T09:00:00-04:00[America/New_York]"), actual)
        assertEquals(23L * 60 * 60 * 1000, actual!! - first)
    }

    @Test fun missingDstHourDoesNotPermanentlyShiftLaterDays() {
        val first = at("2026-03-07T02:30:00-05:00[America/New_York]")
        val afterGap = at("2026-03-08T04:00:00-04:00[America/New_York]")
        assertEquals(
            at("2026-03-09T02:30:00-04:00[America/New_York]"),
            nextScheduledTaskTime(task(first, ScheduledTaskRepeat.DAILY, "America/New_York"), afterGap),
        )
    }
}
