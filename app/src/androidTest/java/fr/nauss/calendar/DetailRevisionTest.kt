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

@RunWith(AndroidJUnit4::class)
class DetailRevisionTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=isolatedApp(ApplicationProvider.getApplicationContext())
    private val cal="https://revision-fixture.invalid/cal/"
    private lateinit var selected: DisplayEvent
    @Before fun setup() {
        val db=app.store;db.addCalendars(listOf(DavClient.CalendarInfo(cal,"REVISION FIXTURE","#4285F4",true)))
        val raw=IcsCodec.save(null,IcsCodec.Draft().apply { title="VISIBLE REVISION";start=LocalDate.now().atTime(9,0);end=start.plusHours(1) })
        db.applySnapshot(cal,listOf(DavClient.Resource(cal+"fixture.ics","\"1\"",raw)))
        val record=db.events().single { it.calendar==cal }
        selected=DisplayEvent(record,IcsCodec.expand(raw,LocalDate.now(),LocalDate.now().plusDays(1),ZoneId.systemDefault()).single(),db.calendar(cal)!!)
        compose.setContent { MaterialTheme { EventDetail(app,record.id,selected,{},{},{},{}) } }
        compose.onNodeWithText("VISIBLE REVISION").assertExists()
    }
    private fun refresh() {
        val d=IcsCodec.inspect(selected.record.raw,ZoneId.systemDefault()).draft.apply { title="UNSEEN REVISION" }
        app.store.applySnapshot(cal,listOf(DavClient.Resource(selected.record.href,"\"2\"",IcsCodec.save(selected.record.raw,d))))
    }
    @After fun cleanup() { app.store.applySnapshot(cal,emptyList()) }
    @Test fun refreshInvalidatesOldOccurrenceInsteadOfAuthorizingNewRevision() {
        refresh()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("stale_detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("delete_event").assertDoesNotExist()
        compose.onNodeWithTag("edit_event").assertDoesNotExist()
        assertEquals(0,app.store.pending(cal).size)
    }
    @Test fun revisionChangedWhileConfirmationOpenCannotBeDeleted() {
        compose.onNodeWithTag("delete_event").performClick()
        refresh()
        compose.onNodeWithTag("confirm_action").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(app.getString(R.string.operation_error)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("UNSEEN REVISION",IcsCodec.inspect(app.store.event(selected.record.id)!!.raw,ZoneId.systemDefault()).draft.title)
        assertEquals(0,app.store.pending(cal).size)
    }
}
