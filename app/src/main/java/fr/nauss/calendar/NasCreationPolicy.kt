package fr.nauss.calendar

internal fun creationCalendars(calendars: List<CalendarRow>,allowLocal: Boolean=false): List<CalendarRow> =
    calendars.filter { it.writable && it.enabled && (it.remote || allowLocal) }
internal fun creationDefault(calendars: List<CalendarRow>,preferred: String,allowLocal: Boolean=false): String =
    creationCalendars(calendars,allowLocal).let { eligible ->
        preferred.takeIf { p -> eligible.any { it.id==p } }
            ?: if(allowLocal && eligible.any { it.id=="local" }) "local"
            else if(preferred.isEmpty() || preferred=="local") eligible.singleOrNull()?.id.orEmpty() else ""
    }

internal fun initializeNasDefault(store: AgendaStore,allowLocal: Boolean) {
    if(allowLocal) return
    val preferred=store.meta("default_calendar")
    if(preferred.isNotEmpty() && preferred!="local") return
    val destination=creationDefault(store.calendars(),preferred)
    if(destination.isNotEmpty()) store.setMeta("default_calendar",destination)
}
