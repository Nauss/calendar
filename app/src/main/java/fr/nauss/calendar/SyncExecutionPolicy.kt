package fr.nauss.calendar

internal fun syncErrorsAfterRun(previousFullError: String,error: String,uploadOnly: Boolean): String =
    if(uploadOnly) fr.nauss.calendar.core.SyncEngine.mergeErrors(previousFullError,error) else error

internal fun shouldRetrySync(error: String,userRequested: Boolean): Boolean =
    !userRequested && error.split(',').any { it in setOf("NETWORK","SERVER","RETRY") }
