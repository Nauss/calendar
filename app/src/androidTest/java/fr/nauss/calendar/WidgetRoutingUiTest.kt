package fr.nauss.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.rule.ActivityTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@RunWith(AndroidJUnit4::class)
class WidgetRoutingUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val compose=createEmptyComposeRule()
    // ActivityScenario filters lifecycle callbacks by Intent.filterEquals. onNewIntent
    // legitimately changes action/data via setIntent, so use identity-based ownership here.
    // Other Compose activity tests keep their ordinary ActivityScenario rules.
    @Suppress("DEPRECATION")
    private val activityRule=ActivityTestRule(MainActivity::class.java)
    private val activity get()=activityRule.activity
    private val created=mutableListOf<MainActivity>()
    private val stages=java.util.IdentityHashMap<MainActivity,Stage>()
    private val lifecycleRule=TestRule { base,_ -> object: Statement() {
        override fun evaluate() {
            val monitor=ActivityLifecycleMonitorRegistry.getInstance()
            val destroyed=java.util.concurrent.CountDownLatch(1)
            val callback=ActivityLifecycleCallback { candidate,stage ->
                if(candidate is MainActivity) {
                    if(stage==Stage.CREATED) created.add(candidate)
                    stages[candidate]=stage
                    if(stage==Stage.DESTROYED) destroyed.countDown()
                    android.util.Log.i("WidgetRouteLifecycle","instance=${System.identityHashCode(candidate)} task=${candidate.taskId} stage=$stage")
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try {
                base.evaluate() // Includes ActivityTestRule's finish of the owned instance.
                // finish() is asynchronous; main-loop idle alone does not wait for destruction.
                assertTrue("Owned activity never reached DESTROYED",destroyed.await(10,java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals("Widget launches must never create a second MainActivity",1,created.size)
                    assertEquals("Owned activity must actually be destroyed",Stage.DESTROYED,stages[created.single()])
                    assertTrue(created.single().isDestroyed)
                }
            } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        }
    } }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(lifecycleRule).around(compose).around(activityRule)
    private val app get()=isolatedApp(activity)
    private var id: String?=null
    private var localWasVisible=true
    private lateinit var row: AgendaRow
    @Before fun seed() {
        localWasVisible=app.store.calendar("local")!!.visible;app.store.visibility("local",true)
        id=app.store.save(null,"local",IcsCodec.Draft().apply {
            title="ISOLATED WIDGET ROUTE";start=LocalDate.now().atTime(9,0);end=start.plusHours(1);repeat="DAILY"
        })
        row=WidgetProjection.planning(listOf(app.store.event(id!!)!!),app.store.calendars(),LocalDate.now().plusDays(2),LocalDate.now().plusDays(3),ZoneId.systemDefault()).rows.single()
    }
    @After fun cleanup() { id?.let { app.store.delete(it) };app.store.visibility("local",localWasVisible) }
    private fun launch(route: WidgetRoute) {
        val original=activity;val task=original.taskId
        val expected=WidgetIntents.read(WidgetIntents.activity(original,907,route))
        WidgetIntents.click(original,907,route).send()
        compose.waitUntil(10000) {
            var consumed=false
            instrumentation.runOnMainSync { consumed=stages[original]==Stage.RESUMED && WidgetIntents.read(original.intent)==expected }
            consumed
        }
        compose.runOnIdle {
            assertEquals("Actual PendingIntent must reuse the running instance",listOf(original),created)
            assertSame(original,activity);assertEquals(task,activity.taskId)
        }
    }
    private fun detailVisible() { compose.waitUntil(10000) { compose.onAllNodesWithText("ISOLATED WIDGET ROUTE").fetchSemanticsNodes().isNotEmpty() };compose.onNodeWithTag("edit_event").assertExists() }
    @Test fun exactOccurrenceAndRepeatedNewIntentAreConsumedByAlreadyRunningActivity() {
        val route=WidgetRoute.event(row)
        launch(route);detailVisible()
        val expected=row.event.occurrence.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT))
        compose.onNodeWithText(expected,substring=true).assertExists()
        compose.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()
        compose.onNodeWithTag("navigation").assertExists()
        launch(route);detailVisible()
        compose.onNodeWithText(expected,substring=true).assertExists()
    }
    @Test fun hiddenDeletedAndMalformedRoutesNeverOpenEditor() {
        val route=WidgetRoute.event(row)
        app.store.visibility("local",false)
        launch(route)
        val unavailable=activity.getString(R.string.widget_route_unavailable)
        compose.waitUntil(10000) { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save_event").assertDoesNotExist();compose.onNodeWithTag("edit_event").assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.done)).performClick()
        app.store.visibility("local",true);app.store.delete(id!!);id=null
        launch(route)
        compose.waitUntil(10000) { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save_event").assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.done)).performClick()
        launch(WidgetRoute(RouteKind.CREATE))
        compose.waitUntil(10000) { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save_event").assertDoesNotExist()
    }
    @Test fun createAndDayRoutesRespectRequestedDate() {
        val date=LocalDate.now().plusDays(9)
        launch(WidgetRoute(RouteKind.DAY,date))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("time_grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))).assertExists()
        launch(WidgetRoute(RouteKind.CREATE,date))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("event_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("event_start_date").assertTextContains(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
}
