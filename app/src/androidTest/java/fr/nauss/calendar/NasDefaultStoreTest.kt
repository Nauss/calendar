package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.DavClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasDefaultStoreTest {
    private lateinit var db: AgendaStore
    private lateinit var app: IsolatedAgendaApplication
    private val main=DavClient.CalendarInfo("https://default-fixture.invalid/main/","Main","#4285F4",true)
    private val other=DavClient.CalendarInfo("https://default-fixture.invalid/other/","Other","#4285F4",true)
    @Before fun setup() {
        app=isolatedApp(ApplicationProvider.getApplicationContext());app.storageContext.deleteDatabase("test-nas-default.db")
        db=AgendaStore(app.storageContext,"test-nas-default.db")
    }
    @After fun cleanup() { db.close();app.storageContext.deleteDatabase("test-nas-default.db") }
    @Test fun legacyLocalDefaultIsPinnedToTheSoleNasAndNeverSilentlyRetargeted() {
        db.addCalendars(listOf(main));db.setMeta("default_calendar","local")
        initializeNasDefault(db,allowLocal=false);assertEquals(main.href,db.meta("default_calendar"))
        db.chooseCalendars(listOf(other));initializeNasDefault(db,allowLocal=false)
        assertEquals(main.href,db.meta("default_calendar"));assertEquals("",creationDefault(db.calendars(),db.meta("default_calendar")))
        assertTrue(db.events(true).isEmpty());assertEquals(0,db.pendingCount())
    }
    @Test fun severalDestinationsRequireTheOwnerToChoose() {
        db.addCalendars(listOf(main,other));db.setMeta("default_calendar","local")
        initializeNasDefault(db,allowLocal=false)
        assertEquals("local",db.meta("default_calendar"));assertEquals("",creationDefault(db.calendars(),"local"))
    }
}
