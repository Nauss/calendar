package fr.nauss.calendar

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.nauss.calendar.core.IcsCodec
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Run on the explicitly owned, muted emulator after granting POST_NOTIFICATIONS in the test command. */
@RunWith(AndroidJUnit4::class)
class ReminderInstrumentedTest {
    private fun awaitAbsent(manager: NotificationManager,id: String) {
        val deadline=System.currentTimeMillis()+5000
        while(manager.activeNotifications.any { it.id==id.hashCode() } && System.currentTimeMillis()<deadline) Thread.sleep(50)
        assertTrue(manager.activeNotifications.none { it.id==id.hashCode() })
    }
    @Test fun failedNotifyRemainsEligibleAndConcurrentRefreshCannotDuplicate() {
        val context=ApplicationProvider.getApplicationContext<Context>();val app=isolatedApp(context);val db=app.store
        val d=IcsCodec.Draft().apply { title="ISOLATED FAILED NOTIFY";start=LocalDateTime.now().minusMinutes(1);end=start.plusHours(1);reminderMinutes=0 }
        val id=db.save(null,"local",d);val manager=context.getSystemService(NotificationManager::class.java)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        try {
            app.notificationHook={throw IllegalStateException("INJECTED_NOTIFY_FAILURE")}
            ReminderScheduler.reschedule(context)
            assertEquals("DELIVERY",db.meta("reminder_error"));assertTrue(manager.activeNotifications.none { it.id==id.hashCode() })
            app.notificationHook={calls.incrementAndGet()}
            val workers=List(3) { Thread { ReminderScheduler.reschedule(context) }.apply { start() } };workers.forEach { it.join(10000);assertFalse(it.isAlive) }
            assertEquals(1,calls.get());assertEquals("",db.meta("reminder_error"))
            db.delete(id);ReminderScheduler.reschedule(context);awaitAbsent(manager,id)
        } finally { app.notificationHook=null;if(db.event(id)!=null) db.delete(id);ReminderScheduler.cancelNotification(context,id) }
    }
    @Test fun silentNativeNotificationAndCancellation() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        Assume.assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        val db=isolatedApp(context).store
        val draft=IcsCodec.Draft().apply { title="ISOLATED SILENT REMINDER FIXTURE";start=LocalDateTime.now().minusMinutes(1);end=start.plusHours(1);reminderMinutes=0 }
        val id=db.save(null,"local",draft)
        val manager=context.getSystemService(NotificationManager::class.java)
        try {
            ReminderScheduler.channel(context)
            context.sendBroadcast(Intent(context,ReminderReceiver::class.java).setAction("fr.nauss.calendar.REMIND"))
            val deadline=System.currentTimeMillis()+15000
            while(manager.activeNotifications.none { it.id==id.hashCode() } && System.currentTimeMillis()<deadline) Thread.sleep(100)
            val posted=manager.activeNotifications.single { it.id==id.hashCode() }.notification
            assertEquals(draft.title,posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,posted.visibility)
            assertNotNull(posted.contentIntent);assertNotNull(posted.publicVersion)
            val channel=manager.getNotificationChannel(posted.channelId)
            assertNull(channel.sound);assertFalse(channel.shouldVibrate())
            ReminderScheduler.cancelNotification(context,id)
            awaitAbsent(manager,id)
        } finally { db.delete(id);ReminderScheduler.cancelNotification(context,id);ReminderScheduler.reschedule(context) }
    }
    @Test fun rescheduleDeliversOverdueOnceAndNeverAfterDeletion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val db=isolatedApp(context).store
        val draft=IcsCodec.Draft().apply { title="ISOLATED LATE REMINDER";start=LocalDateTime.now().minusMinutes(1);end=start.plusHours(1);reminderMinutes=0 }
        val id=db.save(null,"local",draft)
        val manager=context.getSystemService(NotificationManager::class.java)
        try {
            ReminderScheduler.reschedule(context)
            val deadline=System.currentTimeMillis()+10000
            while(manager.activeNotifications.none { it.id==id.hashCode() } && System.currentTimeMillis()<deadline) Thread.sleep(50)
            assertEquals(1,manager.activeNotifications.count { it.id==id.hashCode() })
            val first=manager.activeNotifications.single { it.id==id.hashCode() }.postTime
            repeat(2) { ReminderScheduler.reschedule(context) }
            assertEquals(first,manager.activeNotifications.single { it.id==id.hashCode() }.postTime)
            db.delete(id);ReminderScheduler.reschedule(context)
            awaitAbsent(manager,id)
            val deleted=db.save(null,"local",draft);db.delete(deleted)
            ReminderScheduler.reschedule(context)
            assertTrue(manager.activeNotifications.none { it.id==deleted.hashCode() })
        } finally { if(db.event(id)!=null) db.delete(id);ReminderScheduler.cancelNotification(context,id) }
    }
}
