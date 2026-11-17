package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import fr.nauss.calendar.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Opt-in, synthetic loopback integration only; never uses the configured app account. */
@RunWith(AndroidJUnit4::class)
class DavInstrumentedTest {
    @Test fun opaqueImportedTimezoneEditAndGuardedDeleteOnArt() {
        val endpoint=InstrumentationRegistry.getArguments().getString("testServer")
        Assume.assumeTrue("Pass explicit synthetic loopback testServer",endpoint!=null)
        require(endpoint!!.startsWith("http://127.0.0.1:15232/"))
        val client=DavClient(endpoint,"tester","synthetic-only",true)
        val cal=client.discover().first { it.href.endsWith("/agenda-qa/") }
        val baseline=client.report(cal.href)
        val uid="isolated-v2-import-${java.util.UUID.randomUUID()}"
        val href=client.childHref(cal.href,uid) // Deliberately suffixless, never a collection URL.
        val raw=listOf(
            "BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//ISOLATED ART IMPORT//EN",
            "BEGIN:VTIMEZONE","TZID:Europe/Paris","X-FIXTURE:retain",
            "BEGIN:DAYLIGHT","DTSTART:19700329T020000","TZOFFSETFROM:+0100","TZOFFSETTO:+0200",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU","END:DAYLIGHT",
            "BEGIN:STANDARD","DTSTART:19701025T030000","TZOFFSETFROM:+0200","TZOFFSETTO:+0100",
            "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU","END:STANDARD","END:VTIMEZONE",
            "BEGIN:VEVENT","UID:$uid","DTSTAMP:20260101T000000Z",
            "DTSTART;TZID=Europe/Paris:20261117T090000","DTEND;TZID=Europe/Paris:20261117T100000",
            "SUMMARY:ISOLATED IMPORTED ART","X-FIXTURE-PRESERVED:opaque","END:VEVENT","END:VCALENDAR",""
        ).joinToString("\r\n")
        val context=isolatedApp(ApplicationProvider.getApplicationContext()).storageContext
        context.deleteDatabase("test-imported-dav-art.db")
        val db=AgendaStore(context,"test-imported-dav-art.db")
        var resource: DavClient.Resource?=null
        try {
            resource=client.put(cal.href,href,null,raw)
            val imported=client.get(cal.href,href)
            db.addCalendars(listOf(cal));db.applySnapshot(cal.href,baseline+imported)
            val row=db.events().single { it.uid==uid }
            val info=IcsCodec.inspect(row.raw,ZoneOffset.UTC)
            assertTrue(info.timeEditable);assertFalse(info.timezoneEditable);assertFalse(info.recurrenceEditable)
            val draft=info.draft.apply { title="ISOLATED IMPORTED ART EDIT";start=start.plusMinutes(15);end=end.plusMinutes(15) }
            db.save(row.id,cal.href,draft,expectedRevision=eventRevision(row))
            val engine=SyncEngine(db,client)
            assertTrue(engine.error(),engine.sync(cal.href));assertEquals(0,db.pendingCount())
            resource=client.get(cal.href,href)
            val edited=IcsCodec.inspect(resource.ics,ZoneOffset.UTC)
            assertEquals(uid,edited.uid);assertEquals(draft.title,edited.draft.title)
            assertEquals(draft.start,edited.draft.start);assertEquals(draft.end,edited.draft.end)
            assertTrue(resource.ics.contains("X-FIXTURE-PRESERVED:opaque"))
            assertEquals(biweekly.Biweekly.parse(imported.ics).first().timezoneInfo.components.toString(),biweekly.Biweekly.parse(resource.ics).first().timezoneInfo.components.toString())
            db.delete(row.id,expectedRevision=eventRevision(db.event(row.id)!!))
            assertTrue(engine.error(),engine.sync(cal.href));resource=null
            assertEquals(0,db.pendingCount());assertNull(db.event(row.id))
            assertEquals(404,assertThrows(DavClient.DavException::class.java) { client.get(cal.href,href) }.status)
            val after=client.report(cal.href)
            assertEquals(baseline.associate { it.href to (it.etag to it.ics) },after.associate { it.href to (it.etag to it.ics) })
        } finally {
            try { resource?.let { client.delete(cal.href,it.href,it.etag) } }
            finally { db.close();context.deleteDatabase("test-imported-dav-art.db") }
        }
    }
    @Test fun disposableServerRoundTripOnArt() {
        val endpoint=InstrumentationRegistry.getArguments().getString("testServer")
        Assume.assumeTrue("Pass explicit synthetic loopback testServer",endpoint!=null)
        require(endpoint!!.startsWith("http://127.0.0.1:15232/"))
        val client=DavClient(endpoint,"tester","synthetic-only",true)
        val found=client.discover();assertTrue(found.isNotEmpty())
        val cal=found.first { it.href.endsWith("/agenda-qa/") };assertTrue(cal.writable)
        val context=isolatedApp(ApplicationProvider.getApplicationContext()).storageContext
        context.deleteDatabase("test-dav-art.db")
        var db=AgendaStore(context,"test-dav-art.db")
        var resource: DavClient.Resource?=null
        try {
            db.addCalendars(listOf(cal))
            val baseline=client.report(cal.href)
            assertTrue(baseline.size>=8)
            val draft=IcsCodec.Draft().apply { title="ISOLATED ART ROUNDTRIP";zone="Europe/Paris";start=LocalDateTime.of(2026,11,19,10,0);end=start.plusHours(1);repeat="WEEKLY" }
            val id=db.save(null,cal.href,draft);val originalOp=db.pending(cal.href).single()
            db.close();db=AgendaStore(context,"test-dav-art.db")
            assertEquals(originalOp.id,db.pending(cal.href).single().id)
            val engine=SyncEngine(db,client);assertTrue(engine.error(),engine.sync(cal.href))
            assertEquals(0,db.pendingCount());resource=client.get(cal.href,originalOp.href)
            assertEquals("ISOLATED ART ROUNDTRIP",IcsCodec.inspect(resource.ics,ZoneId.systemDefault()).draft.title)
            val edit=IcsCodec.inspect(db.event(id)!!.raw,ZoneId.systemDefault()).draft.apply { title="ISOLATED ART EDIT" }
            db.save(id,cal.href,edit,contentVersion(db.event(id)!!.raw));assertTrue(engine.sync(cal.href));resource=client.get(cal.href,originalOp.href)
            assertEquals("ISOLATED ART EDIT",IcsCodec.inspect(resource.ics,ZoneId.systemDefault()).draft.title)
            assertTrue(engine.sync(cal.href));assertEquals(baseline.size+1,db.events().size)
            db.delete(id);assertTrue(engine.sync(cal.href));resource=null
            assertEquals(baseline.size,client.report(cal.href).size)
        } finally { resource?.let { client.delete(cal.href,it.href,it.etag) };db.close();context.deleteDatabase("test-dav-art.db") }
    }
}
