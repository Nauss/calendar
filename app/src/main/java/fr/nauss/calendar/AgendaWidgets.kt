package fr.nauss.calendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

/** Only explicit identifier/date fields cross a PendingIntent boundary. Never raw ICS or account data. */
object WidgetIntents {
    const val OPEN="fr.nauss.calendar.WIDGET_OPEN"
    const val ACTION="fr.nauss.calendar.WIDGET_ACTION"
    private const val PREFIX="widget.route."
    fun read(intent: Intent): WidgetRoute? = if(intent.action!=OPEN) null else runCatching { WidgetRoutes.parse(
        listOf("kind","date","id","start","occurrence").mapNotNull { key -> intent.getStringExtra(PREFIX+key)?.let { key to it } }.toMap()) }.getOrDefault(WidgetRoute(RouteKind.UNAVAILABLE))
    fun fill(route: WidgetRoute)=Intent().apply { route.fields().forEach { (key,value)-> putExtra(PREFIX+key,value) } }
    fun activity(context: Context,id: Int,route: WidgetRoute): Intent = Intent(context,MainActivity::class.java).apply {
        action=OPEN;flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        data=Uri.Builder().scheme("agenda-widget").authority("open").appendPath(id.toString()).apply { route.fields().forEach { (k,v)->appendQueryParameter(k,v) } }.build()
        putExtras(fill(route))
    }
    fun click(context: Context,id: Int,route: WidgetRoute): PendingIntent = PendingIntent.getActivity(context,0,activity(context,id,route),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun template(context: Context,id: Int): PendingIntent {
        val intent=Intent(context,MainActivity::class.java).apply {
            action=OPEN;data=Uri.parse("agenda-widget://collection/$id")
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0))
    }
    fun action(context: Context,id: Int,provider: Class<out AppWidgetProvider>,actionName: String): PendingIntent {
        val intent=Intent(context,provider).apply {
            action=ACTION;data=Uri.parse("agenda-widget://action/$id/$actionName")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);putExtra("widget_action",actionName)
        }
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

open class AgendaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) { WidgetUpdates.request(context,ids) }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: Bundle) { WidgetUpdates.request(context,intArrayOf(id)) }
    override fun onDeleted(context: Context,ids: IntArray) { WidgetUpdates.request(context,ids,"delete") }
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action==WidgetIntents.ACTION) {
            val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
            val action=intent.getStringExtra("widget_action") ?: return
            if(action in setOf("previous","next","today","refresh")) WidgetUpdates.request(context,intArrayOf(id),action)
        } else super.onReceive(context,intent)
    }
}
class PlanningWidgetProvider : AgendaWidgetProvider()
class MonthWidgetProvider : AgendaWidgetProvider()
class WidgetEnvironmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_DATE_CHANGED,Intent.ACTION_LOCALE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) WidgetUpdates.request(context)
    }
}

