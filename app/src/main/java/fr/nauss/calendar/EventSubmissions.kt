package fr.nauss.calendar

import fr.nauss.calendar.core.IcsCodec
import kotlinx.coroutines.*

/** Activity cancellation cannot cancel a commit. Durable creation receipts survive process death. */
class EventSubmissions(private val store: AgendaStore,private val afterCommit: (String)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    fun save(id: String?,calendar: String,draft: IcsCodec.Draft,version: String?,submissionId: String?): Deferred<String> = scope.async {
        val saved=store.save(id,calendar,draft,submissionId=submissionId,expectedRevision=version)
        // A scheduler failure is not a failed save and must never invite a second creation.
        try { afterCommit(saved) }
        catch(e: Exception) { runCatching { store.setMeta("reminder_error","SCHEDULE") } }
        saved
    }
}
