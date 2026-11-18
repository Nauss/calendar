package fr.nauss.calendar

import org.junit.Assert.*
import org.junit.Test

class SyncExecutionPolicyTest {
    @Test fun anUploadCannotClearAnUnrecoveredDownloadFailure() {
        assertEquals("REPORT",syncErrorsAfterRun("REPORT","",uploadOnly=true))
        assertEquals("REPORT,NETWORK",syncErrorsAfterRun("REPORT","NETWORK",uploadOnly=true))
        assertEquals("",syncErrorsAfterRun("REPORT","",uploadOnly=false))
    }
    @Test fun explicitRequestsNeverRemainHiddenInAutomaticRetryBackoff() {
        for(error in listOf("NETWORK","SERVER","RETRY","CONFLICT,NETWORK")) {
            assertFalse("Manual $error must finish so the next tap can start fresh",shouldRetrySync(error,userRequested=true))
        }
    }
    @Test fun backgroundWorkRetainsAutomaticRetryForTransientFailuresOnly() {
        for(error in listOf("NETWORK","SERVER","RETRY","CONFLICT,NETWORK")) assertTrue(shouldRetrySync(error,userRequested=false))
        for(error in listOf("","AUTH","PERMISSION","TLS","REPORT","ACCOUNT","CONFLICT")) assertFalse(shouldRetrySync(error,userRequested=false))
    }
}