/** Production WorkManager owns process lifetime. Isolated tests only update explicitly registered host IDs. */
object WidgetUpdates {
    private val testExecutor=Executors.newSingleThreadExecutor()
    private val renderLock=Any()
    fun providers(context: Context)=listOf(ComponentName(context,PlanningWidgetProvider::class.java),ComponentName(context,MonthWidgetProvider::class.java))
    fun allowed(context: Context,id: Int): Boolean {
        val app=context.agenda()
        return (app.productionWidgetsEnabled || id in app.widgetTestTargets) && AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider in providers(context)
    }
    fun request(context: Context,ids: IntArray?=null,action: String="refresh") {
        val app=context.agenda()
        if(!app.productionWidgetsEnabled) {
            val targets=(ids?.toList() ?: app.widgetTestTargets.toList()).filter { it in app.widgetTestTargets }.toIntArray()
            if(targets.isNotEmpty()) testExecutor.execute { render(app,targets,action) }
            return
        }
        val input=Data.Builder().putString("action",action).apply { ids?.let { putIntArray("ids",it) } }.build()
        WorkManager.getInstance(app).enqueueUniqueWork("calendar-widgets",ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(input).build())
    }
    fun render(context: Context,ids: IntArray?=null,action: String="refresh") = synchronized(renderLock) {
        val app=context.agenda();val manager=AppWidgetManager.getInstance(context)
        val targets=ids ?: providers(context).flatMap { manager.getAppWidgetIds(it).toList() }.toIntArray()
        for(id in targets) {
            if(!app.productionWidgetsEnabled && id !in app.widgetTestTargets) continue
            if(action=="delete") { app.store.setMeta("widget:$id:month","");continue }
            if(!allowed(context,id)) continue
            val month=manager.getAppWidgetInfo(id).provider.className==MonthWidgetProvider::class.java.name
            if(month && action in setOf("previous","next","today")) {
                val value=WidgetMonths.move(app.store.meta("widget:$id:month"),when(action) { "previous"->-1;"next"->1;else->0 },LocalDate.now())
                app.store.setMeta("widget:$id:month",value.toString())
            }
            try {
                val snapshot=load(context,id,month)
                manager.updateAppWidget(id,WidgetRenderer.render(context,id,month,snapshot))
                if(app.widgetUseLegacyCollections) manager.notifyAppWidgetViewDataChanged(id,R.id.widget_list)
                app.onWidgetRendered(id,snapshot)
            } catch(e: Exception) {
                // Keep a visible error instead of silently leaving stale successful-looking content.
                manager.updateAppWidget(id,WidgetRenderer.render(context,id,month,WidgetSnapshot.error()))
                app.onWidgetRendered(id,WidgetSnapshot.error())
            }
        }
    }
    fun load(context: Context,id: Int,month: Boolean): WidgetSnapshot {
        if(!allowed(context,id)) return WidgetSnapshot.error()
        val db=context.agenda().store;val today=LocalDate.now()
        val days=if(month) WidgetMonths.days(WidgetMonths.current(db.meta("widget:$id:month"),today)) else emptyList()
        val from=days.firstOrNull() ?: today;val until=days.lastOrNull()?.plusDays(1) ?: today.plusDays(31)
        // Month needs every projected day marker, rather than the Planning row cap.
        val (records,limited)=db.widgetRecords()
        val projected=WidgetProjection.planning(records,db.calendars(),from,until,ZoneId.systemDefault(),rowLimit=if(month) 4096 else WidgetProjection.ROW_LIMIT)
        val snapshot=projected.copy(truncated=projected.truncated || limited)
        return if(db.meta("sync_error").isNotEmpty()) snapshot.copy(status=WidgetStatus.ERROR) else snapshot
    }
}
class WidgetRefreshWorker(context: Context,parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result=withContext(Dispatchers.IO) {
        if(applicationContext.agenda().productionWidgetsEnabled) WidgetUpdates.render(applicationContext,inputData.getIntArray("ids"),inputData.getString("action") ?: "refresh")
        Result.success()
    }
}

