package pl.poznan.offdojade.data.gtfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.poznan.offdojade.data.local.entity.CalendarDateEntity
import pl.poznan.offdojade.data.local.entity.CalendarEntity
import java.time.LocalDate

class ServiceCalendarResolverAndCsvTest {

    @Test
    fun calendarResolver_appliesDateRangeWeekdaysAndExceptions() {
        val calendars = listOf(
            CalendarEntity(
                serviceId = "WEEKDAY",
                monday = true, tuesday = true, wednesday = true, thursday = true, friday = true,
                saturday = false, sunday = false,
                startDate = 20261101, endDate = 20261130
            ),
            CalendarEntity(
                serviceId = "WEEKEND",
                monday = false, tuesday = false, wednesday = false, thursday = false, friday = false,
                saturday = true, sunday = true,
                startDate = 20261101, endDate = 20261130
            )
        )

        // 2026-11-26 — четверг; представим, что WEEKDAY отменен (2), а HOLIDAY добавлен (1)
        val date = LocalDate.of(2026, 11, 26)
        val exceptions = listOf(
            CalendarDateEntity("WEEKDAY", 20261126, ServiceCalendarResolver.EXCEPTION_REMOVED),
            CalendarDateEntity("HOLIDAY", 20261126, ServiceCalendarResolver.EXCEPTION_ADDED)
        )

        val active = ServiceCalendarResolver.resolveActiveServiceIds(date, calendars, exceptions)
        assertFalse(active.contains("WEEKDAY"))
        assertFalse(active.contains("WEEKEND"))
        assertTrue(active.contains("HOLIDAY"))
    }

    @Test
    fun csvParser_handlesQuotesAndAfterMidnightGtfsTimes() {
        val cols = CsvLineParser.parseLine("""101,"Rondo ""Kaponiera"", peron 2",52.4082,16.9125,A""")
        assertEquals(5, cols.size)
        assertEquals("101", cols[0])
        assertEquals("""Rondo "Kaponiera", peron 2""", cols[1])
        assertEquals("A", cols[4])

        // 25:15:30 = 25*3600 + 15*60 + 30 = 90930
        assertEquals(90930, CsvLineParser.parseGtfsTimeToSeconds("25:15:30"))
    }
}
