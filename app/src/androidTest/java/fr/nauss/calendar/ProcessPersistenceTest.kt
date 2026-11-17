package fr.nauss.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** Two invocations separated by adb force-stop exercise actual process death, in a separate test DB. */
@RunWith(AndroidJUnit4::class)
class ProcessPersistenceTest {
    @Test fun durableAcrossProcessDeath() {
        val phase=InstrumentationRegistry.getArguments().getString("persistencePhase")
        Assume.assumeTrue(phase=="seed" || phase=="verify")
        val context=isolatedApp(ApplicationProvider.getApplicationContext()).storageContext
        if(phase=="seed") context.deleteDatabase("test-process.db")
        val db=AgendaStore(context,"test-process.db")
        try {
            if(phase=="seed") {
                val cal="https://fixture.invalid/cal/";db.addCalendars(listOf(DavClient.CalendarInfo(cal,"FIXTURE","#4285F4",true)))
                val draft=IcsCodec.Draft().apply { title="PROCESS DEATH SYNTHETIC FIXTURE";start=LocalDateTime.of(2026,11,17,9,0);end=start.plusHours(1) }
                db.save(null,"local",draft);db.save(null,cal,draft,submissionId="12345678-1234-1234-1234-123456789abc")
                db.setMeta("original_operation",db.pending(cal).single().id)
                assertEquals(2,db.events().size);assertEquals(1,db.pendingCount())
            } else {
                assertEquals(2,db.events().size);assertEquals(1,db.pendingCount())
                assertEquals(db.meta("original_operation"),db.pending("https://fixture.invalid/cal/").single().id)
                val committed=checkNotNull(db.committedSubmission("12345678-1234-1234-1234-123456789abc"))
                val original=db.event(committed)!!
                repeat(2) { assertEquals(committed,db.save(null,original.calendar,IcsCodec.inspect(original.raw,ZoneId.systemDefault()).draft,submissionId="12345678-1234-1234-1234-123456789abc")) }
                assertEquals(2,db.events().size)
                assertEquals(db.meta("original_operation"),db.pending(original.calendar).single().id)
                assertTrue(db.events().all { IcsCodec.inspect(it.raw,ZoneId.systemDefault()).draft.title=="PROCESS DEATH SYNTHETIC FIXTURE" })
            }
        } finally { db.close();if(phase=="verify") context.deleteDatabase("test-process.db") }
    }
}