object WidgetRenderer {
    private data class Palette(val dark: Boolean) {
        val text=android.graphics.Color.parseColor(if(dark) "#E3E3E3" else "#1F1F1F")
        val secondary=android.graphics.Color.parseColor(if(dark) "#C4C7C5" else "#444746")
        val primary=android.graphics.Color.parseColor(if(dark) "#A8C7FA" else "#1A73E8")
        val onPrimary=android.graphics.Color.parseColor(if(dark) "#062E6F" else "#FFFFFF")
    }
    private fun palette(context: Context): Palette {
        val theme=runCatching { context.agenda().store.meta("theme","system") }.getOrDefault("system")
        return Palette(theme=="dark" || (theme=="system" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES))
    }
    private fun locale(context: Context)=context.resources.configuration.locales[0] ?: Locale.getDefault()
    fun render(context: Context,id: Int,month: Boolean,snapshot: WidgetSnapshot): RemoteViews {
        val palette=palette(context);val manager=AppWidgetManager.getInstance(context);val options=manager.getAppWidgetOptions(id)
        val size=WidgetSize.resolve(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it>0 },options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it>0 })
        val today=LocalDate.now();val provider=if(month) MonthWidgetProvider::class.java else PlanningWidgetProvider::class.java
        val view=RemoteViews(context.packageName,if(month) R.layout.widget_month else R.layout.widget_planning)
        view.setInt(R.id.widget_root,"setBackgroundResource",if(palette.dark) R.drawable.widget_surface_dark else R.drawable.widget_surface_light)
        view.setTextViewText(R.id.widget_title,context.getString(if(month) R.string.widget_month else R.string.agenda))
        view.setTextColor(R.id.widget_title,palette.text);view.setTextColor(R.id.widget_footer,palette.secondary)
        view.setInt(R.id.widget_create,"setColorFilter",palette.primary);view.setInt(R.id.widget_refresh,"setColorFilter",palette.secondary)
        view.setOnClickPendingIntent(R.id.widget_title,WidgetIntents.click(context,id,WidgetRoute(RouteKind.PLANNING,today)))
        view.setOnClickPendingIntent(R.id.widget_create,WidgetIntents.click(context,id,WidgetRoute(RouteKind.CREATE,today)))
        view.setOnClickPendingIntent(R.id.widget_refresh,WidgetIntents.action(context,id,provider,"refresh"))
        view.setOnClickPendingIntent(R.id.widget_footer,WidgetIntents.click(context,id,WidgetRoute(RouteKind.PLANNING,today)))
        val footer=when { snapshot.status==WidgetStatus.ERROR->R.string.widget_error;snapshot.truncated->R.string.widget_more;else->R.string.widget_open }
        view.setTextViewText(R.id.widget_footer,context.getString(footer)+if(snapshot.status==WidgetStatus.ERROR && snapshot.truncated) "\n"+context.getString(R.string.widget_more) else "")
        view.setInt(R.id.widget_footer,"setMaxLines",if(size.height<280) 2 else 3)
        // Month already opens from its header/dates; keep room for all six weeks.
        // Error/limited notices must still be visible, including after reapply.
        view.setViewVisibility(R.id.widget_footer,if(month && snapshot.status!=WidgetStatus.ERROR && !snapshot.truncated) View.GONE else View.VISIBLE)
        if(month) {
            val selected=WidgetMonths.current(runCatching { context.agenda().store.meta("widget:$id:month") }.getOrDefault(""),today)
            view.setTextViewText(R.id.widget_month_label,selected.format(DateTimeFormatter.ofPattern("MMMM yyyy",locale(context))))
            view.setTextColor(R.id.widget_month_label,palette.text)
            view.setOnClickPendingIntent(R.id.widget_month_label,WidgetIntents.click(context,id,WidgetRoute(RouteKind.DAY,selected.atDay(1))))
            listOf(R.id.widget_previous to "previous",R.id.widget_next to "next",R.id.widget_today to "today").forEach { (control,action)->
                view.setInt(control,"setColorFilter",palette.primary);view.setOnClickPendingIntent(control,WidgetIntents.action(context,id,provider,action))
            }
        } else {
            view.setEmptyView(R.id.widget_list,R.id.widget_empty)
            view.setTextColor(R.id.widget_empty,palette.secondary);view.setTextColor(R.id.widget_status,palette.secondary)
            view.setTextViewText(R.id.widget_empty,context.getString(when { snapshot.status==WidgetStatus.ERROR->R.string.widget_error;snapshot.status==WidgetStatus.LOADING->R.string.widget_loading;snapshot.truncated->R.string.widget_more;else->R.string.widget_empty }))
            view.setTextViewText(R.id.widget_status,context.getString(R.string.widget_cache_hint))
            view.setViewVisibility(R.id.widget_status,if(size.height<230) View.GONE else View.VISIBLE)
        }
        view.setPendingIntentTemplate(R.id.widget_list,WidgetIntents.template(context,id))
        if(Build.VERSION.SDK_INT>=31 && !context.agenda().widgetUseLegacyCollections) {
            val items=RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(false).setViewTypeCount(1)
            collectionRows(context,id,month,snapshot).forEachIndexed { index,row -> items.addItem(index.toLong(),row) }
            view.setRemoteAdapter(R.id.widget_list,items.build())
        } else {
            val intent=Intent(context,AgendaWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).apply { data=Uri.parse("agenda-widget://adapter/${context.agenda().widgetNamespace}/$id") }
            view.setRemoteAdapter(R.id.widget_list,intent)
        }
        return view
    }
    fun collectionRows(context: Context,id: Int,month: Boolean,snapshot: WidgetSnapshot): List<RemoteViews> {
        if(!month) return snapshot.rows.map { eventRow(context,it) }
        val palette=palette(context);val today=LocalDate.now()
        val selected=WidgetMonths.current(runCatching { context.agenda().store.meta("widget:$id:month") }.getOrDefault(""),today)
        val weekdays=RemoteViews(context.packageName,R.layout.widget_week)
        weekdays.removeAllViews(R.id.widget_week)
        (1..7).forEach { index ->
            val cell=RemoteViews(context.packageName,R.layout.widget_day)
            cell.setTextViewText(R.id.widget_day,DayOfWeek.of(index).getDisplayName(TextStyle.NARROW,locale(context)))
            cell.setTextColor(R.id.widget_day,palette.secondary);cell.setInt(R.id.widget_day,"setMinHeight",(24*context.resources.displayMetrics.density).toInt())
            cell.setContentDescription(R.id.widget_day,DayOfWeek.of(index).getDisplayName(TextStyle.FULL,locale(context)));weekdays.addView(R.id.widget_week,cell)
        }
        val marked=snapshot.rows.map { it.day }.toSet()
        return listOf(weekdays)+WidgetMonths.days(selected).chunked(7).map { days ->
            val week=RemoteViews(context.packageName,R.layout.widget_week)
            week.removeAllViews(R.id.widget_week)
            days.forEach { day ->
                val cell=RemoteViews(context.packageName,R.layout.widget_day)
                cell.setTextViewText(R.id.widget_day,day.dayOfMonth.toString()+if(day in marked) "\n•" else "\n")
                cell.setTextColor(R.id.widget_day,when { day==today->palette.onPrimary;YearMonth.from(day)!=selected->palette.secondary;else->palette.text })
                cell.setInt(R.id.widget_day,"setBackgroundResource",if(day==today) (if(palette.dark) R.drawable.widget_today_dark else R.drawable.widget_today_light) else android.R.color.transparent)
                val label=day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale(context)))
                cell.setContentDescription(R.id.widget_day,if(day in marked) context.getString(R.string.widget_has_events,label) else label)
                cell.setOnClickFillInIntent(R.id.widget_day,WidgetIntents.fill(WidgetRoute(RouteKind.DAY,day)))
                week.addView(R.id.widget_week,cell)
            }
            week
        }
    }
    fun eventRow(context: Context,row: AgendaRow): RemoteViews {
        val view=RemoteViews(context.packageName,R.layout.widget_event_row);val event=row.event;val o=event.occurrence;val palette=palette(context)
        val base=calendarColor(event.calendar.color);val foreground=calendarEventTextColor(base).toArgb()
        view.setInt(R.id.widget_card_background,"setColorFilter",base.toArgb())
        view.setTextViewText(R.id.widget_date,row.day.format(DateTimeFormatter.ofPattern("EEE\nd",locale(context))))
        view.setTextColor(R.id.widget_date,if(row.day==LocalDate.now()) palette.primary else palette.text)
        val time=if(o.allDay) context.getString(R.string.all_day) else {
            val format=DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale(context));val zone=ZoneId.systemDefault()
            "${o.start.atZone(zone).format(format)} – ${o.end.atZone(zone).format(format)}"
        }
        val detail=listOfNotNull(time,if(row.dayCount>1) context.getString(R.string.event_day_part,row.dayIndex,row.dayCount) else null,
            if(event.record.pending) context.getString(if(event.record.error=="CONFLICT") R.string.conflict else R.string.pending,1) else null).joinToString(" · ")
        // Text bounds keep the parcel below Binder limits even for unusually large imported fields.
        view.setTextViewText(R.id.widget_event_title,o.title.ifBlank { context.getString(R.string.untitled) }.take(180))
        view.setTextViewText(R.id.widget_event_time,detail.take(240));view.setTextViewText(R.id.widget_event_location,o.location.take(120))
        view.setViewVisibility(R.id.widget_event_location,if(o.location.isBlank()) View.GONE else View.VISIBLE)
        listOf(R.id.widget_event_title,R.id.widget_event_time,R.id.widget_event_location).forEach { view.setTextColor(it,foreground) }
        view.setContentDescription(R.id.widget_row,"${row.day}: ${o.title.take(180)}. $detail. ${o.location.take(120)}")
        view.setOnClickFillInIntent(R.id.widget_row,WidgetIntents.fill(WidgetRoute.event(row)))
        return view
    }
}

/** Android 8–11 fallback. The platform alone can bind this permission-protected service. */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(this,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID))
    private class Factory(val context: Context,val id: Int) : RemoteViewsFactory {
        private var rows=emptyList<RemoteViews>()
        override fun onCreate() { }
        override fun onDataSetChanged() {
            rows=if(!WidgetUpdates.allowed(context,id)) emptyList() else {
                val month=AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className==MonthWidgetProvider::class.java.name
                val snapshot=runCatching { WidgetUpdates.load(context,id,month) }.getOrElse { WidgetSnapshot.error() }
                WidgetRenderer.collectionRows(context,id,month,snapshot)
            }
        }
        override fun onDestroy() { rows=emptyList() }
        override fun getCount()=rows.size
        override fun getViewAt(position: Int): RemoteViews?=rows.getOrNull(position)
        override fun getLoadingView(): RemoteViews?=null
        override fun getViewTypeCount()=1
        override fun getItemId(position: Int)=position.toLong()
        override fun hasStableIds()=false
    }
}
