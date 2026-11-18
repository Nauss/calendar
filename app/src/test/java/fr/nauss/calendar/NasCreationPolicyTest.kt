package fr.nauss.calendar

import org.junit.Assert.*
import org.junit.Test

class NasCreationPolicyTest {
    private fun row(id: String,remote: Boolean=true,writable: Boolean=true,enabled: Boolean=true)=CalendarRow(id,id,"#4285F4",remote,writable,true,enabled)
    private val local=row("local",remote=false)
    private val main=row("https://fixture.invalid/main/")
    @Test fun newEventsNeverFallBackToLocalOrGoogle() {
        assertEquals(listOf(main),creationCalendars(listOf(local,main,row("google",remote=false))))
        assertEquals(main.id,creationDefault(listOf(local,main),"local"))
        assertEquals("",creationDefault(listOf(local),"local"))
    }
    @Test fun invalidDefaultWithSeveralNasCalendarsRequiresAnExplicitChoice() {
        val other=row("https://fixture.invalid/other/")
        assertEquals("",creationDefault(listOf(local,main,other),"removed"))
        assertEquals("",creationDefault(listOf(local,other),main.id))
        assertEquals(other.id,creationDefault(listOf(local,main,other),other.id))
        assertEquals(emptyList<CalendarRow>(),creationCalendars(listOf(local,row("read-only",writable=false),row("disabled",enabled=false))))
    }
    @Test fun legacyLocalFixturesCanBeOptedInExplicitlyWithoutChangingProductionPolicy() {
        assertEquals("local",creationDefault(listOf(local,main),"local",allowLocal=true))
        assertEquals("local",creationDefault(listOf(local,main),"",allowLocal=true))
    }
}
