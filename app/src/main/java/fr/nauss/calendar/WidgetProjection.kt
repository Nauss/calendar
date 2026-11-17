package fr.nauss.calendar

import fr.nauss.calendar.core.IcsCodec
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Shared day projection, independent of Compose, RemoteViews and the device clock. */
data class AgendaRow(val day: LocalDate,val event: DisplayEvent,val dayIndex: Int,val dayCount: Int) {
    val key: String get()="$day:${event.key}"
}
fun agendaRows(events: List<DisplayEvent>,from: LocalDate,until: LocalDate,zone: ZoneId): List<AgendaRow> = events.flatMap { event ->
    val first=event.occurrence.start.atZone(zone).toLocalDate()
    val last=(if(event.occurrence.end>event.occurrence.start) event.occurrence.end.minusNanos(1) else event.occurrence.start).atZone(zone).toLocalDate()
    val count=ChronoUnit.DAYS.between(first,last).toInt()+1
    generateSequence(maxOf(first,from)) { it.plusDays(1) }.takeWhile { it<=last && it<until }.map { day ->
        AgendaRow(day,event,ChronoUnit.DAYS.between(first,day).toInt()+1,count)
    }.toList()
}.sortedWith(compareBy<AgendaRow> { it.day }.thenBy { !it.event.occurrence.allDay }.thenBy { it.event.occurrence.start }.thenBy { it.event.occurrence.title }.thenBy { it.key })

enum class WidgetStatus { READY, EMPTY, LOADING, ERROR }
data class WidgetSnapshot(val rows: List<AgendaRow>,val status: WidgetStatus,val truncated: Boolean=false) {
    companion object {
        fun loading()=WidgetSnapshot(emptyList(),WidgetStatus.LOADING)
        fun error()=WidgetSnapshot(emptyList(),WidgetStatus.ERROR)
    }
}
object WidgetProjection {
    const val ROW_LIMIT=64
    const val RECORD_LIMIT=512
    const val OCCURRENCE_LIMIT=4096
    fun planning(records: List<EventRow>,calendars: List<CalendarRow>,from: LocalDate,until: LocalDate,zone: ZoneId,
                 rowLimit: Int=ROW_LIMIT,recordLimit: Int=RECORD_LIMIT,occurrenceLimit: Int=OCCURRENCE_LIMIT): WidgetSnapshot {
        require(until>from && ChronoUnit.DAYS.between(from,until)<=370 && rowLimit>0 && recordLimit>0 && occurrenceLimit>0)
        val visible=calendars.filter { it.visible }.associateBy { it.id }
        val candidates=records.filter { !it.deleted && it.calendar in visible }
        var error=false;var truncated=candidates.size>recordLimit
        // Bound recurrence work. A partial projection is always labelled, never presented as complete.
        val events=mutableListOf<DisplayEvent>()
        for(record in candidates.take(recordLimit)) {
            if(events.size>=occurrenceLimit) { truncated=true;break }
            try {
                val occurrences=IcsCodec.expand(record.raw,from,until,zone)
                val room=occurrenceLimit-events.size
                if(occurrences.size>room) truncated=true
                events.addAll(occurrences.take(room).map { DisplayEvent(record,it,visible.getValue(record.calendar)) })
            } catch(e: Exception) { error=true }
        }
        val rows=agendaRows(events,from,until,zone)
        return WidgetSnapshot(rows.take(rowLimit),when { error->WidgetStatus.ERROR;rows.isEmpty()->WidgetStatus.EMPTY;else->WidgetStatus.READY },rows.size>rowLimit || truncated)
    }
}

enum class RouteKind { PLANNING, DAY, CREATE, EVENT, UNAVAILABLE }
data class WidgetRoute(val kind: RouteKind,val date: LocalDate?=null,val id: String?=null,val start: Instant?=null,val occurrence: String?=null) {
    fun fields(): Map<String,String> = buildMap {
        put("kind",kind.name.lowercase());date?.let { put("date",it.toString()) };id?.let { put("id",it) };start?.let { put("start",it.toString()) };occurrence?.let { put("occurrence",it) }
    }
    companion object { fun event(row: AgendaRow)=WidgetRoute(RouteKind.EVENT,row.day,row.event.record.id,row.event.occurrence.start,row.event.occurrence.key) }
}
data class ResolvedWidgetRoute(val kind: RouteKind,val date: LocalDate?=null,val event: DisplayEvent?=null)
object WidgetRoutes {
    fun parse(fields: Map<String,String>): WidgetRoute = try {
        val kind=RouteKind.valueOf(fields.getValue("kind").uppercase())
        val date=LocalDate.parse(fields.getValue("date"));require(date.year in 1900..2200)
        if(kind==RouteKind.EVENT) {
            val id=fields.getValue("id");require(id.isNotBlank() && id.length<=128)
            val start=Instant.parse(fields.getValue("start"));val key=fields.getValue("occurrence")
            require(key.isNotBlank() && key.length<=128)
            WidgetRoute(kind,date,id,start,key)
        } else WidgetRoute(kind,date)
    } catch(e: Exception) { WidgetRoute(RouteKind.UNAVAILABLE) }
    fun resolve(route: WidgetRoute,records: List<EventRow>,calendars: List<CalendarRow>,zone: ZoneId): ResolvedWidgetRoute {
        if(route.kind!=RouteKind.EVENT) return ResolvedWidgetRoute(route.kind,route.date)
        val unavailable=ResolvedWidgetRoute(RouteKind.UNAVAILABLE)
        val day=route.date ?: return unavailable
        val record=records.firstOrNull { it.id==route.id && !it.deleted } ?: return unavailable
        val calendar=calendars.firstOrNull { it.id==record.calendar && it.visible } ?: return unavailable
        return try {
            val occurrence=IcsCodec.expand(record.raw,day,day.plusDays(1),zone).firstOrNull { it.start==route.start && it.key==route.occurrence } ?: return unavailable
            ResolvedWidgetRoute(RouteKind.EVENT,day,DisplayEvent(record,occurrence,calendar))
        } catch(e: Exception) { unavailable }
    }
}
object WidgetMonths {
    fun current(stored: String?,today: LocalDate): YearMonth {
        val now=YearMonth.from(today)
        return (runCatching { YearMonth.parse(stored) }.getOrNull() ?: now).coerceIn(now.minusMonths(120),now.plusMonths(120))
    }
    fun move(stored: String?,delta: Int,today: LocalDate): YearMonth = if(delta==0) YearMonth.from(today) else
        current(stored,today).plusMonths(delta.coerceIn(-1,1).toLong()).coerceIn(YearMonth.from(today).minusMonths(120),YearMonth.from(today).plusMonths(120))
    fun days(month: YearMonth): List<LocalDate> {
        val first=month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L..41L).map { first.plusDays(it) }
    }
}
data class WidgetSize(val width: Int,val height: Int) {
    companion object { fun resolve(width: Int?,height: Int?)=WidgetSize((width ?: 320).coerceIn(120,1200),(height ?: 360).coerceIn(100,1600)) }
}
fun widgetMetadataInvalidates(key: String)=key in setOf("theme","sync_error","sync_running","last_sync")
