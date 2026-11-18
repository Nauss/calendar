package fr.nauss.calendar

import fr.nauss.calendar.core.SyncEngine

internal data class SyncRunResult(val error: String,val diagnosticCodes: List<String>)

/** Worker orchestration, under accountLock; injectable engine/clock allow isolated execution tests. */
internal fun executeSyncRun(db: AgendaStore,uploadOnly: Boolean,engineFactory: () -> SyncEngine,now: () -> Long = System::currentTimeMillis): SyncRunResult {
    if(uploadOnly && db.pendingCount()==0) return SyncRunResult("",emptyList())
    val targets=db.calendars().filter { it.remote && it.enabled }
    if(!uploadOnly && targets.isEmpty()) return SyncRunResult("",listOf("NO_TARGETS"))
    var confirmedWrites=0
    val previousFullError=db.meta("full_sync_error",db.meta("sync_error"))
    var error=""
    val readErrors=mutableListOf<String>()
    for(cal in targets) {
        if(uploadOnly && db.pending(cal.id).isEmpty()) continue
        val engine=engineFactory()
        val success=if(uploadOnly) engine.upload(cal.id) else engine.sync(cal.id)
        confirmedWrites+=engine.confirmedWrites()
        if(!success) error=SyncEngine.mergeErrors(error,engine.error())
        readErrors.addAll(engine.readError().split(',').filter { it.isNotEmpty() })
    }
    if(uploadOnly && error.isEmpty() && db.pendingCount()>0) error="RETRY"
    db.setMeta("full_sync_error",if(uploadOnly) previousFullError else error)
    db.setMeta("sync_error",syncErrorsAfterRun(previousFullError,error,uploadOnly))
    if(!uploadOnly && error.isEmpty()) db.setMeta("last_sync",now().toString())
    if(confirmedWrites>0 && db.pendingCount()==0) db.setMeta("last_upload",now().toString())
    return SyncRunResult(error,error.split(',')+readErrors)
}
