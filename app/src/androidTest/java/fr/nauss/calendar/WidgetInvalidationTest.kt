package fr.nauss.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class WidgetInvalidationTest {
    @Test fun commitsSyncVisibilityAndThemeInvalidateButFailedEditsDoNot() {
        val app=isolatedApp(ApplicationProvider.getApplicationContext<Context>())
        var changes=0
        app.storageContext.deleteDatabase("widget-invalidation-test.db")
        val db=AgendaStore(app.storageContext,"widget-invalidation-test.db") { changes++ }
        try {
            val draft=IcsCodec.Draft().apply { title="ISOLATED WIDGET INVALIDATION";start=LocalDateTime.of(2026,11,17,10,0);end=start.plusHours(1);zone="UTC" }
            val id=db.save(null,"local",draft);assertEquals(1,changes)
            try { db.save(id,"local",draft,"invalid") ; fail("Stale edit must fail") } catch(e: IllegalStateException) { }
            assertEquals(1,changes)
            db.visibility("local",false);assertEquals(2,changes)
            db.setMeta("theme","dark");assertEquals(3,changes)
            db.setMeta("theme","dark");assertEquals(3,changes)
            db.setMeta("widget:7:month","2026-12");assertEquals(3,changes)
            val remote="http://127.0.0.1:15232/widget-invalidation/"
            db.addCalendars(listOf(DavClient.CalendarInfo(remote,"ISOLATED", "#1A73E8",true)));assertEquals(4,changes)
            db.applySnapshot(remote,listOf(DavClient.Resource(remote+"event.ics","\"v1\"",IcsCodec.save(null,draft))))
            assertEquals(5,changes)
            db.delete(id);assertEquals(6,changes)
        } finally { db.close();app.storageContext.deleteDatabase("widget-invalidation-test.db") }
    }
    @Test fun isolatedApplicationCannotRefreshProductionTargets() {
        val app=isolatedApp(ApplicationProvider.getApplicationContext<Context>())
        assertFalse(app.productionWidgetsEnabled)
        assertFalse(WidgetUpdates.allowed(app,Int.MAX_VALUE))
    }
}
