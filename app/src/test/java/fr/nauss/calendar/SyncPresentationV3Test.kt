package fr.nauss.calendar

import androidx.compose.ui.graphics.luminance
import fr.nauss.calendar.core.IcsCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SyncPresentationV3Test {
    private val calendar=CalendarRow("fixture-calendar","Fixture","#123456",true,true,true,true)
    private fun record(id: String,deleted: Boolean=false,pending: Boolean=false,error: String="")=
        EventRow(id,calendar.id,"fixture-$id.ics",id,"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Synthetic//EN\r\nBEGIN:VEVENT\r\nUID:$id\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261101T090000Z\r\nRRULE:FREQ=DAILY;COUNT=2\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n","\"1\"",deleted,pending,error,false)
    @Test fun unsupportedDoesNotBecomeDeletionOrConflictAndOverlapsHaveOneCategory() {
        val unsupported=record("unsupported")
        val deleted=record("delete",deleted=true,pending=true)
        val conflict=record("conflict",pending=true,error="CONFLICT")
        val deleteConflict=record("delete-conflict",deleted=true,pending=true,error="CONFLICT")
        val network=record("network",pending=true,error="NETWORK")
        val sections=reviewSections(listOf(unsupported,deleted,conflict,deleteConflict,network),listOf(unsupported,conflict,deleted))
        assertEquals(listOf("conflict","delete-conflict"),sections.single { it.kind==ReviewKind.CONFLICT }.records.map { it.id })
        assertEquals(listOf("delete"),sections.single { it.kind==ReviewKind.PENDING_DELETE }.records.map { it.id })
        assertEquals(listOf("unsupported"),sections.single { it.kind==ReviewKind.UNSUPPORTED }.records.map { it.id })
        assertEquals(4,sections.flatMap { it.records }.distinctBy { it.id }.size)
    }
    @Test fun allFailureCausesRemainDistinctAndCompoundErrorsAreNotHidden() {
        val codes=listOf("AUTH","PERMISSION","TLS","NETWORK","SERVER","REPORT","RETRY","ACCOUNT","SCHEDULE","CONFLICT")
        assertEquals(codes.size,codes.map(::syncErrorMessages).distinct().size)
        assertEquals(syncErrorMessages("CONFLICT")+syncErrorMessages("NETWORK"),syncErrorMessages("CONFLICT,NETWORK,CONFLICT"))
        assertEquals(emptyList<Int>(),syncErrorMessages(""))
        assertEquals(listOf(R.string.sync_error),syncErrorMessages("https://private.invalid/event-secret"))
    }
    @Test fun unsupportedReasonsAreSpecificPublicMessagesNotExceptionText() {
        val codes=listOf("LIMIT","RECURRENCE","TIMEZONE","DATA")
        assertEquals(codes.size,codes.map(::unsupportedReasonMessage).distinct().size)
        assertEquals(R.string.unsupported_data_reason,unsupportedReasonMessage("private-fixture-title"))
        assertEquals(R.string.unsupported_data_reason,unsupportedReasonMessage(null))
    }
    @Test fun planningColorsUseUidNotCalendarOccurrenceOrMetadata() {
        val row=record("stable-fixture")
        val occurrences=IcsCodec.expand(row.raw,LocalDate.of(2026,11,1),LocalDate.of(2026,11,4),ZoneOffset.UTC)
        val first=DisplayEvent(row,occurrences.first(),calendar)
        val moved=DisplayEvent(row.copy(id="other-local-id",calendar="other",href="other.ics",etag="\"2\""),occurrences.last(),calendar.copy(id="other",color="#FF0000"))
        assertEquals(planningEventColor(first),planningEventColor(moved))
        val colors=(0..63).map { id ->
            val other=record("fixture-$id")
            planningEventColor(DisplayEvent(other,IcsCodec.expand(other.raw,LocalDate.of(2026,11,1),LocalDate.of(2026,11,2),ZoneOffset.UTC).first(),calendar))
        }
        assertTrue("Distinct event UIDs must have a varied palette",colors.distinct().size>=6)
        colors.forEach { color ->
            val foreground=calendarEventTextColor(color)
            val ratio=(maxOf(color.luminance(),foreground.luminance())+.05f)/(minOf(color.luminance(),foreground.luminance())+.05f)
            assertTrue("Normal text contrast",ratio>=4.5f);assertEquals(1f,color.alpha)
        }
        assertEquals("#123456",calendar.color);assertEquals("stable-fixture",row.uid)
    }
}
