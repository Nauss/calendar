package fr.nauss.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun cleanInterruptedFixtures() { cleanupLocalFixtures(compose.activity) }
    @Test fun allFiveViewsSearchAndTimedOverlapSemantics() {
        val db=compose.activity.agenda().store
        val draft=IcsCodec.Draft().apply { title="ISOLATED NAVIGATION FIXTURE";start=LocalDate.now().atTime(9,0);end=start.plusHours(2);location="Synthetic studio" }
        val one=db.save(null,"local",draft)
        val two=db.save(null,"local",IcsCodec.Draft().apply { title="ISOLATED OVERLAP FIXTURE";start=draft.start.plusMinutes(30);end=start.plusHours(1) })
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("navigation").fetchSemanticsNodes().isNotEmpty() }
            for(view in listOf("DAY","THREE","WEEK","AGENDA","MONTH")) {
                compose.onNodeWithTag("navigation").performClick()
                compose.onNodeWithTag("view_$view").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag(when(view) { "MONTH"->"month_grid";"AGENDA"->"agenda_list";else->"time_grid" }).assertExists()
            }
            compose.onNodeWithTag("search").performClick();compose.onNodeWithTag("search_input").performTextInput("Synthetic studio")
            compose.waitUntil(15000) { compose.onAllNodesWithTag("event_$one").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("event_$one").assertIsDisplayed()
        } finally { db.delete(one);db.delete(two);db.setMeta("view","MONTH") }
    }
}
