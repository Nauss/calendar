package fr.nauss.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Production creation policy on synthetic isolated storage; no account, network or real event. */
@RunWith(AndroidJUnit4::class)
class NasOnlyCreationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var app: IsolatedAgendaApplication
    private val cal="https://nas-create-fixture.invalid/main/"
    private val title="ISOLATED NAS CREATION"
    @Volatile private var created: String?=null
    @Before fun setup() {
        app=isolatedApp(ApplicationProvider.getApplicationContext());app.allowLocalCreation=false
        app.store.addCalendars(listOf(DavClient.CalendarInfo(cal,"Main","#4285F4",true)))
    }
    @After fun cleanup() {
        created?.let { app.store.delete(it) };app.allowLocalCreation=true
    }
    private fun editor(calendars: List<CalendarRow>) {
        compose.setContent { MaterialTheme { EventEditor(app,CalendarState(calendars=calendars,defaultCalendar="local"),null,LocalDateTime.of(2026,11,18,9,0),{}, { created=it }) } }
        compose.onNodeWithTag("event_title").performTextInput(title)
    }
    @Test fun noNasKeepsTheDraftInsteadOfSavingLocally() {
        val before=app.store.events(true);editor(listOf(app.store.calendars().first { it.id=="local" }))
        compose.onNodeWithTag("save_event").assertIsNotEnabled()
        compose.onNodeWithTag("nas_required").assertIsDisplayed()

        assertEquals(before,app.store.events(true));assertNull(created)
    }
    @Test fun soleNasWinsOverAnOldLocalDefaultAndQueuesADurableWrite() {
        editor(app.store.calendars().filter { it.id=="local" || it.id==cal })
        compose.onNodeWithTag("event_calendar").assertTextContains("Main",substring=true)
        compose.onNodeWithTag("event_calendar").performClick()
        compose.onNodeWithTag("choice_local").assertDoesNotExist()
        compose.onNodeWithTag("choice_$cal").performClick()
        compose.onNodeWithTag("save_event").performClick()
        compose.waitUntil(10000) { created!=null }
        val row=app.store.event(created!!)!!;assertEquals(cal,row.calendar);assertTrue(row.pending)
        assertTrue(app.store.pending(cal).any { it.href==row.href && it.kind=="PUT" })
    }
    @Test fun submissionBoundaryRejectsLocalEvenWithoutTheEditor() = runBlocking {
        val before=app.store.events(true)
        try {
            app.submissions.save(null,"local",IcsCodec.Draft().apply { title=this@NasOnlyCreationTest.title },null,java.util.UUID.randomUUID().toString()).await()
            fail("Production submissions must never create a local event")
        } catch(e: IllegalStateException) { assertEquals("NAS_REQUIRED",e.message) }
        assertEquals(before,app.store.events(true))
    }
    @Test fun anOldCommittedLocalReceiptIsRestoredWithoutCreatingAnotherEvent() = runBlocking {
        val receipt=java.util.UUID.randomUUID().toString()
        val draft=IcsCodec.Draft().apply { title=this@NasOnlyCreationTest.title }
        val old=app.store.save(null,"local",draft,submissionId=receipt);created=old
        val before=app.store.events(true)
        assertEquals(old,app.submissions.save(null,"local",draft,null,receipt).await())
        assertEquals(before,app.store.events(true))
    }
}
