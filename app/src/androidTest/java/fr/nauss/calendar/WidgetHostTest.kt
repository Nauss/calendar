package fr.nauss.calendar

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.concurrent.ConcurrentHashMap

/** Real system-bound providers, RemoteViews adapters and PendingIntents. Never a renderer-only test. */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=isolatedApp(ApplicationProvider.getApplicationContext<Context>())
    private val calendar="https://widget-host-fixture.invalid/events/"
    private lateinit var visibility: Map<String,Boolean>
    private var savedSyncError=""
    private val rendered=ConcurrentHashMap<Int,WidgetSnapshot>()
    private var scenario: ActivityScenario<WidgetHostActivity>?=null
    private fun raw(title: String): String=IcsCodec.save(null,IcsCodec.Draft().apply {
        this.title=title;start=LocalDate.now().atTime(10,0);end=start.plusHours(1);repeat="DAILY"
    }).replace("RRULE:FREQ=DAILY","RRULE:FREQ=DAILY;COUNT=3").replace(Regex("(?m)^UID:[^\\r\\n]+"),"UID:isolated-widget-host")
    @Before fun setup() {
        assertFalse(app.productionWidgetsEnabled)
        visibility=app.store.calendars().associate { it.id to it.visible }
        savedSyncError=app.store.meta("sync_error");app.store.setMeta("sync_error","")
        visibility.keys.forEach { app.store.visibility(it,false) }
        app.store.addCalendars(listOf(DavClient.CalendarInfo(calendar,"ISOLATED WIDGET HOST","#1A73E8",true)))
        app.store.visibility(calendar,true)
        app.store.applySnapshot(calendar,listOf(DavClient.Resource(calendar+"fixture.ics","\"v1\"",raw("ISOLATED WIDGET HOST"))))
        app.widgetRenderedHook={id,snapshot->rendered[id]=snapshot}
        if(Build.VERSION.SDK_INT>=29) instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        scenario=ActivityScenario.launch(Intent(app,WidgetHostActivity::class.java).putExtra("auto_bind",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @After fun cleanup() {
        try {
            scenario?.onActivity { it.removeWidgets() };scenario?.close()
            app.widgetRenderedHook=null;app.legacyWidgetCollections=false
            app.store.applySnapshot(calendar,emptyList());app.store.visibility(calendar,false)
            visibility.forEach { (id,visible)->app.store.visibility(id,visible) }
            app.store.setMeta("sync_error",savedSyncError)
        } finally { if(Build.VERSION.SDK_INT>=29) instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
    private fun await(message: String,condition: ()->Boolean) {
        val end=System.nanoTime()+20_000_000_000L
        while(System.nanoTime()<end) { if(condition()) return;Thread.sleep(80) }
        assertTrue(message,condition())
    }
    private fun <T> ui(block: (WidgetHostActivity)->T): T {
        var result: T?=null;scenario!!.onActivity { result=block(it) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(id: Int)=ui { host->views(host.hosted.getValue(id)).filterIsInstance<TextView>().joinToString(" ") { it.text } }
    private fun add(provider: Class<out AgendaWidgetProvider>,render: Boolean=true): Int {
        val id=ui { it.addWidget(provider,render) }
        assertNotNull("Binding permission was not granted; parent must approve this dedicated host before retry",AppWidgetManager.getInstance(app).getAppWidgetInfo(id))
        assertTrue(ui { id in it.hosted })
        return id
    }
    private fun attachedRow(list: ListView,position: Int): View? =
        (position-list.firstVisiblePosition).takeIf { it>=0 && it<list.childCount }?.let(list::getChildAt)
    private fun attachRow(id: Int,position: Int) {
        ui { it.hosted.getValue(id).findViewById<ListView>(R.id.widget_list).setSelectionFromTop(position,0) }
        await("Collection row $position not attached") {
            ui { host -> attachedRow(host.hosted.getValue(id).findViewById(R.id.widget_list),position)?.isLaidOut==true }
        }
    }
    private fun assertFullMonth(id: Int,width: Int) {
        val days=WidgetMonths.days(YearMonth.now())
        val seen=mutableListOf<String>()
        await("Month must retain weekdays and all six calendar weeks") { ui { it.hosted.getValue(id).findViewById<ListView>(R.id.widget_list)?.count==7 } }
        for(position in 0..6) {
            attachRow(id,position)
            ui { host ->
                val widget=host.hosted.getValue(id);val density=host.resources.displayMetrics.density
                assertEquals("Requested size must fit the actual host",width,(widget.width/density).toInt())
                val parent=widget.parent as ViewGroup
                assertTrue(widget.left>=parent.paddingLeft && widget.right<=parent.width-parent.paddingRight)
                val list=widget.findViewById<ListView>(R.id.widget_list)
                val cells=views(checkNotNull(attachedRow(list,position))).filterIsInstance<TextView>().filter { it.id==R.id.widget_day }
                assertEquals("Recycled weekday/week rows must contain exactly seven cells",7,cells.size)
                assertTrue(cells.maxOf { it.width }-cells.minOf { it.width }<=1)
                var previousRight=-1
                cells.forEachIndexed { column,cell ->
                    val bounds=android.graphics.Rect();cell.getDrawingRect(bounds);widget.offsetDescendantRectToMyCoords(cell,bounds)
                    assertTrue("No horizontal clipping or overlap",bounds.left>=0 && bounds.right<=widget.width && bounds.left>=previousRight)
                    previousRight=bounds.right
                    assertTrue("Date text must remain readable",cell.paint.measureText("31")<=cell.width-cell.paddingLeft-cell.paddingRight)
                    if(position>0) {
                        assertTrue("Dates always retain 48dp height",cell.height>=48*density-1)
                        // Dense-calendar exception: narrower equal-width columns own the full cell.
                        if(width>=384) assertTrue("Roomy dates retain 48dp width",cell.width>=48*density-1)
                        else assertTrue("Narrow calendar columns must not force 48dp overflow",cell.width<48*density)
                        val day=days[(position-1)*7+column]
                        val label=day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(host.resources.configuration.locales[0]))
                        assertTrue("Wrong actual date at row $position column $column",cell.contentDescription.toString().contains(label))
                        assertEquals(day.dayOfMonth.toString(),cell.text.toString().substringBefore('\n'))
                        seen.add(label)
                    }
                }
                listOf(R.id.widget_create,R.id.widget_refresh,R.id.widget_previous,R.id.widget_next,R.id.widget_today).forEach { control ->
                    val button=widget.findViewById<View>(control)
                    assertTrue("Header touch targets stay 48dp",button.width>=48*density-1 && button.height>=48*density-1)
                    val bounds=android.graphics.Rect();button.getDrawingRect(bounds);widget.offsetDescendantRectToMyCoords(button,bounds)
                    assertTrue("Header must remain within host",bounds.left>=0 && bounds.right<=widget.width)
                }
            }
        }
        assertEquals(42,seen.size);assertEquals(42,seen.toSet().size)
    }
    @Test fun fullSizedMonthShowsAllDatesAndKeepsErrorsVisible() {
        val month=add(MonthWidgetProvider::class.java)
        ui { it.resizeWidget(month,384,480) }
        await("Month grid not populated") { ui { it.hosted.getValue(month).findViewById<ListView>(R.id.widget_list)?.count==7 } }
        instrumentation.waitForIdleSync()
        ui { host ->
            val widget=host.hosted.getValue(month)
            assertEquals("Normal month has no redundant footer",View.GONE,widget.findViewById<View>(R.id.widget_footer).visibility)
            val list=widget.findViewById<ListView>(R.id.widget_list)
            assertEquals(0,list.firstVisiblePosition)
            assertEquals("All six weeks visible at normal launcher height",6,list.lastVisiblePosition)
            assertTrue(checkNotNull(attachedRow(list,6)).bottom<=list.height-list.paddingBottom+1)
        }
        app.store.setMeta("sync_error","NETWORK")
        await("Month errors must remain visible") { ui { host ->
            val footer=host.hosted.getValue(month).findViewById<TextView>(R.id.widget_footer)
            footer.visibility==View.VISIBLE && footer.text.toString().contains(host.getString(R.string.widget_error))
        } }
    }
    @Test fun repeatedUpdatesAndRecyclingRetain42DatesAtPhoneWidths() {
        val month=add(MonthWidgetProvider::class.java)
        val info=AppWidgetManager.getInstance(app).getAppWidgetInfo(month)
        assertEquals(4,info.targetCellWidth)
        for(width in listOf(320,280,384)) {
            ui { it.resizeWidget(month,width,260) }
            repeat(2) {
                WidgetUpdates.render(app,intArrayOf(month))
                instrumentation.waitForIdleSync()
                assertFullMonth(month,width)
            }
        }
    }
    @Test fun boundProvidersUpdateResizeNavigateAndKeepOtherInstancesIsolated() {
        val planning=add(PlanningWidgetProvider::class.java)
        val month=add(MonthWidgetProvider::class.java)
        val secondMonth=add(MonthWidgetProvider::class.java)
        val denied=add(PlanningWidgetProvider::class.java,false)
        await("Planning collection did not reach the real host") { text(planning).contains("ISOLATED WIDGET HOST") }
        await("Month grid not populated") { ui { it.hosted.getValue(month).findViewById<ListView>(R.id.widget_list)?.count==7 } }
        await("Month day targets were not laid out") { ui { it.hosted.getValue(month).findViewById<ListView>(R.id.widget_list).childCount>1 } }
        ui { host ->
            val list=host.hosted.getValue(month).findViewById<ListView>(R.id.widget_list)
            val minimum=(48*host.resources.displayMetrics.density).toInt()-1
            val cells=views(list.getChildAt(1)).filter { it.id==R.id.widget_day }
            assertEquals("A real month week must expose seven dates",7,cells.size)
            cells.forEach { cell ->
                assertTrue("Narrow dates use an equal-width dense-calendar column",cell.width>0 && cell.width<minimum)
                assertTrue("Month day must retain a 48dp touch height",cell.height>=minimum)
            }
        }
        val parcel=android.os.Parcel.obtain()
        try {
            val snapshot=rendered.getValue(planning)
            WidgetRenderer.render(app,planning,false,snapshot.copy(rows=List(64) { snapshot.rows[it%3] })).writeToParcel(parcel,0)
            assertTrue("Bounded widget exceeded the 512 KiB parcel budget",parcel.dataSize()<512*1024)
        } finally { parcel.recycle() }
        val original=YearMonth.now()
        ui { it.hosted.getValue(month).findViewById<View>(R.id.widget_next).performClick() }
        await("Per-widget next month action failed") { app.store.meta("widget:$month:month")==original.plusMonths(1).toString() }
        assertEquals(original,WidgetMonths.current(app.store.meta("widget:$secondMonth:month"),LocalDate.now()))
        ui { it.hosted.getValue(month).findViewById<View>(R.id.widget_previous).performClick() }
        await("Previous month action failed") { app.store.meta("widget:$month:month")==original.toString() }
        ui { it.hosted.getValue(month).findViewById<View>(R.id.widget_next).performClick() }
        await("Next action not consumed twice") { app.store.meta("widget:$month:month")==original.plusMonths(1).toString() }
        ui { it.hosted.getValue(month).findViewById<View>(R.id.widget_today).performClick() }
        await("Today did not reset month") { app.store.meta("widget:$month:month")==original.toString() }
        ui { it.resizeWidget(planning,240,180);it.resizeWidget(month,280,260) }
        await("Compact host lost its collection") { text(planning).contains("ISOLATED WIDGET HOST") }
        assertTrue(AppWidgetManager.getInstance(app).getAppWidgetOptions(month).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)<=280)
        app.store.applySnapshot(calendar,listOf(DavClient.Resource(calendar+"fixture.ics","\"v2\"",raw("ISOLATED WIDGET UPDATED"))))
        await("Sync snapshot did not refresh bound widget") { text(planning).contains("ISOLATED WIDGET UPDATED") }
        app.store.visibility(calendar,false)
        await("Hidden calendar remained in widget") { rendered[planning]?.status==WidgetStatus.EMPTY && !text(planning).contains("ISOLATED WIDGET UPDATED") }
        app.store.visibility(calendar,true)
        await("Visibility restoration did not refresh") { text(planning).contains("ISOLATED WIDGET UPDATED") }
        assertFalse("Instrumentation updated an unregistered host ID",rendered.containsKey(denied))
        assertFalse(text(denied).contains("ISOLATED"))
    }
    @Test fun realCollectionClickUsesExactOccurrenceAndHeaderCreateAndDayClicksRoute() {
        val planning=add(PlanningWidgetProvider::class.java)
        val month=add(MonthWidgetProvider::class.java)
        await("Collection not loaded") { ui { it.hosted.getValue(planning).findViewById<ListView>(R.id.widget_list)?.count==3 } }
        fun clickAndRead(click: (WidgetHostActivity)->Unit): WidgetRoute {
            val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            val destroyed=java.util.concurrent.CountDownLatch(1)
            var launched: android.app.Activity?=null
            val lifecycle=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val callback=androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity,stage ->
                if(activity===launched && stage==androidx.test.runner.lifecycle.Stage.DESTROYED) destroyed.countDown()
            }
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }
            try {
                ui(click)
                val activity=instrumentation.waitForMonitorWithTimeout(monitor,10000) ?: throw AssertionError("Actual widget PendingIntent did not launch MainActivity")
                val route=WidgetIntents.read(activity.intent) ?: throw AssertionError("Missing widget route")
                instrumentation.runOnMainSync { launched=activity;activity.finish() }
                assertTrue("Launched activity did not finish",destroyed.await(10,java.util.concurrent.TimeUnit.SECONDS))
                return route
            } finally {
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
            }
        }
        val expected=rendered.getValue(planning).rows[1]
        ui { it.resizeWidget(planning,340,220) }
        ui { it.hosted.getValue(planning).findViewById<ListView>(R.id.widget_list).setSelection(1) }
        await("Second occurrence not attached to host collection") { ui { val list=it.hosted.getValue(planning).findViewById<ListView>(R.id.widget_list);if(list.firstVisiblePosition!=1) list.setSelection(1);list.firstVisiblePosition==1 && list.childCount>0 } }
        val occurrence=clickAndRead { host ->
            val list=host.hosted.getValue(planning).findViewById<ListView>(R.id.widget_list)
            // The platform's collection OnItemClickListener forwards the row fill-in Intent.
            list.performItemClick(list.getChildAt(0),1,list.adapter.getItemId(1))
        }
        assertEquals(WidgetRoute.event(expected),occurrence)
        assertEquals(RouteKind.PLANNING,clickAndRead { it.hosted.getValue(planning).findViewById<View>(R.id.widget_title).performClick() }.kind)
        assertEquals(RouteKind.CREATE,clickAndRead { it.hosted.getValue(planning).findViewById<View>(R.id.widget_create).performClick() }.kind)
        val target=WidgetMonths.days(YearMonth.now())[10]
        ui { it.resizeWidget(month,280,260) }
        assertFullMonth(month,280)
        attachRow(month,2)
        val date=clickAndRead { host ->
            val list=host.hosted.getValue(month).findViewById<ListView>(R.id.widget_list)
            val cells=views(checkNotNull(attachedRow(list,2))).filter { it.id==R.id.widget_day }
            assertTrue("Date cell must have its own real click handler",cells[3].performClick()) // date index 10
        }
        assertEquals(RouteKind.DAY,date.kind);assertEquals(target,date.date)
    }
    @Test fun legacyServiceAdapterBindsAndRefreshesThroughRealHost() {
        app.legacyWidgetCollections=true
        val id=add(PlanningWidgetProvider::class.java)
        val month=add(MonthWidgetProvider::class.java)
        await("RemoteViewsService adapter not bound by the platform") { text(id).contains("ISOLATED WIDGET HOST") }
        await("Legacy month service not bound") { ui { it.hosted.getValue(month).findViewById<ListView>(R.id.widget_list)?.count==7 } }
        app.store.applySnapshot(calendar,listOf(DavClient.Resource(calendar+"fixture.ics","\"v3\"",raw("ISOLATED LEGACY UPDATED"))))
        await("Legacy notifyAppWidgetViewDataChanged did not reload") { text(id).contains("ISOLATED LEGACY UPDATED") }
        repeat(2) {
            WidgetUpdates.render(app,intArrayOf(month))
            assertFullMonth(month,320)
        }
    }
}
