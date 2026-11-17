package fr.nauss.calendar

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarRangeTest {
    @Test fun weekStartsMondayAndMonthIncludesLeadingDays() {
        assertEquals(LocalDate.of(2026,11,16),firstDay(LocalDate.of(2026,11,19),CalendarView.WEEK))
        assertEquals(LocalDate.of(2026,10,26),firstDay(LocalDate.of(2026,11,17),CalendarView.MONTH))
    }
    @Test fun selectedDateStaysForDayAndAgenda() {
        val date=LocalDate.of(2026,12,31)
        assertEquals(date,firstDay(date,CalendarView.DAY));assertEquals(date,firstDay(date,CalendarView.AGENDA))
    }
}
