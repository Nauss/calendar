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
    open val allowLocalCreation: Boolean get()=false
    open val vaultAlias: String get()="agenda-account-v1"
    open val reminderNamespace: String get()="production"
    open val reminderAlarmsEnabled: Boolean get()=true
    open val productionWidgetsEnabled: Boolean get()=true
    open val widgetNamespace: String get()="production"
    open val widgetUseLegacyCollections: Boolean get()=android.os.Build.VERSION.SDK_INT<31
    val widgetTestTargets: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    open fun onWidgetRendered(id: Int,snapshot: WidgetSnapshot) { }
    private var widgetUpdatesReady=false
    open fun postReminder(id: String,notification: android.app.Notification) {
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("NOTIFICATION_PERMISSION")
        androidx.core.app.NotificationManagerCompat.from(this).notify(reminderNamespace,id.hashCode(),notification)
    }
    val store: AgendaStore by lazy { AgendaStore(storageContext,onWidgetChange={ if(widgetUpdatesReady) WidgetUpdates.request(this) }) }
    val vault: CredentialVault by lazy { CredentialVault(storageContext,alias=vaultAlias) }
    val submissions by lazy { EventSubmissions(store,::afterEventCommit) { !allowLocalCreation } }
    open fun afterEventCommit(id: String) {
        try { ReminderScheduler.reschedule(this) }
        catch(e: Exception) { store.setMeta("reminder_error","SCHEDULE") }
        try { SyncTasks.now(this) } catch(e: Exception) { store.setMeta("sync_error","SCHEDULE") }
    }
    override fun onCreate() {
        super.onCreate();store.setMeta("sync_running","0")
        initializeNasDefault(store,allowLocalCreation)
        if(backgroundSyncEnabled) { WorkManager.initialize(this,Configuration.Builder().build());SyncTasks.periodic(this);if(store.pendingCount()>0) SyncTasks.now(this) }
        widgetUpdatesReady=true
        ReminderScheduler.channel(this)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(widgetUpdatesReady) WidgetUpdates.request(this)
    }
}
fun Context.agenda() = applicationContext as AgendaApplication

object SyncTasks {
    val accountLock=Any()
    const val USER_REQUEST_TAG="calendar-user-request"
    const val UPLOAD_TAG="calendar-auto-upload"
    const val REMOTE_REFRESH_HOURS=6L
    // Fresh wake-ups bypass stale retry backoff; accountLock serializes network work.
    // Later wake-ups find a drained outbox and make no redundant network request.
    internal fun enqueueUpload(manager: WorkManager): Operation = manager.enqueue(OneTimeWorkRequestBuilder<CalendarSyncWorker>().addTag(UPLOAD_TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    // A user tap must bypass old automatic retry delays, never replace/cancel a running write.
    internal fun enqueueManual(manager: WorkManager): Operation = manager.enqueueUniqueWork("calendar-user-sync",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<CalendarSyncWorker>().addTag(USER_REQUEST_TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    fun manual(context: Context) { if(!context.agenda().backgroundSyncEnabled) return;enqueueManual(WorkManager.getInstance(context)) }
    internal fun enqueuePeriodic(manager: WorkManager): Operation = manager.enqueueUniquePeriodicWork("calendar-sync",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<CalendarSyncWorker>(REMOTE_REFRESH_HOURS,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    fun periodic(context: Context) {
        if(!context.agenda().backgroundSyncEnabled) return
        enqueuePeriodic(WorkManager.getInstance(context))
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("calendar-reminders",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ReminderRefreshWorker>(12,TimeUnit.HOURS).build())
    }
    fun now(context: Context) { if(!context.agenda().backgroundSyncEnabled) return;enqueueUpload(WorkManager.getInstance(context)) }
}
class CalendarSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app=applicationContext.agenda();val db=app.store
        if(!app.backgroundSyncEnabled) return@withContext Result.success()
        val uploadOnly=tags.contains(SyncTasks.UPLOAD_TAG)
        fun diagnostic(codes: List<String>) { if(uploadOnly) ReadDiagnostics.upload(app,codes) else ReadDiagnostics.sync(app,codes) }
        synchronized(SyncTasks.accountLock) {
            // Do not let coalesced wake-ups poll the server or clear a prior pull failure.
            if(uploadOnly && db.pendingCount()==0) return@synchronized Result.success()
            val hadPending=db.pendingCount()>0
            val previousFullError=db.meta("full_sync_error",db.meta("sync_error"))
            fun persistError(error: String) {
                db.setMeta("full_sync_error",if(uploadOnly) previousFullError else error)
                db.setMeta("sync_error",syncErrorsAfterRun(previousFullError,error,uploadOnly))
            }
            db.setMeta("sync_running","1")
            diagnostic(listOf("STARTED"))
            try {
                val account=app.vault.read() ?: run {
                    if(hadPending) { persistError("ACCOUNT");diagnostic(listOf("ACCOUNT"));return@synchronized Result.failure() }
                    diagnostic(listOf("NO_ACCOUNT"));return@synchronized Result.success()
                }
                if(db.meta("endpoint")!=account.endpoint || db.meta("username")!=account.user) { persistError("ACCOUNT");diagnostic(listOf("ACCOUNT"));return@synchronized Result.failure() }
                val client=DavClient(account.endpoint,account.user,account.password,account.localHttp)
                val run=executeSyncRun(db,uploadOnly,{ SyncEngine(db,client) })
                val error=run.error
                diagnostic(run.diagnosticCodes)
                when {
                    error.isEmpty() -> Result.success()
                    shouldRetrySync(error,tags.contains(SyncTasks.USER_REQUEST_TAG)) -> Result.retry()
                    else -> Result.failure()
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { persistError("ACCOUNT");diagnostic(listOf("ACCOUNT"));Result.failure() }
            finally {
                db.setMeta("sync_running","0")
                try { ReminderScheduler.reschedule(applicationContext) } catch(e: Exception) { db.setMeta("reminder_error","SCHEDULE") }
            }
        }
    }
}
class ReminderRefreshWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { ReminderScheduler.reschedule(applicationContext); Result.success() }
}
