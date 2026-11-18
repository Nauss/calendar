package fr.nauss.calendar

import org.junit.Assert.*
import org.junit.Test

class ReadDiagnosticsTest {
    @Test fun workerStartAndEarlyNoAccountExitAreExplicitNonPrivateStates() {
        assertEquals("NO_ACCOUNT=1\nSTARTED=1\n",readDiagnosticCounts(listOf("STARTED","NO_ACCOUNT")))
    }
    @Test fun onlyAllowlistedCodesAndCountsAreWritten() {
        val privateValue="PRIVATE_TITLE https://private.invalid/account secret\nTIMEZONE=20"
        val report=readDiagnosticCounts(listOf("TIMEZONE","TIMEZONE","PARSE_WARNING_6","REPORT",privateValue,"PARSE_WARNING_-1","PARSE_WARNING_1000"))
        assertEquals("DATA=3\nPARSE_WARNING_6=1\nREPORT=1\nTIMEZONE=2\n",report)
        assertFalse(report.contains("PRIVATE"));assertFalse(report.contains("https"));assertFalse(report.contains("secret"))
    }
    @Test fun successReplacesThePreviousErrorAndDoesNotLookLikeAnAbsentDiagnostic() {
        assertEquals("OK=1\n",readDiagnosticCounts(emptyList()))
        assertEquals("OK=1\n",readDiagnosticCounts(listOf("")))
    }
}
