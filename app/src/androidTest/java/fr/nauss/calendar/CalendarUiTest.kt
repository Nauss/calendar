package fr.nauss.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import fr.nauss.calendar.core.IcsCodec
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CalendarUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun cleanInterruptedFixtures() {
        cleanupLocalFixtures(compose.activity)
        val app=isolatedApp(compose.activity)
        app.store.addCalendars(listOf(fr.nauss.calendar.core.DavClient.CalendarInfo("https://ui-fixture.invalid/cal/","ISOLATED REMOTE DEFAULT","#4285F4",true)))
        app.store.setMeta("default_calendar","https://ui-fixture.invalid/cal/")
        lateinit var model: CalendarModel
        compose.runOnIdle {
            model=androidx.lifecycle.ViewModelProvider(compose.activity)[CalendarModel::class.java]
            model.load(java.time.LocalDate.now(),CalendarView.MONTH,false)
        }
        compose.waitUntil(10000) { model.state.value.defaultCalendar=="https://ui-fixture.invalid/cal/" && model.state.value.calendars.any { it.id=="https://ui-fixture.invalid/cal/" } }
    }
    @After fun restoreDefault() { isolatedApp(compose.activity).store.setMeta("default_calendar","local") }
    @Test fun localCreateViewSwitchAndPersistence() {
        val title="ISOLATED UI SMOKE FIXTURE"
        compose.waitUntil(15000) { compose.onAllNodesWithTag("create_event").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("create_event").performClick()
        compose.onNodeWithTag("event_title").performTextInput(title)
        compose.onNodeWithTag("event_calendar").assertTextContains("ISOLATED REMOTE DEFAULT",substring=true)
        compose.onNodeWithTag("event_calendar").performScrollTo().performClick()
        compose.onNodeWithTag("choice_local").performClick()
        compose.onNodeWithTag("save_event").performClick()
        compose.waitUntil(15000) { compose.activity.agenda().store.events().any { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title==title } }
        val saved=compose.activity.agenda().store.events().first { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title==title }
        assertEquals("local",saved.calendar);assertEquals(0,compose.activity.agenda().store.pendingCount())
        try {
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithTag("edit_event").performClick()
            compose.onNodeWithTag("event_title").performTextReplacement("$title EDITED")
            compose.onNodeWithTag("save_event").performClick()
            compose.waitUntil(15000) { IcsCodec.inspect(compose.activity.agenda().store.event(saved.id)!!.raw,ZoneId.systemDefault()).draft.title.endsWith("EDITED") }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("$title EDITED").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("$title EDITED",IcsCodec.inspect(compose.activity.agenda().store.event(saved.id)!!.raw,ZoneId.systemDefault()).draft.title)
        } finally { compose.activity.agenda().store.delete(saved.id) }
    }
}
