package fr.nauss.calendar

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.*
import java.util.concurrent.Executors
import fr.nauss.calendar.core.IcsCodec

object ReminderScheduler {
    private const val CHANNEL="agenda-reminders-silent-v1"
    private fun channelId(context: Context)=if(context.agenda().reminderNamespace=="production") CHANNEL else "$CHANNEL-${context.agenda().reminderNamespace}"
    private val executor=Executors.newSingleThreadExecutor()
    private data class Reminder(val event: EventRow,val occurrence: IcsCodec.Occurrence,val at: Long)
    private fun receipt(reminder: Reminder)="notified:${reminder.event.id}:${reminder.occurrence.key}"
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId(context),context.getString(R.string.reminders),NotificationManager.IMPORTANCE_DEFAULT).apply {
            description=context.getString(R.string.reminder_precision);setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
    }
    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context,1,Intent(context,ReminderReceiver::class.java).setAction("fr.nauss.calendar.REMIND").setData(Uri.parse("agenda://reminders/${context.agenda().reminderNamespace}")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun upcoming(context: Context,now: Long): List<Reminder> {
        val db=context.agenda().store;val zone=ZoneId.systemDefault();val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var unsupported=false
        val result=db.events().flatMap { event ->
            try {
                IcsCodec.expand(event.raw,today.minusDays(1),today.plusDays(60),zone).mapNotNull { o ->
                    val at=o.start.toEpochMilli()-o.reminderMinutes*60_000L
                    val reminder=Reminder(event,o,at)
                    if(o.reminderMinutes>=0 && at>now-3_600_000 && db.meta("notified:${event.id}")!=o.key && db.meta(receipt(reminder))!="1") reminder else null
                }
            } catch(e: Exception) { unsupported=true;emptyList() }
        }.sortedBy { it.at }
        db.setMeta("reminder_error",if(unsupported) "UNSUPPORTED" else "")
        return result
    }
    /** Same serialized path for alarm delivery and refresh; no recursive scheduling. */
    @Synchronized fun reschedule(context: Context) {
        val app=context.agenda();val db=app.store
        // A delete/edit/sync cannot interleave between the snapshot and notify.
        synchronized(db) {
        val notifications=context.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.filter { it.tag==app.reminderNamespace }.forEach { active ->
            val current=active.notification.extras.getString("agenda_event_id")?.let { db.event(it) }
            if(current==null || current.deleted || contentVersion(current.raw)!=active.notification.extras.getString("agenda_event_version")) notifications.cancel(active.tag,active.id)
        }
        val manager=context.getSystemService(AlarmManager::class.java);val intent=alarmIntent(context);manager.cancel(intent)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        channel(context)
        if(notifications.getNotificationChannel(channelId(context)).importance==NotificationManager.IMPORTANCE_NONE) return
        val now=System.currentTimeMillis();val reminders=upcoming(context,now)
        var failed=false
        reminders.filter { it.at<=now }.forEach { reminder ->
            try {
            val e=reminder.event;val o=reminder.occurrence
            val intent=Intent(context,MainActivity::class.java).setData(Uri.parse("agenda://event/${e.id}")).putExtra("event_id",e.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(context,channelId(context)).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(o.title.ifBlank { context.getString(R.string.untitled) }).setContentText(context.getString(R.string.reminder_event))
                .setContentIntent(tap).setAutoCancel(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addExtras(Bundle().apply { putString("agenda_event_id",e.id);putString("agenda_event_version",contentVersion(e.raw)) })
                .setPublicVersion(NotificationCompat.Builder(context,channelId(context)).setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.reminder_event)).build()).build()
            app.postReminder(e.id,notification)
            db.setMeta(receipt(reminder),"1")
            } catch(e: Exception) { failed=true }
        }
        if(failed) db.setMeta("reminder_error","DELIVERY")
        val next=reminders.firstOrNull { it.at>now }?.at
        val at=if(failed) minOf(next ?: Long.MAX_VALUE,now+60_000) else next
        if(at!=null && app.reminderAlarmsEnabled) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,intent)
        }
    }
    fun background(context: Context,result: BroadcastReceiver.PendingResult) {
        executor.execute { try { reschedule(context) } catch(e: Exception) { context.agenda().store.setMeta("reminder_error","SCHEDULE") } finally { result.finish() } }
    }
    @Synchronized fun cancelNotification(context: Context,eventId: String) {
        NotificationManagerCompat.from(context).cancel(context.agenda().reminderNamespace,eventId.hashCode())
        if(context.agenda().reminderNamespace=="production") NotificationManagerCompat.from(context).cancel(eventId.hashCode())
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val namespace=intent.data?.lastPathSegment
        if(intent.action=="fr.nauss.calendar.REMIND" && (namespace==context.agenda().reminderNamespace || namespace==null)) ReminderScheduler.background(context,goAsync())
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) ReminderScheduler.background(context,goAsync())
    }
}
