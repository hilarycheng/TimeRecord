package com.quickstamp.timerecorder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.quickstamp.timerecorder.audio.AudioProfileEngine
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.model.HkTime

class AlarmClockReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RING = "com.quickstamp.timerecorder.alarm.RING"
        const val ACTION_SNOOZE_RING = "com.quickstamp.timerecorder.alarm.SNOOZE_RING"
        const val ACTION_TEST_RING = "com.quickstamp.timerecorder.alarm.TEST_RING"
        const val EXTRA_ALARM_ID = "alarm_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID) ?: return
        if (intent.action != ACTION_RING && intent.action != ACTION_SNOOZE_RING && intent.action != ACTION_TEST_RING) return
        val alarm = AlarmClockStore.find(context, alarmId) ?: AlarmTestStore.find(context, alarmId) ?: return
        if (!alarm.enabled) return
        if (alarm.workingDayOnly) {
            val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
            val today = HkTime.today()
            val governmentDayOff = !AudioProfileEngine.isWorkingDay(today, holidays)
            val personalDayOff = PersonalDayOffCalendar.isLeaveDay(context, today)
            if (governmentDayOff || personalDayOff) {
                if (alarm.id != AlarmTestStore.TEST_ID) AlarmClockScheduler.schedule(context, alarm)
                return
            }
        }
        if (intent.action == ACTION_RING) {
            AlarmClockScheduler.schedule(context, alarm)
        }
        val service = Intent(context, AlarmPlaybackService::class.java)
            .setAction(AlarmPlaybackService.ACTION_START)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        ContextCompat.startForegroundService(context, service)
    }
}
