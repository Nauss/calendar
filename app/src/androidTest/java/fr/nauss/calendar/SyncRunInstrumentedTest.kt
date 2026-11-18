package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import fr.nauss.calendar.core.SyncEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDateTime

/** The worker's real orchestration, real isolated SQLite and inert transport; never an account/network. */
@RunWith(AndroidJUnit4::class)
class SyncRunInstrumentedTest {
    @Test fun fullRunWithoutRemoteTargetsDoesNotInventDownloadProgress() {
        for(detach in listOf(false,true)) {
            if(detach) db.detachPreservingCache() else db.chooseCalendars(emptyList())
            db.setMeta("last_sync","100");db.setMeta("last_upload","90")
            db.setMeta("full_sync_error","REPORT");db.setMeta("sync_error","REPORT")
            val result=execute(uploadOnly=false)
            assertEquals("",result.error);assertTrue(calls.isEmpty())
            assertEquals("100",db.meta("last_sync"));assertEquals("90",db.meta("last_upload"))
            assertEquals("REPORT",db.meta("full_sync_error"));assertEquals("REPORT",db.meta("sync_error"))
            assertEquals(listOf("NO_TARGETS"),result.diagnosticCodes)
            assertEquals("NO_TARGETS=1\n",readDiagnosticCounts(result.diagnosticCodes))
        }
    }
    private lateinit var db: AgendaStore
    private lateinit var app: IsolatedAgendaApplication
    private var cal="https://run-fixture.invalid/main/"
    private val calls=mutableListOf<String>()
    private var failReport=false
    private var failPutAt=0
    private var putCount=0
    private val transport=object: SyncEngine.Transport {
        override fun put(c: String,h: String,e: String?,raw: String): DavClient.Resource {
            calls.add("PUT");putCount++
            if(putCount==failPutAt) throw IOException("offline fixture")
            return DavClient.Resource(h,"\"2\"",raw)
        }
        override fun delete(c: String,h: String,e: String?) { throw AssertionError("Unexpected deletion") }
        override fun report(c: String): List<DavClient.Resource> {
            calls.add("REPORT")
            if(failReport) throw DavClient.DataException()
            return emptyList()
        }
    }
    @Before fun setup() {
        app=isolatedApp(ApplicationProvider.getApplicationContext());app.storageContext.deleteDatabase("test-sync-run.db")
        db=AgendaStore(app.storageContext,"test-sync-run.db")
        db.setIdentity("https://run-fixture.invalid/","fixture")
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"RUN FIXTURE","#4285F4",true)))
        db.setMeta("last_sync","100");db.setMeta("last_upload","90")
    }
    @After fun cleanup() { db.close();app.storageContext.deleteDatabase("test-sync-run.db") }
    private fun create()=db.save(null,cal,IcsCodec.Draft().apply { title="ISOLATED SYNC RUN";start=LocalDateTime.of(2026,11,18,9,0);end=start.plusHours(1) })
    private fun execute(uploadOnly: Boolean)=executeSyncRun(db,uploadOnly,{ SyncEngine(db,transport) },{123L})
    @Test fun confirmedUploadIsDatedEvenWhenTheFollowingFullDownloadFails() {
        val id=create();failReport=true
        assertEquals("REPORT",execute(uploadOnly=false).error)
        assertEquals(listOf("PUT","REPORT"),calls);assertEquals(0,db.pendingCount());assertNotNull(db.event(id))
        assertEquals("REPORT",db.meta("sync_error"));assertEquals("REPORT",db.meta("full_sync_error"))
        assertEquals("100",db.meta("last_sync"));assertEquals("123",db.meta("last_upload"))
    }
    @Test fun detachAndReconnectCannotResurrectThePreviousAccountsDownloadErrorOrClocks() {
        db.setMeta("full_sync_error","REPORT");db.setMeta("sync_error","REPORT")
        db.detachPreservingCache()
        db.setIdentity("https://new-run-fixture.invalid/","new-fixture")
        cal="https://new-run-fixture.invalid/main/"
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"NEW RUN FIXTURE","#4285F4",true)));create()
        assertEquals("",execute(uploadOnly=true).error)
        assertEquals(listOf("PUT"),calls);assertEquals(0,db.pendingCount())
        assertEquals("",db.meta("full_sync_error"));assertEquals("",db.meta("sync_error"))
        assertEquals("",db.meta("last_sync"));assertEquals("123",db.meta("last_upload"))
    }
    @Test fun uploadOnlyPreservesAnUnrecoveredDownloadErrorAndNeverRequestsReport() {
        db.setMeta("full_sync_error","REPORT");db.setMeta("sync_error","REPORT");create()
        assertEquals("",execute(uploadOnly=true).error)
        assertEquals(listOf("PUT"),calls);assertEquals("REPORT",db.meta("sync_error"))
        assertEquals("REPORT",db.meta("full_sync_error"));assertEquals("100",db.meta("last_sync"));assertEquals("123",db.meta("last_upload"))
    }
    @Test fun drainedWakeUpDoesNotClearErrorsChangeClocksOrContactTransport() {
        db.setMeta("full_sync_error","REPORT");db.setMeta("sync_error","REPORT")
        assertEquals("",execute(uploadOnly=true).error);assertTrue(calls.isEmpty())
        assertEquals("REPORT",db.meta("sync_error"));assertEquals("100",db.meta("last_sync"));assertEquals("90",db.meta("last_upload"))
    }
    @Test fun offlineFailureKeepsTheOperationAndBothProgressClocks() {
        create();val operation=db.pending(cal).single();failPutAt=1
        assertEquals("NETWORK",execute(uploadOnly=true).error)
        assertEquals(operation.id,db.pending(cal).single().id);assertEquals(operation.ics,db.pending(cal).single().ics)
        assertEquals(listOf("PUT"),calls);assertEquals("100",db.meta("last_sync"));assertEquals("90",db.meta("last_upload"))
    }
    @Test fun partialUploadDoesNotPretendTheWholeOutboxWasSent() {
        create();create();failPutAt=2
        assertEquals("NETWORK",execute(uploadOnly=true).error);assertEquals(1,db.pendingCount())
        assertEquals(listOf("PUT","PUT"),calls);assertEquals("100",db.meta("last_sync"));assertEquals("90",db.meta("last_upload"))
    }
    @Test fun successfulDownloadWithoutWritesDoesNotInventAnUpload() {
        db.setMeta("full_sync_error","REPORT");db.setMeta("sync_error","REPORT")
        assertEquals("",execute(uploadOnly=false).error);assertEquals(listOf("REPORT"),calls)
        assertEquals("",db.meta("full_sync_error"));assertEquals("",db.meta("sync_error"))
        assertEquals("123",db.meta("last_sync"));assertEquals("90",db.meta("last_upload"))
    }
}
