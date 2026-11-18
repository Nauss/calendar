package fr.nauss.calendar

import fr.nauss.calendar.core.IcsCodec
import java.io.File

/** Debug-only, app-private aggregate codes. Never include titles, ICS, URLs, UIDs or exception text. */
internal fun readDiagnosticCounts(reasons: Collection<String>): String {
    val status=setOf("AUTH","PERMISSION","TLS","NETWORK","SERVER","REPORT","RETRY","ACCOUNT","SCHEDULE","CONFLICT","STARTED","NO_ACCOUNT","NO_TARGETS")
    val counts=reasons.filter { it.isNotEmpty() }.map { if(it in status) it else IcsCodec.safeReadReason(it) }.groupingBy { it }.eachCount().toSortedMap()
    return if(counts.isEmpty()) "OK=1\n" else counts.entries.joinToString("\n",postfix="\n") { "${it.key}=${it.value}" }
}
internal object ReadDiagnostics {
    @Synchronized fun display(app: AgendaApplication,reasons: Collection<String>,pending: Int?=null) = write(app,"display",reasons,pending)
    @Synchronized fun sync(app: AgendaApplication,reasons: Collection<String>) = write(app,"sync",reasons)
    @Synchronized fun upload(app: AgendaApplication,reasons: Collection<String>) = write(app,"upload",reasons)
    private fun write(app: AgendaApplication,stage: String,reasons: Collection<String>,pending: Int?=null) {
        if(!BuildConfig.DEBUG) return
        // Diagnostic failure must not change sync, rendering, or persistence outcomes.
        runCatching {
            val outbox=if(pending==null) "" else "pending=${pending.coerceAtLeast(0)}\n"
            File(app.storageContext.noBackupFilesDir,"read-diagnostics-$stage.txt").writeText("format=1\nchecked=${System.currentTimeMillis()}\n"+outbox+readDiagnosticCounts(reasons))
        }
    }
}
