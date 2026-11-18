package fr.nauss.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

// Presentation is independent of persistence and must never mutate an event.
enum class ReviewKind { CONFLICT, PENDING_DELETE, UNSUPPORTED }
data class ReviewSection(val kind: ReviewKind,val records: List<EventRow>)
fun reviewSections(records: List<EventRow>,unsupported: List<EventRow>): List<ReviewSection> {
    val conflicts=records.filter { it.pending && it.error=="CONFLICT" }.distinctBy { it.id }
    val conflictIds=conflicts.map { it.id }.toSet()
    val deleted=records.filter { it.deleted && it.pending && it.id !in conflictIds }.distinctBy { it.id }
    val hidden=unsupported.filter { !it.deleted && it.id !in conflictIds }.distinctBy { it.id }
    return listOf(ReviewSection(ReviewKind.CONFLICT,conflicts),ReviewSection(ReviewKind.PENDING_DELETE,deleted),ReviewSection(ReviewKind.UNSUPPORTED,hidden)).filter { it.records.isNotEmpty() }
}
fun syncErrorMessages(error: String): List<Int> = error.split(',').filter { it.isNotEmpty() }.map { code -> when(code) {
    "CONFLICT" -> R.string.sync_conflict_reason
    "AUTH" -> R.string.sync_auth_reason
    "PERMISSION" -> R.string.sync_permission_reason
    "TLS" -> R.string.sync_tls_reason
    "NETWORK" -> R.string.sync_network_reason
    "SERVER" -> R.string.sync_server_reason
    "REPORT" -> R.string.sync_data_reason
    "RETRY" -> R.string.sync_retry_reason
    "ACCOUNT" -> R.string.sync_account_reason
    "SCHEDULE" -> R.string.sync_schedule_reason
    else -> R.string.sync_error
} }.distinct()
fun unsupportedReasonMessage(reason: String?): Int = when(reason) {
    "LIMIT" -> R.string.unsupported_limit_reason
    "RECURRENCE" -> R.string.unsupported_recurrence_reason
    "TIMEZONE" -> R.string.unsupported_timezone_reason
    else -> R.string.unsupported_data_reason
}
@Composable fun SyncStatusMessages(state: CalendarState) {
    Column {
        // An active retry must not hide the previous failure or pending changes.
        syncErrorMessages(state.syncError).forEach { Text(stringResource(it),style=MaterialTheme.typography.bodySmall) }
        if(state.syncing) Text(stringResource(R.string.syncing),style=MaterialTheme.typography.bodySmall)
        if(state.pending>0) Text(stringResource(R.string.pending,state.pending),style=MaterialTheme.typography.bodySmall)
    }
}

// Fixed, opaque colors. UID only: no account, href, title, recurrence date or server color.
// Do not reorder: the palette is a stable local presentation convention, not DAV metadata.
private val planningPalette=listOf(Color(0xFF1967D2),Color(0xFF137333),Color(0xFF8E24AA),Color(0xFFB3261E),
    Color(0xFF00695C),Color(0xFF9C4A00),Color(0xFFAD1457),Color(0xFF3949AB))
fun planningEventColor(event: DisplayEvent): Color = planningPalette[Math.floorMod(event.record.uid.hashCode(),planningPalette.size)]
