@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nauss.calendar

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class WidgetLaunch(val serial: Long,val route: WidgetRoute)
private data class IncomingNavigation(val token: String,val eventId: String?,val widget: WidgetRoute?) {
    companion object {
        val saver=Saver<IncomingNavigation?,ArrayList<String>>(
            save={ request -> request?.let { arrayListOf(it.token,it.eventId ?: "",it.widget?.kind?.name ?: "",it.widget?.date?.toString() ?: "",it.widget?.id ?: "",it.widget?.start?.toString() ?: "",it.widget?.occurrence ?: "") } },
            restore={ fields -> IncomingNavigation(fields[0],fields[1].ifEmpty { null },fields[2].ifEmpty { null }?.let {
                WidgetRoute(RouteKind.valueOf(it),fields[3].ifEmpty { null }?.let(LocalDate::parse),fields[4].ifEmpty { null },fields[5].ifEmpty { null }?.let(Instant::parse),fields[6].ifEmpty { null })
            }) }
        )
    }
}
class MainActivity : ComponentActivity() {
    private var requestedEvent by mutableStateOf<String?>(null)
    private var widgetLaunch by mutableStateOf<WidgetLaunch?>(null)
    private var launchSerial=0L
    private fun receive(intent: Intent) {
        requestedEvent=intent.getStringExtra("event_id")
        widgetLaunch=WidgetIntents.read(intent)?.let { WidgetLaunch(++launchSerial,it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(savedInstanceState==null) receive(intent) else {
            requestedEvent=savedInstanceState.getString("agenda.pendingEvent")
            if(savedInstanceState.getBoolean("agenda.pendingWidget")) widgetLaunch=WidgetIntents.read(intent)?.let { WidgetLaunch(++launchSerial,it) }
        }
        setContent { AgendaRoot(requestedEvent,consumeEvent={ requestedEvent=null },widgetLaunch=widgetLaunch,consumeWidget={ widgetLaunch=null }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);receive(intent) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("agenda.pendingEvent",requestedEvent)
        outState.putBoolean("agenda.pendingWidget",widgetLaunch!=null)
        super.onSaveInstanceState(outState)
    }
}

@Composable fun calendarName(c: CalendarRow): String = when {
    c.id=="local" -> stringResource(R.string.local_calendar)
    c.name.isBlank() -> stringResource(R.string.recovered_calendar)
    else -> c.name
}
fun calendarColor(value: String): Color = try {
    // Apple calendar-color is RRGGBBAA, not Android AARRGGBB.
    Color(android.graphics.Color.parseColor(if(value.length==9) value.substring(0,7) else value))
} catch(e: Exception) { Color(0xFF1A73E8) }
fun nativeDate(context: android.content.Context,date: LocalDate,selected: (LocalDate)->Unit) {
    DatePickerDialog(context,{ _,y,m,d -> selected(LocalDate.of(y,m+1,d)) },date.year,date.monthValue-1,date.dayOfMonth).show()
}

@Composable fun AgendaRoot(requestedEvent: String?,consumeEvent: ()->Unit,model: CalendarModel=viewModel(),widgetLaunch: WidgetLaunch?=null,consumeWidget: ()->Unit={}) {
    val state by model.state.collectAsState(); val context=LocalContext.current;val scope=rememberCoroutineScope()
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) };val date=LocalDate.parse(dateText)
    var viewName by rememberSaveable { mutableStateOf(model.app.store.meta("view",CalendarView.MONTH.name)) };val view=CalendarView.entries.firstOrNull { it.name==viewName } ?: CalendarView.MONTH
    var route by rememberSaveable { mutableStateOf("home") };var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val occurrenceSaver=remember(model.app) { Saver<DisplayEvent?,ArrayList<String>>(
        save={ event -> event?.let { arrayListOf(it.record.id,it.occurrence.start.toString(),it.occurrence.key) } },
        restore={ fields -> runCatching {
            val start=Instant.parse(fields[1]);val zone=ZoneId.systemDefault();val db=model.app.store
            val request=WidgetRoute(RouteKind.EVENT,start.atZone(zone).toLocalDate(),fields[0],start,fields[2])
            WidgetRoutes.resolve(request,db.event(fields[0])?.let { listOf(it) } ?: emptyList(),db.calendars(),zone).event
        }.getOrNull() }
    ) }
    var selectedOccurrence by rememberSaveable(stateSaver=occurrenceSaver) { mutableStateOf<DisplayEvent?>(null) }
    var editorStart by rememberSaveable { mutableStateOf(LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).toString()) }
    var editorSession by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var incoming by rememberSaveable(stateSaver=IncomingNavigation.saver) { mutableStateOf<IncomingNavigation?>(null) }
    var acceptedNavigation by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) };var query by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var navigationRevision by rememberSaveable { mutableIntStateOf(0) }
    var unavailableRoute by remember { mutableStateOf(false) }
    val dark=when(state.theme) { "dark" -> true;"light" -> false;else -> isSystemInDarkTheme() }
    val colors=if(dark) darkColorScheme(
        primary=Color(0xFFA8C7FA),onPrimary=Color(0xFF062E6F),primaryContainer=Color(0xFF0842A0),onPrimaryContainer=Color(0xFFD3E3FD),
        secondary=Color(0xFFBCC7DC),onSecondary=Color(0xFF273141),secondaryContainer=Color(0xFF3D4758),onSecondaryContainer=Color(0xFFDCE2F9),
        tertiary=Color(0xFFADC6E8),onTertiary=Color(0xFF18324E),tertiaryContainer=Color(0xFF304966),onTertiaryContainer=Color(0xFFD3E4FF),
        surface=Color(0xFF131314),onSurface=Color(0xFFE3E3E3),background=Color(0xFF131314),onBackground=Color(0xFFE3E3E3),surfaceContainer=Color(0xFF1E1F20),
        surfaceContainerLow=Color(0xFF191A1C),surfaceContainerHigh=Color(0xFF282A2D),surfaceContainerHighest=Color(0xFF333538),onSurfaceVariant=Color(0xFFC4C7C5)
    ) else lightColorScheme(
        primary=Color(0xFF1A73E8),onPrimary=Color.White,primaryContainer=Color(0xFFD3E3FD),onPrimaryContainer=Color(0xFF041E49),
        secondary=Color(0xFF545F71),onSecondary=Color.White,secondaryContainer=Color(0xFFE1E7F0),onSecondaryContainer=Color(0xFF18212F),
        tertiary=Color(0xFF485F7B),onTertiary=Color.White,tertiaryContainer=Color(0xFFD3E4FF),onTertiaryContainer=Color(0xFF001C38),
        surface=Color.White,onSurface=Color(0xFF1F1F1F),background=Color.White,onBackground=Color(0xFF1F1F1F),surfaceContainer=Color(0xFFF1F3F4),
        surfaceContainerLow=Color(0xFFF8F9FA),surfaceContainerHigh=Color(0xFFE8EAED),surfaceContainerHighest=Color(0xFFE1E3E6),onSurfaceVariant=Color(0xFF444746)
    )
    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    LaunchedEffect(date,view,searching,revision) { while(true) { model.load(date,view,searching);delay(4000) } }
    fun selectEvent(event: DisplayEvent) { selectedId=event.record.id;selectedOccurrence=event;route="detail" }
    fun create(start: LocalDateTime) { selectedId=null;selectedOccurrence=null;editorStart=start.toString();editorSession=java.util.UUID.randomUUID().toString();route="editor" }
    // Consume the Activity delivery, not the user's draft. The pending request is saved
    // separately so recreation cannot replay an intent or lose a discard confirmation.
    LaunchedEffect(requestedEvent,widgetLaunch) {
        if(requestedEvent!=null || widgetLaunch!=null) {
            incoming=IncomingNavigation(java.util.UUID.randomUUID().toString(),requestedEvent,widgetLaunch?.route)
            acceptedNavigation=null
            consumeEvent();consumeWidget()
        }
    }
    LaunchedEffect(incoming,route,acceptedNavigation) {
        incoming?.let { request ->
            if(route=="editor" && acceptedNavigation!=request.token) return@LaunchedEffect
            val resolved=request.widget?.let { widget -> withContext(Dispatchers.IO) {
                val db=model.app.store
                WidgetRoutes.resolve(widget,widget.id?.let { db.event(it) }?.let { listOf(it) } ?: emptyList(),db.calendars(),ZoneId.systemDefault())
            } }
            if(incoming!=request) return@LaunchedEffect
            searching=false;query="";selectedId=null;selectedOccurrence=null;route="home";navigationRevision++
            if(resolved==null) { selectedId=request.eventId;route="detail" } else {
            resolved.date?.let { dateText=it.toString() }
            when(resolved.kind) {
                RouteKind.EVENT -> resolved.event?.let(::selectEvent)
                RouteKind.CREATE -> resolved.date?.let { create(it.atTime(9,0)) }
                RouteKind.DAY -> viewName=CalendarView.DAY.name
                RouteKind.PLANNING -> viewName=CalendarView.AGENDA.name
                RouteKind.UNAVAILABLE -> unavailableRoute=true
            }
            }
            incoming=null;acceptedNavigation=null
        }
    }
    MaterialTheme(colorScheme=colors) {
        if(unavailableRoute) AlertDialog(onDismissRequest={unavailableRoute=false},text={Text(stringResource(R.string.widget_route_unavailable))},confirmButton={TextButton(onClick={unavailableRoute=false}) { Text(stringResource(R.string.done)) }})
        Surface(Modifier.fillMaxSize()) {
            when(route) {
                "editor" -> key(editorSession) {
                    EventEditor(model.app,state,selectedId,LocalDateTime.parse(editorStart),onBack={route=if(selectedId==null) "home" else "detail"},onSaved={ id -> selectedId=id;selectedOccurrence=null;revision++;route="detail" },
                        navigationRequest=incoming?.token,navigating=incoming!=null && acceptedNavigation==incoming?.token,
                        onNavigate={ token -> if(incoming?.token==token && requestedEvent==null && widgetLaunch==null) acceptedNavigation=token },
                        onCancelNavigation={ token -> if(incoming?.token==token) {incoming=null;acceptedNavigation=null} })
                }
                "detail" -> EventDetail(model.app,selectedId,selectedOccurrence,onBack={route="home"},onEdit={editorSession=java.util.UUID.randomUUID().toString();route="editor"},onChanged={revision++},onDeleted={revision++;route="home"})
                "settings" -> SettingsScreen(model.app,state,onBack={revision++;route="home"},onChanged={revision++},onEvent={selectedId=it;selectedOccurrence=null;route="detail"})
                else -> {
                    val drawer=rememberDrawerState(DrawerValue.Closed)
                    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
                    BackHandler(searching && !drawer.isOpen) { searching=false;query="" }
                    ModalNavigationDrawer(drawerState=drawer,drawerContent={
                        ModalDrawerSheet(Modifier.widthIn(max=340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineSmall,color=colors.primary,modifier=Modifier.padding(28.dp))
                            CalendarView.entries.forEach { entry ->
                                NavigationDrawerItem(label={Text(stringResource(entry.label))},selected=view==entry,onClick={viewName=entry.name;model.app.store.setMeta("view",entry.name);scope.launch { drawer.close() }},icon={Icon(when(entry) {CalendarView.AGENDA->Icons.Default.ViewAgenda;CalendarView.MONTH->Icons.Default.CalendarMonth;else->Icons.Default.ViewWeek},null)},modifier=Modifier.padding(horizontal=12.dp).testTag("view_${entry.name}"))
                            }
                            HorizontalDivider(Modifier.padding(vertical=16.dp))
                            Text(stringResource(R.string.calendars),style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(horizontal=28.dp,vertical=8.dp))
                            state.calendars.forEach { cal ->
                                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable { model.app.store.visibility(cal.id,!cal.visible);revision++ }.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(cal.visible,{model.app.store.visibility(cal.id,it);revision++},colors=CheckboxDefaults.colors(checkedColor=calendarColor(cal.color)),modifier=Modifier.testTag("calendar_${cal.id}"))
                                    Column(Modifier.weight(1f).padding(vertical=8.dp)) { Text(calendarName(cal));Text(stringResource(if(cal.remote) R.string.remote_label else R.string.local_label),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant) }
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical=12.dp))
                            NavigationDrawerItem(label={Text(stringResource(R.string.settings))},selected=false,onClick={scope.launch { drawer.close() };route="settings"},icon={Icon(Icons.Default.Settings,null)},modifier=Modifier.padding(12.dp).testTag("settings"))
                            Spacer(Modifier.height(20.dp))
                        }
                    }) {
                        Scaffold(
                            topBar={ TopAppBar(title={
                                if(searching) TextField(query,{query=it},placeholder={Text(stringResource(R.string.search_hint))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("search_input"),colors=TextFieldDefaults.colors(focusedContainerColor=colors.surface,unfocusedContainerColor=colors.surface))
                                else TextButton(onClick={nativeDate(context,date){dateText=it.toString();navigationRevision++}},contentPadding=PaddingValues(horizontal=0.dp),colors=ButtonDefaults.textButtonColors(contentColor=colors.onSurface)) {
                                    Text(date.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) },maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleLarge)
                                    Icon(Icons.Default.ArrowDropDown,null)
                                }
                            },navigationIcon={IconButton(onClick={if(searching) {searching=false;query=""} else scope.launch{drawer.open()}},modifier=Modifier.testTag("navigation")){Icon(if(searching) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,stringResource(if(searching) R.string.back else R.string.menu))}},actions={
                                if(!searching) {
                                    IconButton(onClick={dateText=LocalDate.now().toString();navigationRevision++},modifier=Modifier.testTag("today")) { Icon(Icons.Default.Today,stringResource(R.string.today)) }
                                    IconButton(onClick={searching=true},modifier=Modifier.testTag("search")) { Icon(Icons.Default.Search,stringResource(R.string.search)) }
                                }
                            }) },
                            floatingActionButton={FloatingActionButton(onClick={create(date.atTime(9,0))},modifier=Modifier.testTag("create_event"),containerColor=colors.primaryContainer,contentColor=colors.onPrimaryContainer) { Icon(Icons.Default.Add,stringResource(R.string.new_event)) }}
                        ) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding)) {
                                if(state.syncing || state.pending>0 || state.syncError.isNotEmpty()) {
                                    Surface(color=if(state.syncError.isNotEmpty()) colors.errorContainer else colors.secondaryContainer,modifier=Modifier.fillMaxWidth().clickable { route="settings" }.testTag("sync_status")) {
                                        Text(when {state.syncing->stringResource(R.string.syncing);state.syncError=="CONFLICT"->stringResource(R.string.conflict);state.syncError.isNotEmpty()->stringResource(R.string.sync_error);else->stringResource(R.string.pending,state.pending)},style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=16.dp,vertical=10.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                                if(state.unsupported.isNotEmpty()) TextButton(onClick={route="settings"}) { Text(stringResource(R.string.parse_error),style=MaterialTheme.typography.bodySmall) }
                                if(searching) {
                                    Text(stringResource(R.string.search_range),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(16.dp,6.dp))
                                    EventAgenda(state.events.filter { query.isNotBlank() && (it.occurrence.title.contains(query,true) || it.occurrence.location.contains(query,true)) },onEvent=::selectEvent,emptyText=stringResource(R.string.search_empty),from=date.withDayOfYear(1),until=date.withDayOfYear(1).plusYears(1),scrollKey=query)
                                } else {
                                    if(view!=CalendarView.AGENDA) {
                                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                                        IconButton(onClick={dateText=when(view) {CalendarView.MONTH->date.minusMonths(1);CalendarView.AGENDA->date.minusDays(7);else->date.minusDays(view.days.toLong())}.toString()}) { Icon(Icons.Default.ChevronLeft,stringResource(R.string.previous)) }
                                        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        IconButton(onClick={dateText=when(view) {CalendarView.MONTH->date.plusMonths(1);CalendarView.AGENDA->date.plusDays(7);else->date.plusDays(view.days.toLong())}.toString()}) { Icon(Icons.Default.ChevronRight,stringResource(R.string.next)) }
                                    }
                                    HorizontalDivider()
                                    }
                                    if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if(state.calendars.isNotEmpty() && state.calendars.none { it.visible }) Text(stringResource(R.string.hidden_calendars),Modifier.padding(24.dp))
                                    when(view) {
                                        CalendarView.MONTH -> MonthCalendar(date,state.events,{dateText=it.toString()},::selectEvent)
                                        CalendarView.AGENDA -> EventAgenda(state.events,::selectEvent,stringResource(R.string.empty_agenda),from=date,until=date.plusDays(view.days.toLong()),scrollKey=date to navigationRevision)
                                        else -> TimedCalendar(firstDay(date,view),view.days,state.events,onDay={dateText=it.toString();viewName=CalendarView.DAY.name},onCreate=::create,onEvent=::selectEvent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
