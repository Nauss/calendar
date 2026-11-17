package fr.nauss.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class PlanningUiTest {
    @get:Rule val compose=createComposeRule()
    private val today=LocalDate.now()
    private val calendar=CalendarRow("isolated","ISOLATED","#1A73E8",false,true,true,true)
    private fun event(index: Int,days: Long=1): DisplayEvent {
        val draft=IcsCodec.Draft().apply { title="ISOLATED PLANNING $index";start=today.plusDays(index.toLong()).atStartOfDay();end=start.plusDays(days);allDay=true }
        val raw=IcsCodec.save(null,draft)
        val record=EventRow(index.toString(),calendar.id,index.toString(),index.toString(),raw,null,false,false,"",false)
        return DisplayEvent(record,IcsCodec.expand(raw,today,today.plusDays(90),ZoneId.systemDefault()).first(),calendar)
    }
    @Test fun pollingKeepsScrollAndExplicitNavigationResetsIt() {
        var events by mutableStateOf((0..40).map { event(it) })
        var navigation by mutableIntStateOf(0)
        compose.setContent { MaterialTheme { EventAgenda(events,{},"EMPTY",today,today.plusDays(90),navigation) } }
        compose.onNodeWithTag("agenda_list").performScrollToIndex(20)
        fun scroll()=compose.onNodeWithTag("agenda_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before=scroll();assertTrue(before>0f)
        compose.runOnIdle { events=events.map { it.copy(record=it.record.copy(raw=it.record.raw+" ")) } }
        compose.waitForIdle();assertEquals(before,scroll(),0.5f)
        compose.runOnIdle { navigation++ }
        compose.waitForIdle();assertEquals(0f,scroll(),0.5f)
    }
    @Test fun multiDayAppearsEachDayAndSelectionKeepsOccurrence() {
        val event=event(0,2);var selected: DisplayEvent?=null
        compose.setContent { MaterialTheme { EventAgenda(listOf(event),{selected=it},"EMPTY",today,today.plusDays(2)) } }
        compose.onAllNodesWithTag("event_0").assertCountEquals(2)
        compose.onAllNodesWithTag("event_0")[1].performClick()
        compose.runOnIdle { assertEquals(event.occurrence.start,selected!!.occurrence.start) }
    }
}
