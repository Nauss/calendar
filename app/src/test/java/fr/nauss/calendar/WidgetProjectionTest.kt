package fr.nauss.calendar

import fr.nauss.calendar.core.IcsCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WidgetProjectionTest {
    private val zone=ZoneId.of("Europe/Paris")
    private val day=LocalDate.of(2026,11,17)
    private val calendar=CalendarRow("local","","#1A73E8",false,true,true,true)
    private fun record(start: String="20261117T090000Z",end: String="20261117T100000Z",extra: String="",id: String="one",allDay: Boolean=false): EventRow {
        val param=if(allDay) ";VALUE=DATE" else ""
        val raw="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Agenda Test//EN\r\nBEGIN:VEVENT\r\nUID:$id\r\nDTSTART$param:$start\r\nDTEND$param:$end\r\nSUMMARY:TEST\r\n$extra"+"END:VEVENT\r\nEND:VCALENDAR\r\n"
        return EventRow(id,"local",id,id,raw,null,false,false,"",false)
    }
    private fun project(records: List<EventRow>,calendars: List<CalendarRow> = listOf(calendar),from: LocalDate=day,until: LocalDate=day.plusDays(31),limit: Int=64)=
        WidgetProjection.planning(records,calendars,from,until,zone,rowLimit=limit)

    @Test fun allDayEndIsExclusive() {
        val rows=project(listOf(record("20261117","20261119",allDay=true))).rows
        assertEquals(listOf(day,day.plusDays(1)),rows.map { it.day })
        assertEquals(listOf(1,2),rows.map { it.dayIndex });assertTrue(rows.all { it.dayCount==2 })
    }
    @Test fun overlappingMultiDayIsClippedToVisibleDays() {
        val rows=project(listOf(record("20261115T220000Z","20261118T010000Z"))).rows
        assertEquals(listOf(day,day.plusDays(1)),rows.map { it.day })
        assertEquals(3,rows.first().dayIndex)
    }
    @Test fun midnightEndDoesNotCreateExtraDay() {
        assertEquals(1,project(listOf(record("20261117T200000Z","20261117T230000Z"))).rows.size)
    }
    @Test fun zeroDurationAtMidnightBelongsOnlyToThatDay() {
        val rows=project(listOf(record("20261116T230000Z","20261116T230000Z"))).rows
        assertEquals(listOf(day),rows.map { it.day })
    }
    @Test fun hiddenAndDeletedAreExcludedWithoutDeduplicatingCalendars() {
        assertTrue(project(listOf(record()),listOf(calendar.copy(visible=false))).rows.isEmpty())
        assertTrue(project(listOf(record().copy(deleted=true))).rows.isEmpty())
        val other=calendar.copy(id="other")
        assertEquals(2,project(listOf(record(),record(id="two").copy(calendar="other")),listOf(calendar,other)).rows.size)
    }
    @Test fun recurrenceUsesExactOccurrenceAndDistinctDailyKeys() {
        val record=record(extra="RRULE:FREQ=DAILY;COUNT=3\r\n")
        val rows=project(listOf(record)).rows
        assertEquals(3,rows.size);assertEquals(3,rows.map { it.key }.toSet().size)
        val target=WidgetRoute.event(rows[2])
        val resolved=WidgetRoutes.resolve(target,listOf(record),listOf(calendar),zone)
        assertEquals(rows[2].event.occurrence.start,resolved.event!!.occurrence.start)
    }
    @Test fun missingHiddenDeletedAndMovedOccurrencesFailClosed() {
        val original=record();val route=WidgetRoute.event(project(listOf(original)).rows.first())
        assertEquals(RouteKind.UNAVAILABLE,WidgetRoutes.resolve(route,emptyList(),listOf(calendar),zone).kind)
        assertEquals(RouteKind.UNAVAILABLE,WidgetRoutes.resolve(route,listOf(original.copy(deleted=true)),listOf(calendar),zone).kind)
        assertEquals(RouteKind.UNAVAILABLE,WidgetRoutes.resolve(route,listOf(original),listOf(calendar.copy(visible=false)),zone).kind)
        assertEquals(RouteKind.UNAVAILABLE,WidgetRoutes.resolve(route,listOf(record("20261118T090000Z","20261118T100000Z")),listOf(calendar),zone).kind)
    }
    @Test fun invalidRoutesNeverFallThroughToCreate() {
        listOf(emptyMap(),mapOf("kind" to "event"),mapOf("kind" to "create","date" to "bad"),mapOf("kind" to "day","date" to "999999-12-12")).forEach {
            assertEquals(RouteKind.UNAVAILABLE,WidgetRoutes.parse(it).kind)
        }
    }
    @Test fun routeRoundTripsOnlyIdentifiersAndDates() {
        val row=project(listOf(record())).rows.first();val route=WidgetRoute.event(row)
        assertEquals(route,WidgetRoutes.parse(route.fields()))
        assertEquals(setOf("kind","date","id","start","occurrence"),route.fields().keys)
        assertFalse(route.fields().values.any { it.contains("TEST") || it.contains("VCALENDAR") })
    }
    @Test fun emptyLoadingAndErrorAreDistinctAndRetainGoodRows() {
        assertEquals(WidgetStatus.EMPTY,project(emptyList()).status)
        assertEquals(WidgetStatus.LOADING,WidgetSnapshot.loading().status)
        assertEquals(WidgetStatus.ERROR,WidgetSnapshot.error().status)
        val result=project(listOf(record(),record(id="broken").copy(raw="invalid")))
        assertEquals(WidgetStatus.ERROR,result.status);assertEquals(1,result.rows.size)
    }
    @Test fun rowAndRecordBoundsAreExplicitNeverSilent() {
        val result=project(listOf(record(extra="RRULE:FREQ=DAILY;COUNT=3\r\n")),limit=2)
        assertEquals(2,result.rows.size);assertTrue(result.truncated)
        val bounded=WidgetProjection.planning(listOf(record(),record(id="two")),listOf(calendar),day,day.plusDays(1),zone,recordLimit=1)
        assertTrue(bounded.truncated)
    }
    @Test fun totalOccurrenceBudgetIsExplicitAcrossRecords() {
        val result=WidgetProjection.planning(listOf(record(extra="RRULE:FREQ=DAILY;COUNT=3\r\n")),listOf(calendar),day,day.plusDays(31),zone,occurrenceLimit=2)
        assertTrue(result.truncated);assertEquals(2,result.rows.size)
    }
    @Test fun searchProjectionDoesNotClipToPlanningWindow() {
        val records=listOf(record("20260103T090000Z","20260103T100000Z"),record("20261229T090000Z","20261229T100000Z",id="late"))
        val events=records.flatMap { r -> IcsCodec.expand(r.raw,day.withDayOfYear(1),day.withDayOfYear(1).plusYears(1),zone).map { DisplayEvent(r,it,calendar) } }
        val rows=agendaRows(events,day.withDayOfYear(1),day.withDayOfYear(1).plusYears(1),zone)
        assertEquals(listOf(LocalDate.of(2026,1,3),LocalDate.of(2026,12,29)),rows.map { it.day })
    }
    @Test fun allDaySortsBeforeTimedAndOverlapIsNotLost() {
        val result=project(listOf(record(),record(id="overlap"),record("20261117","20261118",id="all",allDay=true)))
        assertEquals(3,result.rows.size);assertTrue(result.rows.first().event.occurrence.allDay)
    }
    @Test fun monthHas42DaysYearRolloverAndPerInstanceState() {
        val today=LocalDate.of(2026,12,17)
        val states=mapOf(11 to "2026-12",12 to "2026-11")
        assertEquals(YearMonth.of(2027,1),WidgetMonths.move(states[11],1,today))
        assertEquals(YearMonth.of(2026,11),WidgetMonths.current(states[12],today))
        val cells=WidgetMonths.days(YearMonth.of(2027,1))
        assertEquals(42,cells.size);assertEquals(DayOfWeek.MONDAY,cells.first().dayOfWeek)
        assertEquals(LocalDate.of(2026,12,28),cells.first())
    }
    @Test fun monthNavigationIsBoundedAndTodayResets() {
        assertEquals(YearMonth.from(day).plusMonths(120),WidgetMonths.move(YearMonth.from(day).plusMonths(120).toString(),1,day))
        assertEquals(YearMonth.from(day),WidgetMonths.current("invalid",day))
        assertEquals(YearMonth.from(day),WidgetMonths.move("2027-04",0,day))
    }
    @Test fun sizeFallbackAndLandscapeAreBounded() {
        assertEquals(WidgetSize(320,360),WidgetSize.resolve(null,null))
        assertEquals(WidgetSize(280,180),WidgetSize.resolve(280,180))
        assertEquals(WidgetSize(120,100),WidgetSize.resolve(-1,0))
    }
    @Test fun refreshKeysOnlyIncludeWidgetRelevantMetadata() {
        listOf("theme","sync_error","sync_running","last_sync").forEach { assertTrue(widgetMetadataInvalidates(it)) }
        listOf("view","endpoint","widget:11:month","submission:example").forEach { assertFalse(widgetMetadataInvalidates(it)) }
    }
}
