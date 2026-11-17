package fr.nauss.calendar

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*

/** Debug source set only. Never seeds events or changes accounts, theme, or production host IDs. */
class WidgetHostActivity : Activity() {
    companion object {
        const val DEBUG_HOST_ID=414702
        const val INSTRUMENTATION_HOST_ID=414703
        private const val BIND_REQUEST=41
    }
    lateinit var host: AppWidgetHost
        private set
    val hosted=linkedMapOf<Int,AppWidgetHostView>()
    private lateinit var container: LinearLayout
    private var pendingId=AppWidgetManager.INVALID_APPWIDGET_ID
    private var compact=false
    private val app get()=application as AgendaApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host=AppWidgetHost(this,if(app.productionWidgetsEnabled) DEBUG_HOST_ID else INSTRUMENTATION_HOST_ID)
        container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(12,32,12,24) }
        setContentView(ScrollView(this).apply {
            addView(container)
            setOnApplyWindowInsetsListener { _,insets ->
                container.setPadding(dp(12),insets.systemWindowInsetTop+dp(12),dp(12),insets.systemWindowInsetBottom+dp(12));insets
            }
        })
        container.addView(TextView(this).apply { setText(R.string.widget_host_title);textSize=18f })
        fun button(label: Int,action: ()->Unit) { container.addView(Button(this).apply { setText(label);setOnClickListener { action() } }) }
        button(R.string.widget_pin_planning) { addWidget(PlanningWidgetProvider::class.java) }
        button(R.string.widget_pin_month) { addWidget(MonthWidgetProvider::class.java) }
        button(R.string.widget_host_resize) {
            compact=!compact
            hosted.keys.toList().forEach { id ->
                val month=AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className==MonthWidgetProvider::class.java.name
                resizeWidget(id,if(month) (if(compact) 280 else 320) else if(compact) 240 else 340,if(compact) (if(month) 260 else 180) else 380)
            }
        }
        button(R.string.widget_host_clear) { removeWidgets() }
        val restored=savedInstanceState?.getIntArray("host_ids")
        if(restored!=null) restored.forEach { if(!app.productionWidgetsEnabled) app.widgetTestTargets.add(it);attach(it) }
        else {
            // This host ID belongs exclusively to this debug tool; never enumerate/delete launcher IDs.
            host.deleteHost()
            if(intent.getBooleanExtra("auto_bind",true)) addWidget(PlanningWidgetProvider::class.java)
        }
    }
    fun addWidget(provider: Class<out AgendaWidgetProvider>,renderingAllowed: Boolean=true): Int {
        val id=host.allocateAppWidgetId();if(!app.productionWidgetsEnabled && renderingAllowed) app.widgetTestTargets.add(id)
        val component=ComponentName(this,provider)
        val width=if(provider==MonthWidgetProvider::class.java) 320 else 340
        val options=Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,width);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,380);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,380)
        }
        if(AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id,component,options)) attach(id)
        else {
            pendingId=id
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options),BIND_REQUEST)
        }
        return id
    }
    private fun attach(id: Int) {
        val info=AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return
        val view=host.createView(this,id,info)
        hosted[id]=view;container.addView(view,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(380)))
        resizeWidget(id,if(info.provider.className==MonthWidgetProvider::class.java.name) 320 else 340,380)
    }
    fun resizeWidget(id: Int,width: Int,height: Int) {
        val view=hosted.getValue(id)
        val available=container.width.takeIf { it>0 }?.minus(container.paddingLeft+container.paddingRight)
            ?: (resources.displayMetrics.widthPixels-dp(24))
        val pixels=minOf(dp(width),available)
        val actualWidth=(pixels/resources.displayMetrics.density).toInt()
        view.layoutParams=LinearLayout.LayoutParams(pixels,dp(height))
        view.updateAppWidgetSize(Bundle(),actualWidth,height,actualWidth,height)
        WidgetUpdates.request(this,intArrayOf(id))
    }
    fun removeWidgets() {
        hosted.forEach { (id,view)->container.removeView(view);host.deleteAppWidgetId(id);app.widgetTestTargets.remove(id) }
        hosted.clear()
        if(pendingId!=AppWidgetManager.INVALID_APPWIDGET_ID) { host.deleteAppWidgetId(pendingId);app.widgetTestTargets.remove(pendingId);pendingId=AppWidgetManager.INVALID_APPWIDGET_ID }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==BIND_REQUEST) {
            if(resultCode==RESULT_OK) attach(pendingId) else { host.deleteAppWidgetId(pendingId);app.widgetTestTargets.remove(pendingId) }
            pendingId=AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }
    override fun onStart() { super.onStart();host.startListening();hosted.keys.forEach { WidgetUpdates.request(this,intArrayOf(it)) } }
    override fun onStop() { host.stopListening();super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putIntArray("host_ids",hosted.keys.toIntArray());super.onSaveInstanceState(outState) }
    override fun onDestroy() { if(isFinishing) removeWidgets();super.onDestroy() }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
}
