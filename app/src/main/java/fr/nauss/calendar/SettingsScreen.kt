@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nauss.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import fr.nauss.calendar.core.DavClient
import fr.nauss.calendar.core.IcsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun SettingsScreen(app: AgendaApplication,state: CalendarState,onBack: ()->Unit,onChanged: ()->Unit,onEvent: (String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snackbar=remember { SnackbarHostState() }
    var endpoint by remember { mutableStateOf("") };var username by remember { mutableStateOf("") };var password by remember { mutableStateOf("") };var localHttp by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) };var result by remember { mutableStateOf<List<DavClient.CalendarInfo>?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) };var candidate by remember { mutableStateOf<Account?>(null) };var feedback by remember { mutableIntStateOf(0) }
    var detach by remember { mutableStateOf(false) };var notices by remember { mutableStateOf(false) };var noticeText by remember { mutableStateOf("") }
    val operationError=stringResource(R.string.operation_error)
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO){app.vault.read()}?.let { endpoint=it.endpoint;username=it.user;password=it.password;localHttp=it.localHttp } }
        catch(e: Exception) { feedback=R.string.operation_error }
    }
    BackHandler { onBack() }
    if(detach) ConfirmDialog(stringResource(R.string.detach),stringResource(R.string.detach_confirm),stringResource(R.string.done),{detach=false}) {
        detach=false;scope.launch {
            try { withContext(Dispatchers.IO) { synchronized(SyncTasks.accountLock) { app.store.detachPreservingCache();app.vault.clear() } };endpoint="";username="";password="";result=null;candidate=null;onChanged() }
            catch(e: Exception) { feedback=R.string.detach_pending }
        }
    }
    if(notices) AlertDialog(onDismissRequest={notices=false},title={Text(stringResource(R.string.licenses))},text={Text(noticeText,Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},confirmButton={TextButton(onClick={notices=false}){Text(stringResource(R.string.done))}})
    Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.settings))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back))}})},snackbarHost={SnackbarHost(snackbar)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.appearance),style=MaterialTheme.typography.titleMedium)
            Choice(stringResource(R.string.appearance),state.theme,listOf("system" to stringResource(R.string.system_theme),"light" to stringResource(R.string.light_theme),"dark" to stringResource(R.string.dark_theme))) { app.store.setMeta("theme",it);onChanged() }
            Choice(stringResource(R.string.default_calendar),state.defaultCalendar,state.calendars.filter { it.writable && it.enabled }.map { it.id to calendarName(it) }) { app.store.setMeta("default_calendar",it);onChanged() }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text(stringResource(R.string.status),style=MaterialTheme.typography.titleMedium)
            if(state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.pending>0) Text(stringResource(R.string.pending,state.pending),color=MaterialTheme.colorScheme.primary)
            if(state.syncError.isNotEmpty()) Text(stringResource(when(state.syncError) { "CONFLICT"->R.string.conflict;"AUTH"->R.string.auth_error;else->R.string.sync_error }),color=MaterialTheme.colorScheme.error)
            Text(state.lastSync.toLongOrNull()?.let { stringResource(R.string.last_sync,Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))) } ?: stringResource(R.string.never_synced),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={SyncTasks.now(context);onChanged()},enabled=!state.syncing,modifier=Modifier.fillMaxWidth().testTag("manual_sync")) { Icon(Icons.Default.Sync,null);Spacer(Modifier.width(12.dp));Text(stringResource(R.string.sync)) }
            val review=(state.records.filter { it.deleted || it.error=="CONFLICT" }+state.unsupported).distinctBy { it.id }
            if(review.isNotEmpty()) {
                Text(stringResource(R.string.pending_deletions),style=MaterialTheme.typography.titleSmall)
                review.forEach { e ->
                    val label=remember(e.raw) { runCatching { IcsCodec.inspect(e.raw,ZoneId.systemDefault()).draft.title }.getOrDefault("") }
                    OutlinedButton(onClick={onEvent(e.id)},modifier=Modifier.fillMaxWidth()) { Text(label.ifBlank { stringResource(R.string.untitled) }) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text(stringResource(R.string.account),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.account_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(endpoint,{endpoint=it;result=null;candidate=null},label={Text(stringResource(R.string.endpoint))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("server_url"),enabled=!busy)
            OutlinedTextField(username,{username=it;result=null;candidate=null},label={Text(stringResource(R.string.username))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().testTag("server_username"),enabled=!busy)
            OutlinedTextField(password,{password=it;result=null;candidate=null},label={Text(stringResource(R.string.password))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=if(shown) VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={IconButton(onClick={shown=!shown}){Icon(if(shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,stringResource(if(shown) R.string.hide_password else R.string.show_password))}},modifier=Modifier.fillMaxWidth().testTag("server_password"),enabled=!busy)
            Row(verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.local_http),Modifier.weight(1f));Switch(localHttp,{localHttp=it;result=null;candidate=null},enabled=!busy,modifier=Modifier.testTag("local_http")) }
            if(localHttp) Text(stringResource(R.string.http_warning),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            if(feedback!=0) Text(stringResource(feedback),color=MaterialTheme.colorScheme.error)
            Button(onClick={
                busy=true;feedback=0;result=null
                scope.launch {
                    try {
                        val pair=withContext(Dispatchers.IO) {
                            val client=DavClient(endpoint,username,password,localHttp)
                            synchronized(SyncTasks.accountLock) { app.store.assertIdentityChangeAllowed(client.endpoint(),username) }
                            Account(client.endpoint(),username,password,localHttp) to client.discover()
                        }
                        candidate=pair.first;result=pair.second
                        val previous=state.calendars.filter { it.remote && it.enabled }.map { it.id }.toSet()
                        selection=if(previous.isEmpty()) pair.second.map { it.href }.toSet() else pair.second.map { it.href }.filter { it in previous }.toSet()
                        if(pair.second.isEmpty()) feedback=R.string.discover_empty
                    } catch(e: IllegalStateException) { feedback=R.string.account_locked }
                    catch(e: Exception) { feedback=R.string.discover_error }
                    finally { busy=false }
                }
            },enabled=!busy && endpoint.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("discover")) { if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Text(stringResource(R.string.discover)) }
            result?.let { calendars ->
                calendars.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { selection=if(c.href in selection) selection-c.href else selection+c.href },verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(c.href in selection,{selection=if(it) selection+c.href else selection-c.href})
                        Column(Modifier.weight(1f).padding(vertical=8.dp)) { Text(c.name);if(!c.writable) Text(stringResource(R.string.read_only),style=MaterialTheme.typography.bodySmall) }
                    }
                }
                if(calendars.isNotEmpty()) Button(onClick={
                    busy=true;scope.launch {
                        try { withContext(Dispatchers.IO) { synchronized(SyncTasks.accountLock) {
                            val account=checkNotNull(candidate);app.store.assertIdentityChangeAllowed(account.endpoint,account.user)
                            val chosen=calendars.filter { it.href in selection }
                            check(app.store.calendars().filter { it.remote && it.id !in selection }.all { app.store.pending(it.id).isEmpty() })
                            app.vault.save(account);app.store.setIdentity(account.endpoint,account.user);app.store.chooseCalendars(chosen)
                        } };result=null;feedback=0;onChanged();SyncTasks.now(context) }
                        catch(e: Exception) { feedback=R.string.account_locked }
                        finally { busy=false }
                    }
                },enabled=!busy && selection.isNotEmpty(),modifier=Modifier.fillMaxWidth().testTag("connect_calendars")) { Text(stringResource(R.string.connect_selected)) }
            }
            if(state.calendars.any { it.remote }) OutlinedButton(onClick={if(state.pending>0) feedback=R.string.detach_pending else detach=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.detach)) }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text(stringResource(R.string.reminders),style=MaterialTheme.typography.titleMedium)
            if(state.reminderError.isNotEmpty()) Text(stringResource(R.string.reminder_error),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("reminder_error"))
            Text(stringResource(R.string.reminder_precision),style=MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_body),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={scope.launch { try { noticeText=withContext(Dispatchers.IO){context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }};notices=true } catch(e: Exception) { snackbar.showSnackbar(operationError) } }}) { Text(stringResource(R.string.licenses)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
