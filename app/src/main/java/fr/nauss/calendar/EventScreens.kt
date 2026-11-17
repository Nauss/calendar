@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nauss.calendar

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun <T> Choice(label: String,value: T,options: List<Pair<T,String>>,enabled: Boolean=true,tag: String="choice",onChange: (T)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag),shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(16.dp,12.dp)) {
            Column(Modifier.weight(1f)) { Text(label,style=MaterialTheme.typography.labelMedium);Text(options.firstOrNull { it.first==value }?.second ?: value.toString(),style=MaterialTheme.typography.bodyLarge) }
            Icon(Icons.Default.ArrowDropDown,null)
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=380.dp)) {
            options.forEach { (id,name) -> DropdownMenuItem(text={Text(name)},onClick={open=false;onChange(id)},modifier=Modifier.testTag("choice_$id")) }
        }
    }
}
@Composable fun ConfirmDialog(title: String,text: String,confirm: String,onDismiss: ()->Unit,onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(text)},confirmButton={TextButton(onClick=onConfirm,modifier=Modifier.testTag("confirm_action")){Text(confirm)}},dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}

@Composable fun EventEditor(app: AgendaApplication,state: CalendarState,id: String?,initialStart: LocalDateTime,onBack: ()->Unit,onSaved: (String)->Unit,
    navigationRequest: String?=null,navigating: Boolean=false,onNavigate: (String)->Unit={},onCancelNavigation: (String)->Unit={}) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snack=remember { SnackbarHostState() }
    val initialRecord=remember(id) { id?.let { app.store.event(it) } }
    val submissionId=rememberSaveable(id) { java.util.UUID.randomUUID().toString() }
    LaunchedEffect(submissionId) {
        if(id==null) withContext(Dispatchers.IO) { app.store.committedSubmission(submissionId) }?.let(onSaved)
    }
    val baseVersion=rememberSaveable(id) { initialRecord?.let(::eventRevision) ?: "" }
    val capabilities=remember(id) { initialRecord?.let { runCatching { IcsCodec.inspect(it.raw,ZoneId.systemDefault()) }.getOrNull() } }
    val initial=remember(id) { capabilities?.draft ?: IcsCodec.Draft().apply { start=initialStart;end=start.plusHours(1) } }
    var title by rememberSaveable(id) { mutableStateOf(initial.title) };var location by rememberSaveable(id) { mutableStateOf(initial.location) };var notes by rememberSaveable(id) { mutableStateOf(initial.notes) }
    var zone by rememberSaveable(id) { mutableStateOf(initial.zone) };var startText by rememberSaveable(id) { mutableStateOf(initial.start.toString()) };var endText by rememberSaveable(id) { mutableStateOf(initial.end.toString()) }
    val start=LocalDateTime.parse(startText);val end=LocalDateTime.parse(endText)
    var allDay by rememberSaveable(id) { mutableStateOf(initial.allDay) };var repeat by rememberSaveable(id) { mutableStateOf(initial.repeat) };var reminder by rememberSaveable(id) { mutableIntStateOf(initial.reminderMinutes) }
    val available=state.calendars.filter { it.writable && it.enabled }
    var calendar by rememberSaveable(id) { mutableStateOf(id?.let { app.store.event(it)?.calendar } ?: state.defaultCalendar.takeIf { def -> available.any { it.id==def } } ?: "local") }
    var dirty by rememberSaveable(id) { mutableStateOf(false) };var discard by rememberSaveable(id) { mutableStateOf(false) };var submitting by remember { mutableStateOf(false) }
    val saving=submitting || navigating
    var error by remember { mutableIntStateOf(0) }
    val canEdit=id==null || capabilities?.editable==true
    val canTime=canEdit && capabilities?.timeEditable!=false && !saving
    val canZone=canEdit && capabilities?.timezoneEditable!=false && !saving
    val notificationText=stringResource(R.string.notification_denied)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(!granted) scope.launch { snack.showSnackbar(notificationText) } else scope.launch(Dispatchers.IO) { ReminderScheduler.reschedule(context) } }
    fun back() { if(dirty) discard=true else onBack() }
    BackHandler { if(!saving) { if(navigationRequest!=null) onCancelNavigation(navigationRequest) else back() } }
    LaunchedEffect(navigationRequest,saving,dirty) {
        if(navigationRequest!=null && !saving && !dirty) onNavigate(navigationRequest)
    }
    if(navigationRequest!=null && dirty && !saving) {
        // Each callback captures this request, never a reusable "discard approved" flag.
        ConfirmDialog(stringResource(R.string.discard_changes),"",stringResource(R.string.discard),
            {onCancelNavigation(navigationRequest)},{onNavigate(navigationRequest)})
    } else if(discard && navigationRequest==null && !saving) ConfirmDialog(stringResource(R.string.discard_changes),"",stringResource(R.string.discard),{discard=false},onBack)
    Scaffold(topBar={ TopAppBar(title={Text(stringResource(if(id==null) R.string.new_event else R.string.edit_event))},navigationIcon={IconButton(onClick={back()},enabled=!saving){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back))}},actions={
        TextButton(enabled=canEdit && !saving && (title.isNotBlank() || (id!=null && title==initial.title)),modifier=Modifier.testTag("save_event"),onClick={
            submitting=true;error=0
            scope.launch {
                try {
                    val draft=IcsCodec.Draft().apply { this.title=title;this.location=location;this.notes=notes;this.zone=zone.trim();this.start=start;this.end=end;this.allDay=allDay;this.floating=initial.floating;this.repeat=repeat;this.reminderMinutes=reminder }
                    val newId=app.submissions.save(id,calendar,draft,baseVersion,submissionId.takeIf { id==null }).await()
                    onSaved(newId)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { error=when(e.message) { "STALE_EDITOR" -> R.string.stale_editor; "CUSTOM_TIMEZONE" -> R.string.edit_timezone_mismatch; else -> R.string.invalid_event } } finally { submitting=false }
            }
        }) { Text(stringResource(R.string.save)) }
    }) },snackbarHost={SnackbarHost(snack)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(error!=0) Text(stringResource(error),color=MaterialTheme.colorScheme.error)
            OutlinedTextField(title,{title=it;dirty=true},enabled=canEdit && !saving,label={Text(stringResource(R.string.title))},modifier=Modifier.fillMaxWidth().testTag("event_title"),textStyle=MaterialTheme.typography.headlineSmall,maxLines=3)
            capabilities?.let { EditingRestrictions(it,"general") }
            Choice(stringResource(R.string.calendar),calendar,available.map { it.id to (calendarName(it)+" · "+stringResource(if(it.remote) R.string.remote_label else R.string.local_label)) },enabled=id==null && !saving,tag="event_calendar") { calendar=it;dirty=true }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule,null,modifier=Modifier.padding(end=16.dp));Text(stringResource(R.string.all_day),Modifier.weight(1f));Switch(allDay,{ value -> allDay=value;dirty=true;if(value) {startText=start.toLocalDate().atStartOfDay().toString();endText=maxOf(end.toLocalDate(),start.toLocalDate().plusDays(1)).atStartOfDay().toString()} },enabled=canZone,modifier=Modifier.testTag("all_day"))
            }
            DateTimeFields(stringResource(R.string.start),start,allDay,canTime,"event_start") { next ->
                val duration=java.time.Duration.between(start,end);startText=next.toString();endText=next.plus(duration).toString();dirty=true
            }
            DateTimeFields(stringResource(R.string.end),end,allDay,canTime,"event_end") { endText=it.toString();dirty=true }
            capabilities?.let { EditingRestrictions(it,"time") }
            if(allDay) Text(stringResource(R.string.end_exclusive),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(initial.floating && !allDay) Text(stringResource(R.string.floating_time),style=MaterialTheme.typography.bodyMedium)
            else if(!allDay) OutlinedTextField(zone,{zone=it;dirty=true},enabled=canZone,label={Text(stringResource(R.string.timezone))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("event_timezone"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
            capabilities?.let { EditingRestrictions(it,"zone") }
            Choice(stringResource(R.string.repeat),repeat,listOf("NONE" to stringResource(R.string.none),"DAILY" to stringResource(R.string.daily),"WEEKLY" to stringResource(R.string.weekly),"MONTHLY" to stringResource(R.string.monthly),"YEARLY" to stringResource(R.string.yearly)),enabled=canEdit && capabilities?.recurrenceEditable!=false && !saving,tag="event_repeat") { repeat=it;dirty=true }
            capabilities?.let { EditingRestrictions(it,"repeat") }
            if(id!=null && initial.repeat!="NONE") Text(stringResource(R.string.series_warning),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
            Choice(stringResource(R.string.reminders),reminder,listOf(-1,0,5,10,15,30,60,1440).map { it to when(it) { -1 -> stringResource(if(capabilities?.reminderEditable==false) R.string.imported_reminder else R.string.none);0 -> stringResource(R.string.at_time);else -> stringResource(R.string.reminder_minutes,it) } },enabled=canEdit && capabilities?.reminderEditable!=false && !saving,tag="event_reminder") { minutes ->
                reminder=minutes;dirty=true
                if(minutes>=0 && Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            capabilities?.let { EditingRestrictions(it,"reminder") }
            if(reminder>=0) Text(stringResource(R.string.reminder_precision),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(location,{location=it;dirty=true},enabled=canEdit && !saving,label={Text(stringResource(R.string.location))},leadingIcon={Icon(Icons.Default.LocationOn,null)},modifier=Modifier.fillMaxWidth().testTag("event_location"))
            OutlinedTextField(notes,{notes=it;dirty=true},enabled=canEdit && !saving,label={Text(stringResource(R.string.notes))},minLines=3,modifier=Modifier.fillMaxWidth().testTag("event_notes"))
            Spacer(Modifier.height(32.dp))
        }
    }
}
@Composable private fun EditingRestrictions(info: IcsCodec.Info,section: String?=null) {
    // Editor reasons sit beside the affected fields; detail shows the same short summary.
    fun show(field: String)=section==null || section==field
    if(!info.editable) {
        if(show("general")) Text(stringResource(R.string.protected_event),modifier=Modifier.testTag("editing_restriction"))
    } else {
        if(show("time") && !info.timeEditable) Text(stringResource(if(info.timeProtection=="CUSTOM_TIMEZONE") R.string.edit_custom_timezone else R.string.edit_complex_series),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("editing_restriction"))
        if(show("zone") && info.timeEditable && !info.timezoneEditable) Text(stringResource(R.string.edit_embedded_timezone),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("editing_restriction"))
        if(show("repeat") && !info.recurrenceEditable) Text(stringResource(if(info.draft.repeat=="NONE" && info.timeProtection!="RECURRENCE_EXCEPTIONS") R.string.edit_repeat_unavailable else R.string.edit_recurrence_preserved),style=MaterialTheme.typography.bodySmall)
        if(show("reminder") && !info.reminderEditable) Text(stringResource(R.string.edit_reminders_preserved),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun DateTimeFields(label: String,value: LocalDateTime,allDay: Boolean,enabled: Boolean,tag: String,onChange: (LocalDateTime)->Unit) {
    val context=LocalContext.current
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={nativeDate(context,value.toLocalDate()){onChange(LocalDateTime.of(it,value.toLocalTime()))}},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("${tag}_date")) { Text(value.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
            if(!allDay) TextButton(onClick={TimePickerDialog(context,{ _,h,m -> onChange(value.withHour(h).withMinute(m).withSecond(0).withNano(0)) },value.hour,value.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("${tag}_time")) { Text(value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
        }
    }
}

@Composable fun EventDetail(app: AgendaApplication,id: String?,selected: DisplayEvent?,onBack: ()->Unit,onEdit: ()->Unit,onChanged: ()->Unit,onDeleted: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snack=remember { SnackbarHostState() }
    var record by remember(id) { mutableStateOf(id?.let { app.store.event(it) }) }
    LaunchedEffect(id) { while(true) { record=withContext(Dispatchers.IO){id?.let { app.store.event(it) }};delay(3000) } }
    var deleting by remember { mutableStateOf(false) };var resolving by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) }
    var deletingVersion by remember { mutableStateOf<String?>(null) }
    val errorText=stringResource(R.string.operation_error);val exportedText=stringResource(R.string.exported)
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val raw=record?.raw
        if(uri!=null && raw!=null) scope.launch {
            try { withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(raw.toByteArray(Charsets.UTF_8)) } };snack.showSnackbar(exportedText) } catch(e: Exception) { snack.showSnackbar(errorText) }
        }
    }
    BackHandler { onBack() }
    val info=remember(record?.raw) { record?.let { runCatching { IcsCodec.inspect(it.raw,ZoneId.systemDefault()) }.getOrNull() } }
    // Never combine a selected occurrence from one ICS revision with actions on another.
    val stale=selected!=null && (selected.record.raw!=record?.raw || selected.record.etag!=record?.etag)
    val cal=record?.let { app.store.calendar(it.calendar) }
    val safeResource=cal!=null && record!=null && (!cal.remote || (DavClient.isResourceHref(cal.id,record!!.href) && (record!!.etag==null || DavClient.strongEtag(record!!.etag))))
    val editable=!stale && info?.editable==true && cal?.writable==true && cal.enabled && record?.attempted!=true && record?.deleted!=true && record?.error!="CONFLICT" && safeResource
    if(deleting) ConfirmDialog(stringResource(R.string.delete),stringResource(R.string.delete_confirm),stringResource(R.string.delete),{deleting=false}) {
        deleting=false;busy=true;scope.launch {
            try { withContext(Dispatchers.IO) { app.store.delete(record!!.id,expectedRevision=checkNotNull(deletingVersion));app.afterEventCommit(record!!.id) };onDeleted() } catch(e: Exception) { snack.showSnackbar(errorText) } finally { busy=false }
        }
    }
    if(resolving) ConfirmDialog(stringResource(R.string.conflict),stringResource(R.string.resolve_confirm),stringResource(R.string.done),{resolving=false}) {
        resolving=false;busy=true;scope.launch {
            try {
                withContext(Dispatchers.IO) { synchronized(SyncTasks.accountLock) {
                    val account=checkNotNull(app.vault.read());val e=checkNotNull(record)
                    val client=DavClient(account.endpoint,account.user,account.password,account.localHttp)
                    val remote=try { client.get(e.calendar,e.href) } catch(ex: DavClient.DavException) { if(ex.status==404 || ex.status==410) null else throw ex }
                    app.store.resolveWithLocalCopy(e.id,remote);ReminderScheduler.reschedule(context)
                } }
                onChanged();onBack();SyncTasks.now(context)
            } catch(e: Exception) { snack.showSnackbar(errorText) } finally { busy=false }
        }
    }
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.event_details))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back))}},actions={
        if(!stale && record!=null) TextButton(onClick=onEdit,enabled=editable && !busy,modifier=Modifier.heightIn(min=48.dp).testTag("edit_event")) { Icon(Icons.Default.Edit,null);Text(stringResource(R.string.edit)) }
        if(editable) IconButton(onClick={deletingVersion=record?.let(::eventRevision);deleting=true},enabled=!busy,modifier=Modifier.testTag("delete_event")){Icon(Icons.Default.DeleteOutline,stringResource(R.string.delete))}
    })},snackbarHost={SnackbarHost(snack)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(record==null) Text(stringResource(R.string.unavailable))
            else if(stale) Text(stringResource(R.string.stale_detail),modifier=Modifier.testTag("stale_detail"))
            else {
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text((selected?.occurrence?.title ?: info?.draft?.title)?.ifBlank { stringResource(R.string.untitled) } ?: stringResource(R.string.untitled),style=MaterialTheme.typography.headlineMedium)
                if(cal!=null) Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(14.dp).background(calendarColor(cal.color),RoundedCornerShape(4.dp)));Text(calendarName(cal),Modifier.padding(start=12.dp)); }
                if(cal!=null) Text(stringResource(if(cal.remote) R.string.remote_label else R.string.local_label),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                info?.draft?.let { draft ->
                    val detailZone=if(draft.allDay || draft.floating) ZoneId.systemDefault() else ZoneId.of(draft.zone)
                    val start=selected?.occurrence?.start?.atZone(detailZone)?.toLocalDateTime() ?: draft.start
                    val end=selected?.occurrence?.end?.atZone(detailZone)?.toLocalDateTime() ?: draft.end
                    Text(if(draft.allDay) start.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))+" — "+end.toLocalDate().minusDays(1).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) else start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT))+"\n"+end.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT)),style=MaterialTheme.typography.titleMedium)
                    Text(if(draft.allDay) stringResource(R.string.all_day) else if(draft.floating) stringResource(R.string.floating_time) else draft.zone,style=MaterialTheme.typography.bodyMedium)
                    if(draft.repeat!="NONE") Text(stringResource(R.string.series_warning),color=MaterialTheme.colorScheme.primary)
                    val detailLocation=selected?.occurrence?.location ?: draft.location
                    val detailNotes=selected?.occurrence?.notes ?: draft.notes
                    if(detailLocation.isNotBlank()) Text(detailLocation,style=MaterialTheme.typography.bodyLarge)
                    if(detailNotes.isNotBlank()) Text(detailNotes,style=MaterialTheme.typography.bodyLarge)
                    if(draft.reminderMinutes>=0) Text(stringResource(R.string.reminder_minutes,draft.reminderMinutes)+"\n"+stringResource(R.string.reminder_precision),style=MaterialTheme.typography.bodySmall)
                }
                if(record!!.error=="CONFLICT") {
                    Text(stringResource(R.string.conflict),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.conflict_help))
                    OutlinedButton(onClick={resolving=true},enabled=!busy) { Text(stringResource(R.string.resolve_copy)) }
                } else if(record!!.pending) Text(stringResource(if(record!!.attempted) R.string.pending_locked else R.string.pending,1),color=MaterialTheme.colorScheme.primary)
                info?.let { EditingRestrictions(it) } ?: Text(stringResource(R.string.protected_event))
                if(!safeResource) Text(stringResource(R.string.edit_resource_protected))
                else if(info?.editable==true && !editable && !record!!.pending) Text(stringResource(R.string.read_only))
                OutlinedButton(onClick={export.launch("agenda-event.ics")},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.FileDownload,null);Spacer(Modifier.width(10.dp));Text(stringResource(R.string.export)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
