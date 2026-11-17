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
            assertEquals(0,db.pendingCount());resource=client.get(originalOp.href)
            assertEquals("ISOLATED ART ROUNDTRIP",IcsCodec.inspect(resource.ics,ZoneId.systemDefault()).draft.title)
            val edit=IcsCodec.inspect(db.event(id)!!.raw,ZoneId.systemDefault()).draft.apply { title="ISOLATED ART EDIT" }
            db.save(id,cal.href,edit,contentVersion(db.event(id)!!.raw));assertTrue(engine.sync(cal.href));resource=client.get(originalOp.href)
            assertEquals("ISOLATED ART EDIT",IcsCodec.inspect(resource.ics,ZoneId.systemDefault()).draft.title)
            assertTrue(engine.sync(cal.href));assertEquals(baseline.size+1,db.events().size)
            db.delete(id);assertTrue(engine.sync(cal.href));resource=null
            assertEquals(baseline.size,client.report(cal.href).size)
        } finally { resource?.let { client.delete(it.href,it.etag) };db.close();context.deleteDatabase("test-dav-art.db") }
    }
}
