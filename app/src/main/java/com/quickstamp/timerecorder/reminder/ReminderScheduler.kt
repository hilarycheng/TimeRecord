package com.quickstamp.timerecorder.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.data.ReminderTask
import com.quickstamp.timerecorder.model.HkTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.absoluteValue

object ReminderScheduler {
    private const val CHANNEL_ID = "calendar_private_reminders"
    private const val URGENT_CHANNEL_ID = "calendar_private_reminders_urgent"
    private const val ACTION_TRIGGER = "com.quickstamp.timerecorder.REMINDER_TRIGGER"
    private const val ACTION_COMPLETE = "com.quickstamp.timerecorder.REMINDER_COMPLETE"
    private const val EXTRA_HOUR = "hour"
    private const val EXTRA_MINUTE = "minute"
    private const val EXTRA_TASK_KEY = "task_key"
    private const val PREFS = "reminder_scheduler"
    private const val KEY_SLOTS = "slots"
    private const val KEY_POSTED = "posted_task_keys"

    fun scheduleAll(context: Context) {
        val rules = ReminderStore.rules(context).filter { it.enabled }
        val slots = rules.map { it.notifyHour to it.notifyMinute }.distinct()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_SLOTS, "").orEmpty().split(',').mapNotNull { token ->
            val p = token.split(':'); if (p.size == 2) p[0].toIntOrNull()?.let { h -> p[1].toIntOrNull()?.let { m -> h to m } } else null
        }.toSet()
        val fresh = slots.toSet()
        (old - fresh).forEach { (h, m) -> cancelSlot(context, h, m) }
        slots.forEach { (hour, minute) -> scheduleSlot(context, hour, minute) }
        prefs.edit().putString(KEY_SLOTS, slots.joinToString(",") { "${it.first}:${it.second}" }).apply()
        reconcilePostedNotifications(context)
    }

    private fun scheduleSlot(context: Context, hour: Int, minute: Int) {
        val now = java.time.ZonedDateTime.now(HkTime.zone)
        var target = now.toLocalDate().atTime(hour, minute).atZone(HkTime.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val manager = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(hour, minute),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trigger = target.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun cancelSlot(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode(hour, minute),
            Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_TRIGGER },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) { manager.cancel(pi); pi.cancel() }
    }

    fun notifySlot(context: Context, hour: Int, minute: Int) {
        createChannel(context)
        val active = ReminderStore.rules(context).any { it.enabled && it.notifyHour == hour && it.notifyMinute == minute }
        if (!active) { cancelSlot(context, hour, minute); scheduleAll(context); return }
        val today = HkTime.today()
        val tasks = ReminderEngine.tasksForDate(context, today).filter { it.notifyHour == hour && it.notifyMinute == minute }
        tasks.forEach { postTask(context, it, today) }
        scheduleSlot(context, hour, minute)
    }

    private fun postTask(context: Context, task: ReminderTask, date: LocalDate) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            task.taskKey.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val completeIntent = PendingIntent.getBroadcast(
            context,
            task.taskKey.hashCode() xor 0x51AA,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_TASK_KEY, task.taskKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = ReminderEngine.displayStatus(task, date)
        val urgent = task.status == com.quickstamp.timerecorder.data.ReminderTaskStatus.LAST_DAY || task.status == com.quickstamp.timerecorder.data.ReminderTaskStatus.OVERDUE
        val builder = Notification.Builder(context, if (urgent) URGENT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.title)
            .setContentText("${task.ruleName} · $text")
            .setStyle(Notification.BigTextStyle().bigText("${task.ruleName} · $text\n${task.sourceLabel}"))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.checkbox_on_background),
                    "完成",
                    completeIntent,
                ).build()
            )
        nm.notify(notificationId(task.taskKey), builder.build())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        posted += task.taskKey
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    fun complete(context: Context, taskKey: String) {
        ReminderStore.markCompleted(context, taskKey)
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(taskKey))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        posted -= taskKey
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    private fun reconcilePostedNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getStringSet(KEY_POSTED, emptySet()).orEmpty().toMutableSet()
        if (posted.isEmpty()) return
        val active = ReminderEngine.tasksForDate(context, HkTime.today()).map { it.taskKey }.toSet()
        val stale = posted - active
        val nm = context.getSystemService(NotificationManager::class.java)
        stale.forEach { nm.cancel(notificationId(it)) }
        posted.removeAll(stale)
        prefs.edit().putStringSet(KEY_POSTED, posted).apply()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Calendar reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Private and calendar rule reminders"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(URGENT_CHANNEL_ID, "Urgent calendar reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Last-day and overdue required-window reminders"
            }
        )
    }

    private fun requestCode(hour: Int, minute: Int) = 62000 + hour * 60 + minute
    private fun notificationId(key: String) = 700000 + (key.hashCode().absoluteValue % 200000)
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra("hour", 9)
        val minute = intent.getIntExtra("minute", 0)
        ReminderScheduler.notifySlot(context.applicationContext, hour, minute)
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("task_key") ?: return
        ReminderScheduler.complete(context.applicationContext, key)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.scheduleAll(context.applicationContext)
    }
}
