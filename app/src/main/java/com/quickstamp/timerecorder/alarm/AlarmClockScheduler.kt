package com.quickstamp.timerecorder.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.model.HkTime

object AlarmClockScheduler {
    private const val SNOOZE_MINUTES = 10L

    fun scheduleAll(context: Context) {
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        AlarmClockStore.load(context).forEach { alarm -> schedule(context, alarm, holidays) }
    }

    fun schedule(context: Context, alarm: UserAlarm, holidays: Set<java.time.LocalDate> = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()) {
        cancelRegular(context, alarm.id)
        val at = AlarmClockEngine.nextTrigger(alarm, HkTime.now(), holidays) ?: return
        val manager = context.getSystemService(AlarmManager::class.java)
        if (!canScheduleExact(context)) return
        val operation = PendingIntent.getBroadcast(
            context,
            requestCode(alarm.id, 0),
            Intent(context, AlarmClockReceiver::class.java)
                .setAction(AlarmClockReceiver.ACTION_RING)
                .putExtra(AlarmClockReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            context,
            requestCode(alarm.id, 7),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ALARMS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
    }

    fun scheduleSnooze(context: Context, alarmId: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (!canScheduleExact(context)) return
        val at = HkTime.now() + SNOOZE_MINUTES * 60_000L
        val operation = PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, 1),
            Intent(context, AlarmClockReceiver::class.java)
                .setAction(AlarmClockReceiver.ACTION_SNOOZE_RING)
                .putExtra(AlarmClockReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            context,
            requestCode(alarmId, 8),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ALARMS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
    }

    fun cancel(context: Context, alarmId: String) {
        cancelRegular(context, alarmId)
        cancelSnooze(context, alarmId)
    }

    fun nextTrigger(context: Context, alarm: UserAlarm): Long? {
        if (!canScheduleExact(context)) return null
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        return AlarmClockEngine.nextTrigger(alarm, HkTime.now(), holidays)
    }

    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    private fun cancelRegular(context: Context, alarmId: String) {
        cancelPending(context, requestCode(alarmId, 0), AlarmClockReceiver.ACTION_RING, alarmId)
    }

    private fun cancelSnooze(context: Context, alarmId: String) {
        cancelPending(context, requestCode(alarmId, 1), AlarmClockReceiver.ACTION_SNOOZE_RING, alarmId)
    }

    private fun cancelPending(context: Context, code: Int, action: String, alarmId: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, AlarmClockReceiver::class.java).setAction(action).putExtra(AlarmClockReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun requestCode(id: String, salt: Int): Int = (id.hashCode() * 31 + salt) and 0x7fffffff
}
