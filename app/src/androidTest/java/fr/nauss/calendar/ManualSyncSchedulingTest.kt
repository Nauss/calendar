package fr.nauss.calendar

import android.annotation.SuppressLint
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real WorkManager, in-memory test DB and inert worker: no real account, network, or sync. */
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class ManualSyncSchedulingTest {
    @Test fun nasRefreshIsPeriodicEverySixHoursAndRequiresNetwork() {
        SyncTasks.enqueuePeriodic(manager).result.get(10,TimeUnit.SECONDS)
        val request=manager.getWorkInfosForUniqueWork("calendar-sync").get().single()
        assertEquals(TimeUnit.HOURS.toMillis(6),request.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED,request.constraints.requiredNetworkType)
        assertFalse(request.tags.contains(SyncTasks.UPLOAD_TAG))
    }
    @Test fun eachChangeBypassesOldBackoffWithoutCancellingAnActiveUpload() {
        val delayed=OneTimeWorkRequestBuilder<CalendarSyncWorker>().setInitialDelay(3,TimeUnit.HOURS).build()
        manager.enqueueUniqueWork("calendar-manual-sync",ExistingWorkPolicy.KEEP,delayed).result.get()
        repeat(2) { SyncTasks.enqueueUpload(manager).result.get() }
        val fresh=manager.getWorkInfosByTag("calendar-auto-upload").get()
        assertEquals("Every committed change needs a fresh wake-up, not the old retry",2,fresh.size)
        assertFalse(fresh.any { it.id==delayed.id })
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(fresh.first().id)
        SyncTasks.enqueueUpload(manager).result.get()
        assertEquals(3,manager.getWorkInfosByTag("calendar-auto-upload").get().size)
        assertEquals(WorkInfo.State.RUNNING,manager.getWorkInfoById(fresh.first().id).get()?.state)
        assertFalse(workers.single().isStopped)
        assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(delayed.id).get()?.state)
        workers.single().complete()
    }
    private lateinit var manager: WorkManager
    private lateinit var app: IsolatedAgendaApplication
    private val workers=mutableListOf<HoldingSyncWorker>()
    @Before fun setup() {
        app=isolatedApp(ApplicationProvider.getApplicationContext())
        val factory=object: WorkerFactory() {
            override fun createWorker(context: Context,name: String,parameters: WorkerParameters): ListenableWorker? {
                if(name!=CalendarSyncWorker::class.java.name) return null
                return HoldingSyncWorker(context,parameters).also { workers.add(it) }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(app,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        manager=WorkManager.getInstance(app)
    }
    @After fun cleanup() {
        manager.cancelAllWork().result.get(10,TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }
    @Test fun explicitRequestBypassesTheOldDelayedQueueWithoutCancellingIt() {
        val delayed=OneTimeWorkRequestBuilder<CalendarSyncWorker>().setInitialDelay(3,TimeUnit.HOURS).build()
        manager.enqueueUniqueWork("calendar-manual-sync",ExistingWorkPolicy.KEEP,delayed).result.get(10,TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(delayed.id).get()?.state)
        SyncTasks.enqueueManual(manager).result.get(10,TimeUnit.SECONDS)
        val fresh=manager.getWorkInfosForUniqueWork("calendar-user-sync").get().singleOrNull()
        assertNotNull("An explicit tap must not reuse the legacy delayed job",fresh)
        assertNotEquals(delayed.id,fresh!!.id)
        assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(delayed.id).get()?.state)
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(fresh.id)
        assertEquals(WorkInfo.State.RUNNING,manager.getWorkInfoById(fresh.id).get()?.state)
        workers.single().complete()
    }
    @Test fun repeatedTapNeverCancelsAnInFlightSync() {
        SyncTasks.enqueueManual(manager).result.get(10,TimeUnit.SECONDS)
        val first=manager.getWorkInfosForUniqueWork("calendar-user-sync").get().single()
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.RUNNING,manager.getWorkInfoById(first.id).get()?.state)
        SyncTasks.enqueueManual(manager).result.get(10,TimeUnit.SECONDS)
        assertEquals(listOf(first.id),manager.getWorkInfosForUniqueWork("calendar-user-sync").get().map { it.id })
        assertEquals(WorkInfo.State.RUNNING,manager.getWorkInfoById(first.id).get()?.state)
        assertFalse(workers.single().isStopped)
        workers.single().complete()
    }
    @Test fun aFinishedFailedAttemptDoesNotBlockTheNextTap() {
        SyncTasks.enqueueManual(manager).result.get(10,TimeUnit.SECONDS)
        val first=manager.getWorkInfosForUniqueWork("calendar-user-sync").get().single()
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(first.id)
        workers.single().complete(success=false)
        assertEquals(WorkInfo.State.FAILED,manager.getWorkInfoById(first.id).get()?.state)
        SyncTasks.enqueueManual(manager).result.get(10,TimeUnit.SECONDS)
        val next=manager.getWorkInfosForUniqueWork("calendar-user-sync").get().single { !it.state.isFinished }
        assertNotEquals(first.id,next.id)
    }
    class HoldingSyncWorker(context: Context,parameters: WorkerParameters): ListenableWorker(context,parameters) {
        private lateinit var completion: CallbackToFutureAdapter.Completer<Result>
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { completion=it;"INERT SYNC FIXTURE" }
        fun complete(success: Boolean=true) { completion.set(if(success) Result.success() else Result.failure()) }
    }
}
