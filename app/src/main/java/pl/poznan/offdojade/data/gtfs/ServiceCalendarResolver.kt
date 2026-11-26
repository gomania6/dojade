package pl.poznan.offdojade.data.gtfs

import pl.poznan.offdojade.data.local.entity.CalendarDateEntity
import pl.poznan.offdojade.data.local.entity.CalendarEntity
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Выбирает точное множество активных `service_id` на выбранную дату с учетом:
 * 1. Диапазона `start_date..end_date` и дней недели из `calendar.txt`.
 * 2. Исключений из `calendar_dates.txt` (`exception_type == 1` — добавлен, `2` — отменен).
 */
object ServiceCalendarResolver {

    const val EXCEPTION_ADDED = 1
    const val EXCEPTION_REMOVED = 2

    fun toYyyymmdd(date: LocalDate): Int =
        date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    fun resolveActiveServiceIds(
        date: LocalDate,
        calendars: List<CalendarEntity>,
        calendarDatesForDate: List<CalendarDateEntity>
    ): Set<String> {
        val dateInt = toYyyymmdd(date)
        val dayOfWeek = date.dayOfWeek
        val active = HashSet<String>(calendars.size + calendarDatesForDate.size)

        for (cal in calendars) {
            if (dateInt in cal.startDate..cal.endDate && isDayActive(cal, dayOfWeek)) {
                active.add(cal.serviceId)
            }
        }

        for (ex in calendarDatesForDate) {
            if (ex.date != dateInt) continue
            when (ex.exceptionType) {
                EXCEPTION_ADDED -> active.add(ex.serviceId)
                EXCEPTION_REMOVED -> active.remove(ex.serviceId)
            }
        }

        return active
    }

    private fun isDayActive(cal: CalendarEntity, dayOfWeek: DayOfWeek): Boolean = when (dayOfWeek) {
        DayOfWeek.MONDAY -> cal.monday
        DayOfWeek.TUESDAY -> cal.tuesday
        DayOfWeek.WEDNESDAY -> cal.wednesday
        DayOfWeek.THURSDAY -> cal.thursday
        DayOfWeek.FRIDAY -> cal.friday
        DayOfWeek.SATURDAY -> cal.saturday
        DayOfWeek.SUNDAY -> cal.sunday
    }
}
