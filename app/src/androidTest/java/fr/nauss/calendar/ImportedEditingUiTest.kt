package fr.nauss.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Uses IsolatedTestRunner only. No account, notifications or synchronization is started. */
@RunWith(AndroidJUnit4::class)
class ImportedEditingUiTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=isolatedApp(ApplicationProvider.getApplicationContext())
    private val cal="https://imported-ui-fixture.invalid/cal/"
    @Before fun setup() { app.store.addCalendars(listOf(DavClient.CalendarInfo(cal,"IMPORTED UI FIXTURE","#4285F4",true))) }
    @After fun cleanup() { app.store.applySnapshot(cal,emptyList()) }
    private fun raw(custom: Boolean=false,meeting: Boolean=false): String {
        val daylight=if(custom) "" else "BEGIN:DAYLIGHT\r\nDTSTART:19700329T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n"
        val standard=if(custom) "TZOFFSETFROM:+0700\r\nTZOFFSETTO:+0700\r\n" else "TZOFFSETFROM:+0200\r\nTZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n"
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//ISOLATED FIXTURE//EN\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\n${daylight}BEGIN:STANDARD\r\nDTSTART:19701025T030000\r\n${standard}END:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:imported-ui-fixture\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=Europe/Paris:20261117T090000\r\nDTEND;TZID=Europe/Paris:20261117T100000\r\nSUMMARY:IMPORTED UI FIXTURE\r\n"+(if(meeting) "ORGANIZER:mailto:fixture@example.invalid\r\n" else "")+"END:VEVENT\r\nEND:VCALENDAR\r\n"
    }
    private fun seed(raw: String): EventRow {
        app.store.applySnapshot(cal,listOf(DavClient.Resource(cal+"opaque","\"1\"",raw)))
        return app.store.events().single { it.calendar==cal }
    }
    private fun editor(row: EventRow) { compose.setContent { MaterialTheme { EventEditor(app,CalendarState(calendars=app.store.calendars()),row.id,LocalDateTime.of(2026,11,17,9,0),{},{}) } } }
    @Test fun commonEmbeddedTimezoneEnablesDatesButPreservesTimezoneAndRecurrence() {
        editor(seed(raw()))
        compose.onNodeWithTag("event_title").assertIsEnabled().assertIsDisplayed()
        val titleBounds=compose.onNodeWithTag("event_title").fetchSemanticsNode().boundsInRoot
        assertTrue("Title must precede capability prose",titleBounds.top<compose.onRoot().fetchSemanticsNode().boundsInRoot.height/3)
        compose.onNodeWithText(app.getString(R.string.edit_complex_series)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.edit_recurrence_preserved)).assertDoesNotExist()
        compose.onNodeWithTag("event_start_date").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("event_end_time").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("event_timezone").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.edit_embedded_timezone)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("event_repeat").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.edit_repeat_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("event_reminder").performScrollTo().assertIsEnabled()
    }
    @Test fun customTimezoneLeavesTextEditableAndExplainsDisabledDates() {
        editor(seed(raw(custom=true)))
        compose.onNodeWithTag("editing_restriction").assertTextEquals(app.getString(R.string.edit_custom_timezone))
        compose.onNodeWithTag("event_title").assertIsEnabled()
        compose.onNodeWithTag("event_start_date").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("event_end_time").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("event_notes").performScrollTo().assertIsEnabled()
    }
    @Test fun embeddedSeriesKeepsTimeLocksAndSpecificVisibleReasons() {
        editor(seed(raw().replace("SUMMARY:","RRULE:FREQ=WEEKLY\r\nSUMMARY:")))
        compose.onNodeWithTag("event_title").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("event_start_date").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("editing_restriction").performScrollTo().assertTextEquals(app.getString(R.string.edit_complex_series))
        compose.onNodeWithTag("event_repeat").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.edit_recurrence_preserved)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.edit_repeat_unavailable)).assertDoesNotExist()
    }
    @Test fun opaqueResourceShowsModifyAction() {
        val row=seed(raw());var edited=false
        compose.setContent { MaterialTheme { EventDetail(app,row.id,null,{}, {edited=true},{},{}) } }
        compose.onAllNodesWithText("IMPORTED UI FIXTURE").onFirst().assertIsDisplayed()
        val title=compose.onNodeWithText(app.getString(R.string.event_details))
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Compact detail heading stays on one line",1,layouts.single().lineCount)
        compose.onNodeWithText(app.getString(R.string.edit)).assertIsDisplayed()
        compose.onNodeWithTag("edit_event").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(edited) }
        assertEquals(0,app.store.pending(cal).size)
    }
    @Test fun invitationShowsDisabledModifyAndReasonInsteadOfMissingPencil() {
        val row=seed(raw(meeting=true))
        compose.setContent { MaterialTheme { EventDetail(app,row.id,null,{},{},{},{}) } }
        compose.onNodeWithTag("edit_event").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("editing_restriction").performScrollTo().assertExists()
    }
}
