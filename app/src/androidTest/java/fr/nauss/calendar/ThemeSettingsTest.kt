package fr.nauss.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSettingsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=isolatedApp(compose.activity)
    @After fun cleanup() { app.store.setMeta("theme","system");app.store.setMeta("reminder_error","") }
    @Test fun systemBarIconsFollowInAppOverrideAndPersistedReminderErrorIsVisible() {
        for(theme in listOf("light","dark","light")) {
            app.store.setMeta("theme",theme)
            compose.waitUntil(10000) {
                var matches=false
                compose.runOnUiThread {
                    val window=compose.activity.window;val controller=WindowCompat.getInsetsController(window,window.decorView)
                    matches=controller.isAppearanceLightStatusBars==(theme=="light") && controller.isAppearanceLightNavigationBars==(theme=="light")
                }
                matches
            }
        }
        app.store.setMeta("reminder_error","UNSUPPORTED")
        compose.onNodeWithTag("navigation").performClick()
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("reminder_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reminder_error").performScrollTo().assertIsDisplayed()
    }
}
