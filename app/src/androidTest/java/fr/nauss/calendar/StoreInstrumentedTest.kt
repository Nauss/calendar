package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import fr.nauss.calendar.core.IcsCodec
import fr.nauss.calendar.core.DavClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class StoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var db: AgendaStore
    @Before fun setup() { context = isolatedApp(ApplicationProvider.getApplicationContext()).storageContext; context.deleteDatabase("test-agenda.db"); db = AgendaStore(context, "test-agenda.db") }
    @After fun cleanup() { db.close(); context.deleteDatabase("test-agenda.db") }
    private fun draft() = IcsCodec.Draft().apply { title="ISOLATED INSTRUMENTATION FIXTURE"; zone="Europe/Paris"; start=LocalDateTime.of(2026,11,17,9,0); end=start.plusHours(1) }
    @Test fun creationSubmissionSurvivesReopenAndDoubleRetryWithoutNewIdentity() {
        val cal="https://fixture.invalid/cal/";db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        val submission=java.util.UUID.randomUUID().toString()
        val id=db.save(null,cal,draft(),submissionId=submission)
        val original=db.event(id)!!;val operation=db.pending(cal).single()
        db.close();db=AgendaStore(context,"test-agenda.db")
        repeat(2) { assertEquals(id,db.save(null,cal,draft().apply { title="RETRY MUST NOT EDIT" },submissionId=submission)) }
        assertEquals(listOf(original),db.events());assertEquals(operation.id,db.pending(cal).single().id)
        assertEquals(original.href,db.pending(cal).single().href)
        db.delete(id)
        assertEquals(id,db.save(null,cal,draft(),submissionId=submission))
        assertTrue("A deleted committed submission must not be resurrected",db.events().isEmpty())
    }
    @Test fun localCrudAndReopen() {
        assertTrue(db.events().isEmpty())
        val id=db.save(null,"local",draft())
        db.close(); db=AgendaStore(context,"test-agenda.db")
        assertEquals(1,db.events().size)
        assertEquals(0,db.pendingCount())
        db.save(id,"local",draft().apply { title="EDITED FIXTURE" },contentVersion(db.event(id)!!.raw))
        assertEquals("EDITED FIXTURE",IcsCodec.inspect(db.event(id)!!.raw,ZoneId.systemDefault()).draft.title)
        db.delete(id); assertTrue(db.events().isEmpty())
    }
    @Test fun outboxPersistsAndPartialSnapshotCannotErasePending() {
        val calendar=DavClient.CalendarInfo("https://fixture.invalid/cal/","FIXTURE","#4285F4",true)
        db.addCalendars(listOf(calendar))
        val id=db.save(null,calendar.href,draft())
        val before=db.pending(calendar.href).single()
        db.close(); db=AgendaStore(context,"test-agenda.db")
        assertEquals(before.id,db.pending(calendar.href).single().id)
        assertEquals(before.href,db.pending(calendar.href).single().href)
        db.applySnapshot(calendar.href,emptyList())
        assertNotNull(db.event(id)); assertEquals(1,db.pendingCount())
        db.markAttempted(before.id)
        assertThrows(IllegalStateException::class.java) { db.save(id,calendar.href,draft()) }
        assertThrows(IllegalStateException::class.java) { db.assertIdentityChangeAllowed("https://other.invalid/","someone") }
    }
    @Test fun coreRecurrenceWorksOnArt() {
        val raw=IcsCodec.save(null,draft().apply { repeat="DAILY"; reminderMinutes=10 })
        val occurrences=IcsCodec.expand(raw,LocalDate.of(2026,11,17),LocalDate.of(2026,11,20),ZoneId.of("UTC"))
        assertEquals(3,occurrences.size)
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"),occurrences.first().start)
        assertEquals(10,occurrences.first().reminderMinutes)
    }
    @Test fun remoteUidReplacementRollsBackSnapshot() {
        val cal="https://fixture.invalid/cal/"
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        val raw=IcsCodec.save(null,draft()); val original=DavClient.Resource(cal+"a.ics","\"1\"",raw)
        db.applySnapshot(cal,listOf(original))
        val changed=DavClient.Resource(cal+"a.ics","\"2\"",IcsCodec.save(null,draft()))
        assertThrows(IllegalStateException::class.java) { db.applySnapshot(cal,listOf(changed)) }
        assertEquals(original.uid,db.events().single().uid)
    }
    @Test fun accountDetachKeepsAnIsolatedArchive() {
        val cal="https://fixture.invalid/cal/"
        db.setIdentity("https://fixture.invalid/","fixture")
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        val raw=IcsCodec.save(null,draft());db.applySnapshot(cal,listOf(DavClient.Resource(cal+"a.ics","\"1\"",raw)))
        db.detachPreservingCache()
        assertTrue(db.calendars().none { it.remote });assertEquals(raw,db.events().single().raw)
        val archive=db.events().single().calendar;assertNotEquals(cal,archive)
        db.setIdentity("https://fixture.invalid/","fixture")
        db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        db.applySnapshot(cal,emptyList())
        assertEquals(archive,db.events().single().calendar)
    }
    @Test fun keystoreCiphertextContainsNoPlainCredentials() {
        val vault=CredentialVault(context,"test-account.gcm","agenda-test-key")
        val a=Account("https://fixture.invalid/","isolated-user","never-a-real-password",false)
        try {
            vault.save(a);assertEquals(a,vault.read())
            val bytes=java.io.File(context.noBackupFilesDir,"test-account.gcm").readBytes()
            assertFalse(String(bytes,Charsets.ISO_8859_1).contains(a.password));assertFalse(String(bytes,Charsets.ISO_8859_1).contains(a.user))
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();java.io.File(context.noBackupFilesDir,"test-account.gcm").writeBytes(bytes)
            assertThrows(Exception::class.java) { vault.read() }
        } finally { vault.clear();java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("agenda-test-key") } }
    }
    @Test fun unattemptedOfflineEditsCoalesceButAttemptedDeletesAreProtected() {
        val cal="https://fixture.invalid/cal/";db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        val id=db.save(null,cal,draft());val first=db.pending(cal).single()
        db.save(id,cal,draft().apply { title="SECOND OFFLINE VERSION" },contentVersion(db.event(id)!!.raw))
        val second=db.pending(cal).single();assertNotEquals(first.id,second.id);assertEquals(first.href,second.href)
        db.markAttempted(second.id)
        assertThrows(IllegalStateException::class.java) { db.delete(id) }
        assertNotNull(db.event(id));assertEquals(1,db.pendingCount())
    }
    @Test fun staleEditorCannotOverwriteARefreshOrNewerLocalEdit() {
        val id=db.save(null,"local",draft())
        val version=contentVersion(db.event(id)!!.raw)
        db.save(id,"local",draft().apply { title="NEWER VERSION" },version)
        assertThrows(IllegalStateException::class.java) { db.save(id,"local",draft().apply { title="STALE VERSION" },version) }
        assertThrows(IllegalStateException::class.java) { db.delete(id,version) }
        assertEquals("NEWER VERSION",IcsCodec.inspect(db.event(id)!!.raw,ZoneId.systemDefault()).draft.title)
    }
    @Test fun deletionConfirmationIncludesEtagEvenWhenContentIsUnchanged() {
        val cal="https://fixture.invalid/cal/";db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
        val raw=IcsCodec.save(null,draft());val href=cal+"revision.ics"
        db.applySnapshot(cal,listOf(DavClient.Resource(href,"\"1\"",raw)))
        val original=db.events().single();val revision=eventRevision(original)
        db.applySnapshot(cal,listOf(DavClient.Resource(href,"\"2\"",raw)))
        assertThrows(IllegalStateException::class.java) { db.delete(original.id,expectedRevision=revision) }
        assertEquals("\"2\"",db.event(original.id)!!.etag);assertEquals(0,db.pendingCount())
    }
}
