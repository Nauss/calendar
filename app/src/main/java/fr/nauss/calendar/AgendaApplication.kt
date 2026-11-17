package fr.nauss.calendar

import android.app.Application
import android.content.Context
import androidx.work.*
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

open class AgendaApplication : Application() {
    open val storageContext: Context get()=this
    open val backgroundSyncEnabled: Boolean get()=true
    open val vaultAlias: String get()="agenda-account-v1"
    open val reminderNamespace: String get()="production"
    open val reminderAlarmsEnabled: Boolean get()=true
    open fun postReminder(id: String,notification: android.app.Notification) {
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("NOTIFICATION_PERMISSION")
        androidx.core.app.NotificationManagerCompat.from(this).notify(reminderNamespace,id.hashCode(),notification)
    }
    val store: AgendaStore by lazy { AgendaStore(storageContext) }
    val vault: CredentialVault by lazy { CredentialVault(storageContext,alias=vaultAlias) }
    val submissions by lazy { EventSubmissions(store,::afterEventCommit) }
    open fun afterEventCommit(id: String) {
        try { ReminderScheduler.reschedule(this) }
        catch(e: Exception) { store.setMeta("reminder_error","SCHEDULE") }
        try { SyncTasks.now(this) } catch(e: Exception) { store.setMeta("sync_error","SCHEDULE") }
    }
    override fun onCreate() {
        super.onCreate();store.setMeta("sync_running","0")
        if(backgroundSyncEnabled) { WorkManager.initialize(this,Configuration.Builder().build());SyncTasks.periodic(this) }
        ReminderScheduler.channel(this)
    }
}
fun Context.agenda() = applicationContext as AgendaApplication

object SyncTasks {
    val accountLock=Any()
    fun periodic(context: Context) {
        if(!context.agenda().backgroundSyncEnabled) return
        val sync=PeriodicWorkRequestBuilder<CalendarSyncWorker>(6,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("calendar-sync",ExistingPeriodicWorkPolicy.KEEP,sync)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("calendar-reminders",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ReminderRefreshWorker>(12,TimeUnit.HOURS).build())
    }
    fun now(context: Context) { if(!context.agenda().backgroundSyncEnabled) return;WorkManager.getInstance(context).enqueueUniqueWork("calendar-manual-sync",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CalendarSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
}
class CalendarSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app=applicationContext.agenda();val db=app.store
        if(!app.backgroundSyncEnabled) return@withContext Result.success()
        synchronized(SyncTasks.accountLock) {
            db.setMeta("sync_running","1")
            try {
                val account=app.vault.read() ?: return@synchronized Result.success()
                if(db.meta("endpoint")!=account.endpoint || db.meta("username")!=account.user) { db.setMeta("sync_error","ACCOUNT");return@synchronized Result.failure() }
                val client=DavClient(account.endpoint,account.user,account.password,account.localHttp)
                var error=""
                for(cal in db.calendars().filter { it.remote && it.enabled }) {
                    val engine=SyncEngine(db,client)
                    if(!engine.sync(cal.id)) error=engine.error()
                }
                db.setMeta("sync_error",error)
                if(error.isEmpty()) db.setMeta("last_sync",System.currentTimeMillis().toString())
                if(error=="NETWORK" || error=="SERVER" || error=="REPORT" || error=="RETRY") Result.retry() else Result.success()
            } catch(e: Exception) { db.setMeta("sync_error","ACCOUNT");Result.failure() }
            finally { db.setMeta("sync_running","0");ReminderScheduler.reschedule(applicationContext) }
        }
    }
}
class ReminderRefreshWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { ReminderScheduler.reschedule(applicationContext); Result.success() }
}
