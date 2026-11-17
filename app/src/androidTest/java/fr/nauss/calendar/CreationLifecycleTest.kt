package fr.nauss.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CreationLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=isolatedApp(compose.activity)
    private val title="ISOLATED CREATION LIFECYCLE"
    private fun records()=app.store.events().filter { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title==title }
    private fun create() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("create_event").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("create_event").performClick()
        compose.onNodeWithTag("event_title").performTextInput(title)
        compose.onNodeWithTag("event_calendar").performScrollTo().performClick()
        compose.onNodeWithTag("choice_local").performClick()
        compose.onNodeWithTag("save_event").performClick()
    }
    @Before fun prepare() { records().forEach { app.store.delete(it.id) } }
    @After fun cleanup() { app.commitHook=null;records().forEach { app.store.delete(it.id) } }
    @Test fun postcommitSchedulingFailureIsStillASuccessfulCreation() {
        app.commitHook={throw IllegalStateException("INJECTED_POSTCOMMIT_FAILURE")}
        create()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("edit_event").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save_event").assertDoesNotExist()
        assertEquals(1,records().size)
        assertEquals("local",records().single().calendar)
    }
    @Test fun recreateAfterCommitBeforeCallbackRestoresCommittedResult() {
        val committed=CountDownLatch(1);val release=CountDownLatch(1)
        app.commitHook={committed.countDown();check(release.await(20,TimeUnit.SECONDS))}
        try {
            create();assertTrue(committed.await(10,TimeUnit.SECONDS))
            val original=records().single()
            compose.activityRule.scenario.recreate()
            release.countDown()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("edit_event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("save_event").assertDoesNotExist()
            assertEquals(listOf(original),records())
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("edit_event").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(original),records())
        } finally { release.countDown() }
    }
}
