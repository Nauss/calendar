package fr.nauss.calendar

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.nauss.calendar.core.TimedLayout
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale


fun DisplayEvent.touches(day: LocalDate): Boolean {
    val lo=day.atStartOfDay(ZoneId.systemDefault()).toInstant();val hi=day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
    return occurrence.start<hi && (occurrence.end>lo || (occurrence.start==occurrence.end && occurrence.start>=lo))
}
@Composable fun occurrenceTime(event: DisplayEvent): String {
    val o=event.occurrence;val zone=ZoneId.systemDefault()
    return if(o.allDay) stringResource(R.string.all_day) else o.start.atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))+" – "+o.end.atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
@Composable private fun EmptyCalendar(message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=44.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(stringResource(R.string.no_events),style=MaterialTheme.typography.titleMedium)
        Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
    }
}
@Composable fun EventLine(event: DisplayEvent,onClick: ()->Unit) {
    val o=event.occurrence;val time=occurrenceTime(event)
    val base=calendarColor(event.calendar.color);val foreground=calendarEventTextColor(base)
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).clip(RoundedCornerShape(12.dp)).background(base).clickable(onClick=onClick).heightIn(min=48.dp).padding(horizontal=14.dp,vertical=10.dp).testTag("event_${event.record.id}")) {
        Text(o.title.ifBlank { stringResource(R.string.untitled) },style=MaterialTheme.typography.titleMedium,color=foreground)
        Text(time,style=MaterialTheme.typography.bodyMedium,color=foreground)
        if(o.location.isNotBlank()) Text(o.location,style=MaterialTheme.typography.bodySmall,color=foreground)
        if(event.record.pending) Text(stringResource(if(event.record.error=="CONFLICT") R.string.conflict else R.string.pending,1),style=MaterialTheme.typography.labelSmall,color=foreground)
    }
}
@Composable private fun PlanningCard(row: AgendaRow,onEvent: (DisplayEvent)->Unit) {
    val event=row.event;val base=calendarColor(event.calendar.color);val foreground=calendarEventTextColor(base)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(base).clickable { onEvent(event) }.heightIn(min=48.dp).padding(horizontal=14.dp,vertical=10.dp).testTag("event_${event.record.id}")) {
        Text(event.occurrence.title.ifBlank { stringResource(R.string.untitled) },style=MaterialTheme.typography.titleMedium,color=foreground)
        Text(occurrenceTime(event),style=MaterialTheme.typography.bodyMedium,color=foreground)
        if(row.dayCount>1) Text(stringResource(R.string.event_day_part,row.dayIndex,row.dayCount),style=MaterialTheme.typography.labelMedium,color=foreground)
        if(event.occurrence.location.isNotBlank()) Text(event.occurrence.location,style=MaterialTheme.typography.bodySmall,color=foreground)
        if(event.record.pending) Text(stringResource(if(event.record.error=="CONFLICT") R.string.conflict else R.string.pending,1),style=MaterialTheme.typography.labelSmall,color=foreground)
    }
}
@Composable fun EventAgenda(events: List<DisplayEvent>,onEvent: (DisplayEvent)->Unit,emptyText: String,
                           from: LocalDate?=null,until: LocalDate?=null,scrollKey: Any?=null) {
    val zone=ZoneId.systemDefault();val today=LocalDate.now();val listState=rememberLazyListState()
    val first=from ?: events.minOfOrNull { it.occurrence.start.atZone(zone).toLocalDate() } ?: today
    val end=until ?: events.maxOfOrNull { it.occurrence.end.atZone(zone).toLocalDate().plusDays(1) } ?: first.plusDays(1)
    val rows=remember(events,first,end,zone) { agendaRows(events,first,end,zone) }
    LaunchedEffect(scrollKey) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("agenda_list"),state=listState,contentPadding=PaddingValues(start=8.dp,end=16.dp,top=12.dp,bottom=100.dp)) {
        if(rows.isEmpty()) item { EmptyCalendar(emptyText) }
        rows.groupBy { it.day }.forEach { (day,list) ->
            items(list,key={it.key}) { row ->
                val firstInDay=row==list.first()
                Row(Modifier.fillMaxWidth().padding(top=if(firstInDay) 16.dp else 0.dp,bottom=6.dp),verticalAlignment=Alignment.Top) {
                    Column(Modifier.width(56.dp).padding(top=2.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        if(firstInDay) {
                            Text(day.format(DateTimeFormatter.ofPattern("EEE",Locale.getDefault())),style=MaterialTheme.typography.labelMedium,color=if(day==today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.sizeIn(minWidth=40.dp,minHeight=40.dp).clip(CircleShape).background(if(day==today) MaterialTheme.colorScheme.primary else Color.Transparent).semantics { contentDescription=day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) },contentAlignment=Alignment.Center) {
                                Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.headlineSmall,color=if(day==today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                            if(day.month!=first.month || day.year!=first.year) Text(day.format(DateTimeFormatter.ofPattern("MMM",Locale.getDefault())),style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.weight(1f)) { PlanningCard(row,onEvent) }
                }
            }
        }
    }
}
@Composable fun MonthCalendar(selected: LocalDate,events: List<DisplayEvent>,onDate: (LocalDate)->Unit,onEvent: (DisplayEvent)->Unit) {
    val start=selected.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));val today=LocalDate.now()
    LazyColumn(Modifier.fillMaxSize().testTag("month_grid"),contentPadding=PaddingValues(bottom=96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top=10.dp)) {
                (0..6).forEach { i -> Text(start.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEEEE",Locale.getDefault())),Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        items((0..5).toList()) { row ->
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { col ->
                    val day=start.plusDays((row*7+col).toLong());val matches=events.filter { it.touches(day) }
                    val fullDate=day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                    Column(Modifier.weight(1f).heightIn(min=60.dp).clickable { onDate(day) }.semantics { contentDescription=fullDate }.testTag("day_$day").padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.sizeIn(minWidth=34.dp,minHeight=34.dp).clip(CircleShape).background(when(day) { selected -> MaterialTheme.colorScheme.primary;today -> MaterialTheme.colorScheme.primaryContainer;else -> Color.Transparent }),contentAlignment=Alignment.Center) {
                            Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.bodyMedium,fontWeight=if(day==today) FontWeight.Bold else FontWeight.Normal,color=when {day==selected -> MaterialTheme.colorScheme.onPrimary;day.month!=selected.month -> MaterialTheme.colorScheme.outline;else -> MaterialTheme.colorScheme.onSurface})
                        }
                        Row(Modifier.height(9.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically) { matches.take(3).forEach { Box(Modifier.size(4.dp).clip(CircleShape).background(calendarColor(it.calendar.color))) } }
                    }
                }
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
        }
        item { Text(selected.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(16.dp,18.dp)) }
        val selectedEvents=events.filter { it.touches(selected) }.sortedWith(compareBy<DisplayEvent> { !it.occurrence.allDay }.thenBy { it.occurrence.start })
        if(selectedEvents.isEmpty()) item { EmptyCalendar(stringResource(R.string.empty_day)) }
        items(selectedEvents,key={it.key}) { e -> EventLine(e){onEvent(e)} }
    }
}

@Composable fun TimedCalendar(start: LocalDate,days: Int,events: List<DisplayEvent>,onDay: (LocalDate)->Unit,onCreate: (LocalDateTime)->Unit,onEvent: (DisplayEvent)->Unit) {
    val density=LocalDensity.current;val zone=ZoneId.systemDefault();val today=LocalDate.now()
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while(true) { now=LocalTime.now();delay(60_000) } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("time_grid")) {
        val gutter=50.dp*maxOf(1f,density.fontScale);val cellWidth=maxOf((maxWidth-gutter)/days,if(density.fontScale>1.4f) 88.dp else 48.dp)
        val hourHeight=if(density.fontScale>1.4f) 96.dp else 64.dp
        val horizontal=rememberScrollState();val vertical=rememberScrollState()
        LaunchedEffect(start,days) { vertical.scrollTo(with(density){(hourHeight*7).roundToPx()}) }
        Column(Modifier.horizontalScroll(horizontal).width(gutter+cellWidth*days).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().heightIn(min=66.dp)) {
                Spacer(Modifier.width(gutter))
                repeat(days) { index ->
                    val day=start.plusDays(index.toLong())
                    Column(Modifier.width(cellWidth).clickable { onDay(day) }.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(day.format(DateTimeFormatter.ofPattern("EEEEE",Locale.getDefault())),style=MaterialTheme.typography.labelSmall)
                        Box(Modifier.sizeIn(minWidth=34.dp,minHeight=34.dp).clip(CircleShape).background(if(day==today) MaterialTheme.colorScheme.primary else Color.Transparent),contentAlignment=Alignment.Center) {
                            Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.titleMedium,color=if(day==today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if(events.any { it.occurrence.allDay }) {
                Row(Modifier.fillMaxWidth().heightIn(max=154.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.width(gutter).heightIn(min=48.dp),contentAlignment=Alignment.TopCenter) {
                        Icon(Icons.Default.WbSunny,stringResource(R.string.all_day),Modifier.padding(top=8.dp).size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    repeat(days) { index ->
                        val day=start.plusDays(index.toLong())
                        Column(Modifier.width(cellWidth).padding(2.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            events.filter { it.occurrence.allDay && it.touches(day) }.forEach { event -> EventBlock(event,Modifier.fillMaxWidth().heightIn(min=48.dp),onEvent) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).verticalScroll(vertical)) {
                Row(Modifier.height(hourHeight*24+48.dp).fillMaxWidth()) {
                    Column(Modifier.width(gutter)) {
                        repeat(24) { hour -> Box(Modifier.height(hourHeight).fillMaxWidth(),contentAlignment=Alignment.TopCenter) { Text(String.format(Locale.getDefault(),"%02d:00",hour),style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp).testTag("hour_label_$hour")) } }
                    }
                    repeat(days) { index ->
                        val day=start.plusDays(index.toLong())
                        Box(Modifier.width(cellWidth).fillMaxHeight()) {
                            Column(Modifier.fillMaxSize()) {
                                repeat(24) { hour ->
                                    val description=day.atTime(hour,0).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT))
                                    Box(Modifier.height(hourHeight).fillMaxWidth().border(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)).clickable { onCreate(day.atTime(hour,0)) }.semantics { contentDescription=description }.testTag("slot_${day}_$hour"))
                                }
                            }
                            val dayEvents=events.filter { !it.occurrence.allDay && it.touches(day) }
                            val byKey=dayEvents.associateBy { it.key }
                            val lo=day.atStartOfDay(zone).toInstant();val hi=day.plusDays(1).atStartOfDay(zone).toInstant()
                            val spans=dayEvents.map { e ->
                                val a=if(e.occurrence.start<lo) 0 else e.occurrence.start.atZone(zone).toLocalTime().let { it.hour*60+it.minute }
                                val b=if(e.occurrence.end>=hi) 1440 else e.occurrence.end.atZone(zone).toLocalTime().let { it.hour*60+it.minute }
                                TimedLayout.Span(e.key,a,b)
                            }
                            TimedLayout.arrange(spans).forEach { placement ->
                                val event=byKey.getValue(placement.span.id);val width=cellWidth/placement.columns
                                EventBlock(event,Modifier.offset(x=width*placement.column,y=hourHeight*(placement.span.start/60f)).width(width-2.dp).height(hourHeight*((placement.span.end-placement.span.start)/60f)),onEvent)
                            }
                            if(day==today) Box(Modifier.offset(y=hourHeight*((now.hour*60+now.minute)/60f)).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error))
                        }
                    }
                }
            }
        }
    }
}
internal fun calendarEventTextColor(base: Color): Color {
    val luminance=base.luminance()
    val whiteContrast=1.05f/(luminance+0.05f)
    val blackContrast=(luminance+0.05f)/0.05f
    return if(blackContrast>=whiteContrast) Color.Black else Color.White
}

@Composable private fun EventBlock(event: DisplayEvent,modifier: Modifier,onEvent: (DisplayEvent)->Unit) {
    val base=calendarColor(event.calendar.color);val time=occurrenceTime(event)
    val foreground=calendarEventTextColor(base)
    val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(modifier.padding(horizontal=1.dp,vertical=1.dp).clip(RoundedCornerShape(4.dp)).background(base).clickable { onEvent(event) }.semantics { contentDescription="${event.occurrence.title}, $time" }.testTag("block_${event.record.id}")) {
        val compact=maxWidth<120.dp*fontScale || maxHeight<56.dp*fontScale
        Column(Modifier.padding(horizontal=4.dp,vertical=if(compact) 1.dp else 4.dp)) {
            Text(event.occurrence.title.ifBlank { stringResource(R.string.untitled) },color=foreground,style=MaterialTheme.typography.labelMedium,maxLines=if(compact) 1 else 2,softWrap=!compact,overflow=TextOverflow.Ellipsis)
            if(!compact && !event.occurrence.allDay) Text(time,color=foreground,style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
        }
    }
}
