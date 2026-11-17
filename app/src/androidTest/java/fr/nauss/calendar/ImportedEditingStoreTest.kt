package fr.nauss.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Isolated private DB, synthetic account name, no vault or network access. */
@RunWith(AndroidJUnit4::class)
class ImportedEditingStoreTest {
    private lateinit var context: Context
    private lateinit var db: AgendaStore
    private val cal="https://imported-edit-fixture.invalid/cal/"
    @Before fun setup() {
        context=isolatedApp(ApplicationProvider.getApplicationContext()).storageContext
        context.deleteDatabase("test-imported-edit.db");db=AgendaStore(context,"test-imported-edit.db")
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"IMPORTED EDIT FIXTURE","#4285F4",true)))
    }
    @After fun cleanup() { db.close();context.deleteDatabase("test-imported-edit.db") }
    private fun raw()=IcsCodec.save(null,IcsCodec.Draft().apply { title="IMPORTED EDIT FIXTURE";zone="Europe/Paris";start=LocalDateTime.of(2026,11,17,9,0);end=start.plusHours(1) })
    private fun seed(): EventRow { db.applySnapshot(cal,listOf(DavClient.Resource(cal+"opaque","\"1\"",raw())));return db.events().single() }
    @Test fun opaqueEditKeepsIdentityAndAttemptedOutboxImmutable() {
        val before=seed();val draft=IcsCodec.inspect(before.raw,ZoneOffset.UTC).draft.apply { title="EDITED FIXTURE" }
        db.save(before.id,cal,draft,expectedRevision=eventRevision(before))
        val op=db.pending(cal).single();assertEquals(before.href,op.href);assertEquals(before.etag,op.etag)
        assertEquals(before.uid,IcsCodec.inspect(op.ics,ZoneOffset.UTC).uid)
        db.markAttempted(op.id)
        val current=db.event(before.id)!!
        assertThrows(IllegalStateException::class.java) { db.save(before.id,cal,draft,expectedRevision=eventRevision(current)) }
        assertEquals(op.id,db.pending(cal).single().id);assertEquals(op.ics,db.pending(cal).single().ics)
    }
    @Test fun etagOnlyRefreshInvalidatesEditorWithoutMutatingNewerVersion() {
        val before=seed();val draft=IcsCodec.inspect(before.raw,ZoneOffset.UTC).draft.apply { title="STALE" }
        db.applySnapshot(cal,listOf(DavClient.Resource(before.href,"\"2\"",before.raw)))
        assertThrows(IllegalStateException::class.java) { db.save(before.id,cal,draft,expectedRevision=eventRevision(before)) }
        assertEquals(before.raw,db.event(before.id)!!.raw);assertEquals("\"2\"",db.event(before.id)!!.etag);assertEquals(0,db.pendingCount())
    }
    @Test fun badSnapshotIsAtomicAndDoesNotRemoveCache() {
        val before=seed()
        assertThrows(IllegalStateException::class.java) { db.applySnapshot(cal,listOf(DavClient.Resource("https://imported-edit-fixture.invalid/other/opaque","\"2\"",before.raw))) }
        assertEquals(listOf(before),db.events());assertEquals(0,db.pendingCount())
    }
    @Test fun opaqueDeleteQueuesExactRevision() {
        val before=seed();db.delete(before.id,expectedRevision=eventRevision(before))
        val op=db.pending(cal).single();assertEquals("DELETE",op.kind);assertEquals(before.href,op.href);assertEquals(before.etag,op.etag)
    }
}
