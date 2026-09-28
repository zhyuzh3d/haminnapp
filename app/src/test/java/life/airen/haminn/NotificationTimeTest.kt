package life.airen.haminn

import life.airen.haminn.notification.NotificationTime
import life.airen.haminn.notification.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun dailyAndWeeklyKeepLocalWallClock() {
        val first = time(2026, 9, 13, 15, 0)
        assertEquals(time(2026, 9, 14, 15, 0), NotificationTime.nextAfter(first, Recurrence.DAILY, time(2026, 9, 13, 15, 1), zone))
        assertEquals(time(2026, 9, 20, 15, 0), NotificationTime.nextAfter(first, Recurrence.WEEKLY, time(2026, 9, 13, 15, 1), zone))
    }

    @Test fun monthlyClampsToLastDayAndReturnsToRequestedDay() {
        val first = time(2026, 1, 31, 9, 30)
        assertEquals(time(2026, 2, 28, 9, 30), NotificationTime.nextAfter(first, Recurrence.MONTHLY, time(2026, 2, 1, 0, 0), zone))
        assertEquals(time(2026, 3, 31, 9, 30), NotificationTime.nextAfter(first, Recurrence.MONTHLY, time(2026, 3, 1, 0, 0), zone))
    }

    @Test fun yearlyClampsLeapDay() {
        val first = time(2024, 2, 29, 8, 0)
        assertEquals(time(2025, 2, 28, 8, 0), NotificationTime.nextAfter(first, Recurrence.YEARLY, time(2025, 1, 1, 0, 0), zone))
        assertEquals(time(2028, 2, 29, 8, 0), NotificationTime.nextAfter(first, Recurrence.YEARLY, time(2028, 1, 1, 0, 0), zone))
    }

    @Test fun onceHasNoNextOccurrence() {
        assertEquals(null, NotificationTime.nextAfter(time(2026, 1, 1, 0, 0), Recurrence.ONCE, time(2026, 1, 1, 0, 1), zone))
    }

    @Test fun storedLocalAnchorFollowsTheCurrentDeviceTimezone() {
        val london = ZoneId.of("Europe/London")
        val anchor = LocalDateTime.of(2026, 9, 13, 15, 0)
        val now = ZonedDateTime.of(2026, 9, 13, 15, 1, 0, 0, london).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2026, 9, 14, 15, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(expected, NotificationTime.nextAfter(anchor, Recurrence.DAILY, now, london))
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
