package fr.nauss.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class TimedGeometryTest {
    @get:Rule val compose=createComposeRule()
    @Test fun adjacentHalfHoursUseHalfAnHourNotFortyFiveMinutes() {
        val day=LocalDate.now();val zone=ZoneId.systemDefault()
        val calendar=CalendarRow("local","","#1A73E8",false,true,true,true)
        fun event(id: String,start: LocalTime): DisplayEvent {
            val raw=IcsCodec.save(null,IcsCodec.Draft().apply { title="SYNTHETIC $id";this.start=day.atTime(start);end=this.start.plusMinutes(30) })
            return DisplayEvent(EventRow(id,"local",id,id,raw,null,false,false,"",false),IcsCodec.expand(raw,day,day.plusDays(1),zone).single(),calendar)
        }
        val first=event("short-first",LocalTime.of(9,30));val second=event("short-second",LocalTime.of(10,0))
        compose.setContent { MaterialTheme { TimedCalendar(day,1,listOf(first,second),{},{},{}) } }
        compose.waitForIdle()
        val labels=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("hour_label_9").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(labels) }
        assertEquals("Hour labels must remain on one line at large font",1,labels.single().lineCount)
        val label=labels.single()
        assertEquals(5,label.getLineEnd(0));assertFalse(label.isLineEllipsized(0))
        // Compose can report didOverflowWidth for a fractional-pixel rounding difference.
        assertTrue("Hour label exceeds its actual bounds",label.getLineRight(0)<=label.size.width+1f)
        val a=compose.onNodeWithTag("block_short-first").fetchSemanticsNode().boundsInRoot
        val b=compose.onNodeWithTag("block_short-second").fetchSemanticsNode().boundsInRoot
        assertTrue("Adjacent blocks must not overlap: $a $b",a.bottom<=b.top+1f)
        assertEquals("Full width for adjacent, non-overlapping intervals",a.left,b.left,1f)
        assertEquals(a.width,b.width,1f)
        assertEquals("Proportional height matches the half-hour start separation",b.top-a.top,a.height,8f)
        compose.onNodeWithTag("block_short-first").assertContentDescriptionContains("SYNTHETIC short-first",substring=true)
    }
}
