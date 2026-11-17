package fr.nauss.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.nauss.calendar.core.IcsCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.*
import java.time.temporal.TemporalAdjusters

data class DisplayEvent(val record: EventRow,val occurrence: IcsCodec.Occurrence,val calendar: CalendarRow) { val key: String get()="${record.id}:${occurrence.key}" }
data class CalendarState(val calendars: List<CalendarRow> = emptyList(),val events: List<DisplayEvent> = emptyList(),val records: List<EventRow> = emptyList(),val unsupported: List<EventRow> = emptyList(),val pending: Int = 0,val syncError: String = "",val syncing: Boolean = false,val theme: String = "system",val defaultCalendar: String = "local",val lastSync: String = "",val loading: Boolean = true,val reminderError: String = "")
enum class CalendarView(val label: Int,val days: Int) { AGENDA(R.string.agenda,90),DAY(R.string.day,1),THREE(R.string.three_days,3),WEEK(R.string.week,7),MONTH(R.string.month,42) }
fun firstDay(date: LocalDate,view: CalendarView): LocalDate = when(view) { CalendarView.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));CalendarView.MONTH -> date.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));else -> date }

class CalendarModel(application: Application) : AndroidViewModel(application) {
    val app=application as AgendaApplication
    private val mutable=MutableStateFlow(CalendarState())
    val state=mutable.asStateFlow()
    private var job: Job?=null
    fun load(date: LocalDate,view: CalendarView,searching: Boolean) {
        job?.cancel();job=viewModelScope.launch {
            val next=withContext(Dispatchers.Default) {
                val db=app.store;val calendars=db.calendars();val records=db.events(true);val unsupported=mutableListOf<EventRow>();val zone=ZoneId.systemDefault()
                val from=if(searching) date.withDayOfYear(1) else firstDay(date,view)
                val until=if(searching) from.plusYears(1) else from.plusDays(view.days.toLong())
                val events=records.filter { !it.deleted }.flatMap { record ->
                    val cal=calendars.firstOrNull { it.id==record.calendar && it.visible } ?: return@flatMap emptyList()
                    try { IcsCodec.expand(record.raw,from,until,zone).map { DisplayEvent(record,it,cal) } }
                    catch(e: Exception) { unsupported.add(record);emptyList() }
                }.sortedWith(compareBy<DisplayEvent> { it.occurrence.start }.thenBy { it.occurrence.title })
                CalendarState(calendars,events,records,unsupported,db.pendingCount(),db.meta("sync_error"),db.meta("sync_running")=="1",db.meta("theme","system"),db.meta("default_calendar","local"),db.meta("last_sync"),false,db.meta("reminder_error"))
            }
            mutable.value=next
        }
    }
}
