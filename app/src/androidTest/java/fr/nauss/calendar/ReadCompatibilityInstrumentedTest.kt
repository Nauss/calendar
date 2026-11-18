package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import fr.nauss.calendar.core.SyncEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

/** Synthetic, offline integration only. No production DB, vault, alarms or accounts. */
@RunWith(AndroidJUnit4::class)
class ReadCompatibilityInstrumentedTest {
    private lateinit var app: IsolatedAgendaApplication
    private lateinit var db: AgendaStore
    private val cal="https://read-fixture.invalid/cal/"
    @Before fun setup() {
        app=isolatedApp(ApplicationProvider.getApplicationContext())
        app.storageContext.deleteDatabase("test-read-compatibility.db")
        db=AgendaStore(app.storageContext,"test-read-compatibility.db")
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"READ FIXTURE","#4285F4",true)))
    }
    @After fun cleanup() { db.close();app.storageContext.deleteDatabase("test-read-compatibility.db") }
    private fun raw()="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fixture//EN\r\nBEGIN:VTIMEZONE\r\nTZID:/fixture.invalid/Europe/Paris\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0530\r\nTZOFFSETTO:+0530\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:read-fixture\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;TZID=/fixture.invalid/Europe/Paris:20261117T090000\r\nDTEND;TZID=/fixture.invalid/Europe/Paris:20261117T100000\r\nSUMMARY:PRIVATE SYNTHETIC TITLE\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    @Test fun importedVendorTimezoneSyncsIntoCacheAndExpandsOnAndroid() {
        val resource=DavClient.Resource(cal+"one.ics","\"1\"",raw())
        val engine=SyncEngine(db,object: SyncEngine.Transport {
            override fun put(c: String,h: String,e: String,b: String): DavClient.Resource = error("No network writes")
            override fun delete(c: String,h: String,e: String) { error("No network writes") }
            override fun report(c: String)=listOf(resource)
        })
        assertTrue(engine.sync(cal));assertEquals("",engine.error());assertEquals("",engine.readError())
        val stored=db.events().single();assertEquals(raw(),stored.raw);assertEquals(0,db.pendingCount())
        val occurrences=IcsCodec.expand(stored.raw,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),ZoneOffset.UTC)
        assertEquals(1,occurrences.size);assertEquals(Instant.parse("2026-11-17T03:30:00Z"),occurrences.single().start)
    }
    @Test fun duplicateReportHrefsRetainBothCachedRowsAndPendingOperations() {
        val first=raw().replace("UID:read-fixture","UID:read-fixture-a")
        val second=raw().replace("UID:read-fixture","UID:read-fixture-b")
        db.applySnapshot(cal,listOf(DavClient.Resource(cal+"a.ics","\"a-1\"",first),DavClient.Resource(cal+"b.ics","\"b-1\"",second)))
        val xml="<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'><d:response><d:href>/cal/a.ics</d:href><d:href>/cal/b.ics</d:href><d:propstat><d:prop><d:getetag>&quot;a-1&quot;</d:getetag><c:calendar-data><![CDATA[$first]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
        val valid=xml.replace("<d:href>/cal/b.ics</d:href>","")
        val status="<d:status>HTTP/1.1 200 OK</d:status>"
        val etag="<d:getetag>&quot;a-1&quot;</d:getetag>"
        val data="<c:calendar-data><![CDATA[$first]]></c:calendar-data>"
        val malformed=mutableListOf(xml,
            valid.replace(status,status+"<d:status>HTTP/1.1 403 Forbidden</d:status>"),
            valid.replace(status,status+status),
            valid.replace("</d:response>",status+"<d:status>HTTP/1.1 403 Forbidden</d:status></d:response>"),
            valid.replace("</d:response>",status+"</d:response>"),
            valid.replace("</d:prop>","</d:prop><d:prop/>"))
        val deep="<d:responsedescription xmlns:x='urn:fixture'>"+"<x:n>".repeat(20000)+"</x:n>".repeat(20000)+"</d:responsedescription>"
        malformed.add(valid.replace("</d:multistatus>","$deep</d:multistatus>"))
        malformed.add(valid.replace("HTTP/1.1 200 OK","HTTP/1.1 200 OK&#10;HTTP/1.1 403 Forbidden"))
        malformed.add(valid.replace("HTTP/1.1 200 OK","HTTP/1.1 200 O&#x7f;K"))
        for(marker in listOf("<d:limit-reached/>","<d:error/>","<c:limit-recurrence-set/>")) {
            malformed.add(valid.replace("</d:response>","$marker</d:response>"))
            malformed.add(valid.replace("</d:multistatus>","<d:responsedescription>$marker</d:responsedescription></d:multistatus>"))
        }
        for(field in listOf(etag,data)) {
            malformed.add(valid.replace(field,field+field))
            malformed.add(valid.replace("</d:response>","<d:propstat><d:prop>$field</d:prop>$status</d:propstat></d:response>"))
        }
        val client=DavClient(cal,"fixture","synthetic",false)
        // Exercise the package-private production decoder without making it public or opening a socket.
        val parser=DavClient::class.java.getDeclaredMethod("parseReport",String::class.java,String::class.java).apply { isAccessible=true }
        for(pending in listOf(false,true)) {
            if(pending) {
                val row=db.events().single { it.href==cal+"a.ics" }
                val draft=IcsCodec.inspect(row.raw,ZoneOffset.UTC).draft.apply { title="PENDING SYNTHETIC METADATA" }
                db.save(row.id,cal,draft,expectedRevision=eventRevision(row))
                db.fail(db.pending(cal).single().id,"CONFLICT")
            }
            val before=db.events(true).sortedBy { it.href }
            fun operations()=db.pending(cal).map { listOf(it.id,it.calendar,it.href,it.etag,it.ics,it.kind,it.conflict) }
            val pendingBefore=operations()
            assertEquals(2,before.size);assertEquals(if(pending) 1 else 0,pendingBefore.size)
            for(response in malformed) {
                var reported=false
                val engine=SyncEngine(db,object: SyncEngine.Transport {
                    override fun put(c: String,h: String,e: String,b: String): DavClient.Resource = error("No network writes")
                    override fun delete(c: String,h: String,e: String) { error("No network writes") }
                    @Suppress("UNCHECKED_CAST")
                    override fun report(c: String): List<DavClient.Resource> {
                        reported=true
                        try { return parser.invoke(client,c,response) as List<DavClient.Resource> }
                        catch(ex: java.lang.reflect.InvocationTargetException) { throw ex.targetException }
                    }
                })
                assertFalse(engine.sync(cal));assertTrue(reported)
                assertEquals(if(pending) "CONFLICT,REPORT" else "REPORT",engine.error())
                assertEquals(before,db.events(true).sortedBy { it.href })
                assertEquals(pendingBefore,operations());assertEquals(pendingBefore.size,db.pendingCount())
            }
        }
    }
    @Test fun privateDiagnosticsContainOnlyCodesAndLiveInTheIsolatedDirectory() {
        ReadDiagnostics.display(app,listOf("PRIVATE SYNTHETIC TITLE","TIMEZONE"),pending=0)
        val file=File(app.storageContext.noBackupFilesDir,"read-diagnostics-display.txt")
        try {
            assertTrue(file.path.contains("instrumentation"));val report=file.readText()
            assertTrue(report.contains("DATA=1\n"));assertTrue(report.contains("TIMEZONE=1\n"));assertTrue(report.contains("pending=0\n"));assertFalse(report.contains("PRIVATE"))
        } finally { file.delete() }
    }
    @Test fun secondPrecisionSurvivesAndroidCacheDisplayAndPendingMetadataEdit() {
        val original=raw().replace("+0530","+010021")
        db.applySnapshot(cal,listOf(DavClient.Resource(cal+"seconds.ics","\"1\"",original)))
        val row=db.events().single()
        assertEquals(Instant.parse("2026-11-17T07:59:39Z"),IcsCodec.expand(row.raw,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),ZoneOffset.UTC).single().start)
        val draft=IcsCodec.inspect(row.raw,ZoneOffset.UTC).draft.apply { title="CHANGED SYNTHETIC METADATA" }
        db.save(row.id,cal,draft,expectedRevision=eventRevision(row))
        val operation=db.pending(cal).single()
        assertTrue(operation.ics.contains("TZOFFSETFROM:+010021"));assertTrue(operation.ics.contains("TZOFFSETTO:+010021"))
        assertEquals(row.href,operation.href);assertEquals(row.etag,operation.etag)
        assertEquals(Instant.parse("2026-11-17T07:59:39Z"),IcsCodec.expand(operation.ics,LocalDate.of(2026,11,17),LocalDate.of(2026,11,18),ZoneOffset.UTC).single().start)
    }
}
