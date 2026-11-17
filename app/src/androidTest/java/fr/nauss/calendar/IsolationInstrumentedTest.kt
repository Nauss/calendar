package fr.nauss.calendar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolationInstrumentedTest {
    @Test fun appStoreVaultAndSchedulersAreNotProductionResources() {
        val app=isolatedApp(ApplicationProvider.getApplicationContext())
        assertNotEquals(app.getDatabasePath("agenda.db"),app.storageContext.getDatabasePath("agenda.db"))
        assertNotEquals(app.noBackupFilesDir,app.storageContext.noBackupFilesDir)
        assertNotEquals("agenda-account-v1",app.vaultAlias)
        assertFalse(app.reminderAlarmsEnabled)
        assertFalse(app.backgroundSyncEnabled)
        assertThrows(IllegalStateException::class.java) { WorkManager.getInstance(app) }
        try {
            val fixture=Account("https://never-contact-fixture.invalid/","isolated","synthetic-only",false)
            app.vault.save(fixture);assertEquals(fixture,app.vault.read())
            SyncTasks.now(app);SyncTasks.periodic(app)
            assertThrows(IllegalStateException::class.java) { WorkManager.getInstance(app) }
        } finally { app.vault.clear() }
    }
}
