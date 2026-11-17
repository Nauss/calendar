package fr.nauss.calendar

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real PendingIntents and framework recreation; all instances owned by identity. */
@RunWith(AndroidJUnit4::class)
class IncomingEditorNavigationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val compose=createEmptyComposeRule()
    @Suppress("DEPRECATION") private val activityRule=ActivityTestRule(MainActivity::class.java)
    private val created=mutableListOf<MainActivity>()
    private val stages=java.util.IdentityHashMap<MainActivity,Stage>()
    private val activity get()=created.last()
    private val lifecycleRule=TestRule { base,_ -> object: Statement() {
        override fun evaluate() {
            val monitor=ActivityLifecycleMonitorRegistry.getInstance()
            val callback=ActivityLifecycleCallback { candidate,stage ->
                if(candidate is MainActivity) {
                    if(stage==Stage.CREATED) created.add(candidate)
                    stages[candidate]=stage
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try { base.evaluate() } finally {
                instrumentation.runOnMainSync { created.filterNot { it.isDestroyed }.forEach { it.finish() } }
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                var destroyed=false
                while(!destroyed && System.nanoTime()<deadline) {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { destroyed=created.all { stages[it]==Stage.DESTROYED && it.isDestroyed } }
                    if(!destroyed) Thread.sleep(20)
                }
                instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
                assertTrue("Every owned activity must reach DESTROYED",destroyed)
            }
        }
    } }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(lifecycleRule).around(compose).around(activityRule)
    private val app get()=isolatedApp(activity)
    private var visible=true
    private var id: String?=null
    private lateinit var row: AgendaRow
    private val draftTitle="ISOLATED REVIEWFIX DRAFT"
    private val first=LocalDate.of(2027,2,10)
    private val second=first.plusDays(1)
    private fun records()=app.store.events().filter { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title==draftTitle }
    @Before fun seed() {
        visible=app.store.calendar("local")!!.visible;app.store.visibility("local",true)
        id=app.store.save(null,"local",IcsCodec.Draft().apply {
            title="ISOLATED REVIEWFIX TARGET";start=first.atTime(11,0);end=start.plusHours(1)
        })
        row=WidgetProjection.planning(listOf(app.store.event(id!!)!!),app.store.calendars(),first,first.plusDays(1),ZoneId.systemDefault()).rows.single()
    }
    @After fun cleanup() {
        app.commitHook=null;records().forEach { app.store.delete(it.id) }
        id?.let { app.store.delete(it) };app.store.visibility("local",visible)
        assertTrue(records().isEmpty());id?.let { assertNull(app.store.event(it)) }
        assertEquals(visible,app.store.calendar("local")!!.visible)
    }
    private fun launch(route: WidgetRoute) {
        val original=activity;val count=created.size
        val expected=WidgetIntents.read(WidgetIntents.activity(original,909,route))
        WidgetIntents.click(original,909,route).send()
        compose.waitUntil(10000) {
            var received=false
            instrumentation.runOnMainSync { received=stages[original]==Stage.RESUMED && WidgetIntents.read(original.intent)==expected }
            received
        }
        compose.runOnIdle { assertSame(original,activity);assertEquals(count,created.size) }
    }
    private fun notification() {
        val original=activity;val count=created.size
        val intent=Intent(original,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data=Uri.parse("agenda-test://reviewfix/notification")
            putExtra("event_id",id)
        }
        PendingIntent.getActivity(original,909,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE).send()
        compose.waitUntil(10000) {
            var received=false
            instrumentation.runOnMainSync { received=stages[original]==Stage.RESUMED && original.intent.data==intent.data }
            received
        }
        compose.runOnIdle { assertSame(original,activity);assertEquals(count,created.size) }
    }
    private fun recreate() {
        val original=activity;val count=created.size
        instrumentation.runOnMainSync { original.recreate() }
        compose.waitUntil(15000) {
            var ready=false
            instrumentation.runOnMainSync { ready=created.size==count+1 && stages[activity]==Stage.RESUMED && stages[original]==Stage.DESTROYED }
            ready
        }
        compose.waitForIdle()
    }
    private fun editorDate(day: LocalDate) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("event_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("event_start_date").assertTextContains(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
    private fun dirty() {
        compose.onNodeWithTag("event_title").performTextReplacement(draftTitle)
        compose.onNodeWithTag("event_location").performScrollTo().performTextReplacement("Exact unsaved location")
        compose.onNodeWithTag("event_notes").performScrollTo().performTextReplacement("Exact unsaved notes")
    }
    private fun assertDraft(day: LocalDate) {
        compose.onNodeWithTag("event_title").assertTextContains(draftTitle)
        compose.onNodeWithTag("event_location").assertTextContains("Exact unsaved location")
        compose.onNodeWithTag("event_notes").assertTextContains("Exact unsaved notes")
        editorDate(day)
    }
    private fun confirmation() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("confirm_action").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.discard_changes)).assertExists()
    }
    private fun cancel() { compose.onNodeWithText(app.getString(R.string.cancel)).performClick() }
    private fun detail() { compose.waitUntil(10000) { compose.onAllNodesWithTag("edit_event").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun consecutiveCleanCreateRoutesInitializeEachDateExactlyOnce() {
        launch(WidgetRoute(RouteKind.CREATE,first));editorDate(first)
        launch(WidgetRoute(RouteKind.CREATE,second));editorDate(second)
        recreate();editorDate(second)
        launch(WidgetRoute(RouteKind.CREATE,second.plusDays(1)));editorDate(second.plusDays(1))
    }
    @Test fun dirtyExistingEditorRequiresDiscardForWidgetAndCancelKeepsExactDraft() {
        launch(WidgetRoute.event(row));detail()
        compose.onNodeWithTag("edit_event").performClick();dirty()
        launch(WidgetRoute.event(row));confirmation();cancel();assertDraft(first)
        launch(WidgetRoute.event(row));confirmation()
        compose.onNodeWithTag("confirm_action").performClick();detail()
        assertEquals("ISOLATED REVIEWFIX TARGET",IcsCodec.inspect(app.store.event(id!!)!!.raw,ZoneId.systemDefault()).draft.title)
    }
    @Test fun notificationNavigationAlsoRequiresExplicitDiscard() {
        launch(WidgetRoute(RouteKind.CREATE,first));editorDate(first);dirty()
        notification();confirmation();cancel();assertDraft(first)
        notification();confirmation();compose.onNodeWithTag("confirm_action").performClick();detail()
        assertTrue(records().isEmpty())
    }
    @Test fun latestRequestReplacesPendingDestinationWithoutReusableApproval() {
        launch(WidgetRoute(RouteKind.CREATE,first));editorDate(first);dirty()
        launch(WidgetRoute(RouteKind.CREATE,second));confirmation()
        val third=second.plusDays(1)
        launch(WidgetRoute(RouteKind.CREATE,third));confirmation()
        compose.onNodeWithTag("confirm_action").performClick();editorDate(third)
        compose.onNodeWithTag("event_title").assertTextEquals(app.getString(R.string.title),"")
        dirty();launch(WidgetRoute.event(row));confirmation();cancel();assertDraft(third)
    }
    @Test fun pendingConfirmationAndDraftSurviveRecreationWithOneCreationReceipt() {
        launch(WidgetRoute(RouteKind.CREATE,first));editorDate(first);dirty()
        compose.onNodeWithTag("event_calendar").performScrollTo().performClick();compose.onNodeWithTag("choice_local").performClick()
        launch(WidgetRoute(RouteKind.CREATE,second));confirmation()
        recreate();confirmation();cancel();assertDraft(first)
        // Dirty remains true after cancellation/recreation: a fresh request asks again.
        launch(WidgetRoute(RouteKind.CREATE,second));confirmation();cancel()
        val committed=CountDownLatch(1);val release=CountDownLatch(1)
        app.commitHook={committed.countDown();check(release.await(30,TimeUnit.SECONDS))}
        try {
            compose.onNodeWithTag("save_event").performClick();assertTrue(committed.await(10,TimeUnit.SECONDS))
            val saved=records().single()
            recreate();release.countDown();detail();recreate();detail()
            assertEquals(listOf(saved),records())
            assertEquals(first,IcsCodec.inspect(saved.raw,ZoneId.systemDefault()).draft.start.toLocalDate())
            app.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM metadata WHERE key LIKE 'submission:%' AND value=?",arrayOf(saved.id)).use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
        } finally { release.countDown() }
    }
    @Test fun incomingNavigationWaitsForInFlightSaveWithoutDuplicateCreation() {
        launch(WidgetRoute(RouteKind.CREATE,first));editorDate(first);dirty()
        compose.onNodeWithTag("event_calendar").performScrollTo().performClick();compose.onNodeWithTag("choice_local").performClick()
        val committed=CountDownLatch(1);val release=CountDownLatch(1)
        app.commitHook={committed.countDown();check(release.await(30,TimeUnit.SECONDS))}
        try {
            compose.onNodeWithTag("save_event").performClick();assertTrue(committed.await(10,TimeUnit.SECONDS))
            launch(WidgetRoute(RouteKind.CREATE,second))
            compose.onNodeWithTag("save_event").assertIsNotEnabled()
            compose.onNodeWithTag("confirm_action").assertDoesNotExist()
            release.countDown();editorDate(second)
            assertEquals(1,records().size)
        } finally { release.countDown() }
    }
}
