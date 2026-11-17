package fr.nauss.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Only exact, explicitly labelled fixtures created by this suite, in its local calendar. */
fun cleanupLocalFixtures(context: Context) {
    val names=setOf("ISOLATED UI SMOKE FIXTURE","ISOLATED UI SMOKE FIXTURE EDITED","ISOLATED NAVIGATION FIXTURE","ISOLATED OVERLAP FIXTURE","ISOLATED SILENT REMINDER FIXTURE")
    val db=isolatedApp(context).store
    db.events().filter { it.calendar=="local" && runCatching { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title in names }.getOrDefault(false) }.forEach {
        db.delete(it.id);ReminderScheduler.cancelNotification(context,it.id)
    }
    ReminderScheduler.reschedule(context)
    check(db.events().none { it.calendar=="local" && runCatching { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title in names }.getOrDefault(false) })
}
@RunWith(AndroidJUnit4::class)
class FixtureCleanupTest {
    @Test fun removeOnlyOwnLabelledLocalFixtures() { cleanupLocalFixtures(ApplicationProvider.getApplicationContext()) }
}
