package com.quickstamp.timerecorder.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/** One rolling exact alarm for the next instant. Multiple simultaneous notices are all posted. */
object ReminderScheduler {
    private const val CHANNEL_ID = "calendar_private_reminders"
    private const val URGENT_CHANNEL_ID = "calendar_private_reminders_urgent"
    private const val ACTION_TRIGGER = "com.quickstamp.timerecorder.REMINDER_TRIGGER"
    private const val ACTION_COMPLETE = "com.quickstamp.timerecorder.REMINDER_COMPLETE"
    private const val EXTRA_TASK_KEY = "task_key"
    private const val PREFS = "reminder_scheduler_v2"
    private const val KEY_NEXT_AT = "next_at"
    private const val KEY_POSTED = "posted_notices"
    private const val KEY_DELIVERED = "delivered_notices"
    private const val NEXT_ALARM_CODE = 62377
    private const val POSTED_SEPARATOR = "\u001F"

    enum class TestNotificationResult { SENT, SENT_SILENT, PERMISSION_REQUIRED, DISABLED }

    /** Immediately post a sample via the real channel. No AlarmManager or delivery ledger writes. */
    fun testNotificationNow(
        context: Context, notice: ReminderEngine.NotificationOccurrence,
    ): TestNotificationResult {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return TestNotificationResult.PERMISSION_REQUIRED
        }
        createChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val channelId = if (notice.urgent) URGENT_CHANNEL_ID else CHANNEL_ID
        if (!nm.areNotificationsEnabled()) return TestNotificationResult.DISABLED
        val channel = nm.getNotificationChannel(channelId)
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return TestNotificationResult.DISABLED
        }
        val openApp = PendingIntent.getActivity(
            context, notice.key.hashCode() xor 0x35709,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val preview = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("[測試] ${notice.title}")
            .setContentText(notice.description)
            .setStyle(Notification.BigTextStyle().bigText(notice.description))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        // Tagged notifications cannot collide with the integer IDs of production reminders.
        nm.notify("reminder_rule_simulation", notificationId(notice.key), preview)
        return if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null) {
            TestNotificationResult.SENT_SILENT
        } else TestNotificationResult.SENT
    }

    @Synchronized
    fun scheduleAll(context: Context) {
        val app = context.applicationContext
        createChannels(app)
        cancelLegacyAlarmSlots(app)
        val now = Instant.now()
        // Rolling scheduling avoids hundreds of per-rule pending intents and exact-alarm limits.
        val next = ReminderEngine.notificationOccurrences(app, now.plusMillis(200), now.plus(400, ChronoUnit.DAYS))
            .firstOrNull { !wasDelivered(app, it.key) }
        val manager = app.getSystemService(AlarmManager::class.java)
        val pi = pendingAlarm(app)
        manager.cancel(pi)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (next == null) {
            prefs.edit().remove(KEY_NEXT_AT).apply()
        } else {
            val millis = next.at.toEpochMilli()
            prefs.edit().putLong(KEY_NEXT_AT, millis).apply()
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                // The OS may delay it; UI should prompt user to grant Alarms & reminders access.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }
        reconcilePostedNotifications(app)
    }

    @Synchronized
    fun notifyDue(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expected = prefs.getLong(KEY_NEXT_AT, 0L)
        val now = Instant.now()
        if (expected == 0L || expected > now.toEpochMilli() + 30_000L) return
        createChannels(app)
        val delivered = prefs.getStringSet(KEY_DELIVERED, emptySet()).orEmpty().toMutableSet()
        // Supports short Doze delays without posting historical reminders days late.
        val from = Instant.ofEpochMilli(expected).minusSeconds(90)
        val due = ReminderEngine.notificationOccurrences(app, from, now.plusSeconds(1))
            .filter { it.at.toEpochMilli() <= now.toEpochMilli() && it.at.isAfter(now.minus(12, ChronoUnit.HOURS)) }
        due.forEach { notice ->
            if (notice.key !in delivered &&
                (notice.keepAfterCompletion || !ReminderStore.isCompleted(app, notice.taskKey))) {
                postNotice(app, notice)
                delivered += notice.key
            }
        }
        // Keep a bounded dedup ledger for repeated onReceive / reboot delivery.
        val recent = delivered.toList().takeLast(400).toSet()
        prefs.edit().putStringSet(KEY_DELIVERED, recent).remove(KEY_NEXT_AT).apply()
        scheduleAll(app)
    }

    private fun wasDelivered(context: Context, noticeKey: String) =
        noticeKey in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DELIVERED, emptySet()).orEmpty()

    private fun postNotice(context: Context, n: ReminderEngine.NotificationOccurrence) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val contentIntent = PendingIntent.getActivity(
            context, n.key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, if (n.urgent) URGENT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(n.title)
            .setContentText(n.description)
            .setStyle(Notification.BigTextStyle().bigText(n.description))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (n.canComplete) {
            val completeIntent = PendingIntent.getBroadcast(
                context, n.taskKey.hashCode() xor 0x51AA,
                Intent(context, ReminderActionReceiver::class.java).apply {
                    action = ACTION_COMPLETE
                    putExtra(EXTRA_TASK_KEY, n.taskKey)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.checkbox_on_background),
                "完成", completeIntent,
            ).build())
        }
        nm.notify(notificationId(n.key), builder.build())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        posted += "${n.key}$POSTED_SEPARATOR${n.taskKey}"
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    fun complete(context: Context, taskKey: String) {
        ReminderStore.markCompleted(context, taskKey)
        cancelTaskNotifications(context, taskKey)
        scheduleAll(context)
    }

    fun undoComplete(context: Context, taskKey: String) {
        ReminderStore.clearCompletion(context, taskKey)
        scheduleAll(context)
    }

    private fun cancelTaskNotifications(context: Context, taskKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        val nm = context.getSystemService(NotificationManager::class.java)
        val matching = posted.filter { it.substringAfter(POSTED_SEPARATOR, "") == taskKey }
        val now = Instant.now()
        val keep = ReminderEngine.notificationOccurrences(context, now.minus(2, ChronoUnit.DAYS), now.plusSeconds(1))
            .filter { it.taskKey == taskKey && it.keepAfterCompletion }.mapTo(hashSetOf()) { it.key }
        val removable = matching.filter { it.substringBefore(POSTED_SEPARATOR) !in keep }
        removable.forEach { nm.cancel(notificationId(it.substringBefore(POSTED_SEPARATOR))) }
        posted.removeAll(removable.toSet())
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    private fun reconcilePostedNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        if (posted.isEmpty()) return
        val now = Instant.now()
        // Includes countdowns, and preserves multiple independent notices per task.
        val live = ReminderEngine.notificationOccurrences(context, now.minus(2, ChronoUnit.DAYS), now.plusSeconds(1))
            .associateBy { it.key }
        val stale = posted.filter { encoded ->
            val occurrence = live[encoded.substringBefore(POSTED_SEPARATOR)]
            occurrence == null || (!occurrence.keepAfterCompletion &&
                ReminderStore.isCompleted(context, encoded.substringAfter(POSTED_SEPARATOR, "")))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        stale.forEach { nm.cancel(notificationId(it.substringBefore(POSTED_SEPARATOR))) }
        posted.removeAll(stale.toSet())
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    private fun cancelLegacyAlarmSlots(context: Context) {
        val oldPrefs = context.getSharedPreferences("reminder_scheduler", Context.MODE_PRIVATE)
        if (!oldPrefs.contains("slots")) return
        val manager = context.getSystemService(AlarmManager::class.java)
        oldPrefs.getString("slots", "").orEmpty().split(',').forEach { token ->
            val p = token.split(':')
            val hour = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val minute = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val pi = PendingIntent.getBroadcast(context, 62000 + hour * 60 + minute,
                Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_TRIGGER },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { manager.cancel(pi); pi.cancel() }
        }
        oldPrefs.edit().remove("slots").apply()
    }

    private fun pendingAlarm(context: Context) = PendingIntent.getBroadcast(
        context, NEXT_ALARM_CODE,
        Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_TRIGGER },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Calendar reminders", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "App-local calendar and linked task reminders" })
        nm.createNotificationChannel(NotificationChannel(
            URGENT_CHANNEL_ID, "Urgent calendar reminders", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Final eligible day for a linked task" })
    }

    private fun notificationId(key: String) = 700000 + (key.hashCode().absoluteValue % 200000)
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Use a worker thread rather than blocking the system broadcast main thread on Calendar queries.
        val pending = goAsync()
        Thread {
            try { ReminderScheduler.notifyDue(context.applicationContext) }
            finally { pending.finish() }
        }.start()
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("task_key") ?: return
        val pending = goAsync()
        Thread {
            try { ReminderScheduler.complete(context.applicationContext, key) }
            finally { pending.finish() }
        }.start()
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { ReminderScheduler.scheduleAll(context.applicationContext) }
            finally { pending.finish() }
        }.start()
    }
}
