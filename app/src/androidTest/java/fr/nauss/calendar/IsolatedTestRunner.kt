package fr.nauss.calendar

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/** No application store, vault, background sync, alarm identity or key alias is shared with production. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl,IsolatedAgendaApplication::class.java.name,context)
}
class IsolatedAgendaApplication : AgendaApplication() {
    override val backgroundSyncEnabled=false
    override val vaultAlias="agenda-instrumentation-key"
    override val reminderNamespace="instrumentation"
    override val reminderAlarmsEnabled=false
    override val productionWidgetsEnabled=false
    override val widgetNamespace="instrumentation"
    @Volatile var legacyWidgetCollections=false
    override val widgetUseLegacyCollections get()=legacyWidgetCollections || android.os.Build.VERSION.SDK_INT<31
    @Volatile var widgetRenderedHook: ((Int,WidgetSnapshot)->Unit)?=null
    override fun onWidgetRendered(id: Int,snapshot: WidgetSnapshot) { widgetRenderedHook?.invoke(id,snapshot) }
    @Volatile var notificationHook: (() -> Unit)?=null
    override fun postReminder(id: String,notification: android.app.Notification) { notificationHook?.invoke();super.postReminder(id,notification) }
    override val storageContext: Context by lazy { IsolatedStorage(this) }
    @Volatile var commitHook: ((String)->Unit)?=null
    override fun afterEventCommit(id: String) { commitHook?.invoke(id);super.afterEventCommit(id) }
}
private class IsolatedStorage(base: Context) : ContextWrapper(base) {
    override fun getDatabasePath(name: String)=File(File(baseContext.noBackupFilesDir,"instrumentation/databases").apply { mkdirs() },name)
    override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
    override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
    override fun deleteDatabase(name: String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getNoBackupFilesDir()=File(baseContext.noBackupFilesDir,"instrumentation/vault").apply { mkdirs() }
}
fun isolatedApp(context: Context): IsolatedAgendaApplication {
    val app=context.agenda()
    check(app is IsolatedAgendaApplication) { "Tests require IsolatedTestRunner; production storage is forbidden" }
    check(!app.backgroundSyncEnabled)
    return app
}
