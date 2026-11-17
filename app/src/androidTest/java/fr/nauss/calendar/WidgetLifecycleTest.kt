package fr.nauss.calendar

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.runner.RunWith
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Saved UI state must not be replaced by an already-consumed widget launch. */
@RunWith(AndroidJUnit4::class)
class WidgetLifecycleTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=isolatedApp(ApplicationProvider.getApplicationContext<Context>())
    private var scenario: ActivityScenario<MainActivity>?=null
    private var id: String?=null
    private var visible=true
    private lateinit var row: AgendaRow
    @Before fun setup() {
        visible=app.store.calendar("local")!!.visible;app.store.visibility("local",true)
        id=app.store.save(null,"local",IcsCodec.Draft().apply {
            title="ISOLATED WIDGET LIFECYCLE";start=LocalDate.now().atTime(9,0);end=start.plusHours(1);repeat="DAILY"
        })
        row=WidgetProjection.planning(listOf(app.store.event(id!!)!!),app.store.calendars(),LocalDate.now().plusDays(2),LocalDate.now().plusDays(3),ZoneId.systemDefault()).rows.single()
        scenario=ActivityScenario.launch(WidgetIntents.activity(app,908,WidgetRoute.event(row)))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("edit_event").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() {
        scenario?.close();id?.let { app.store.delete(it) };app.store.visibility("local",visible)
    }
    @Test fun editingDraftSurvivesRecreationAfterWidgetOpen() {
        compose.onNodeWithTag("edit_event").performClick()
        compose.onNodeWithTag("event_title").performTextReplacement("ISOLATED UNSAVED WIDGET DRAFT")
        scenario!!.recreate()
        compose.onNodeWithTag("event_title").assertTextContains("ISOLATED UNSAVED WIDGET DRAFT")
        compose.onNodeWithTag("save_event").assertExists()
    }
    @Test fun navigatingAwayDoesNotReplayWidgetOnRecreation() {
        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        compose.onNodeWithTag("navigation").performClick()
        compose.onNodeWithText(app.getString(R.string.settings)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.widgets)).assertExists()
        scenario!!.recreate()
        compose.onNodeWithText(app.getString(R.string.widgets)).assertExists()
        compose.onNodeWithTag("edit_event").assertDoesNotExist()
    }
    @Test fun selectedOccurrenceSurvivesRecreationWithoutReplayingLaunch() {
        val expected=row.event.occurrence.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT))
        compose.onNodeWithText(expected,substring=true).assertExists()
        scenario!!.recreate()
        compose.onNodeWithText(expected,substring=true).assertExists()
    }
}
