package com.quickstamp.timerecorder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS = "com.quickstamp.timerecorder.alarm.DISMISS"
        const val ACTION_SNOOZE = "com.quickstamp.timerecorder.alarm.SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val alarmId = intent?.getStringExtra(AlarmClockReceiver.EXTRA_ALARM_ID).orEmpty()
        when (intent?.action) {
            ACTION_SNOOZE -> {
                if (alarmId.isNotBlank()) AlarmClockScheduler.scheduleSnooze(context, alarmId)
                context.stopService(Intent(context, AlarmPlaybackService::class.java))
            }
            ACTION_DISMISS -> {
                context.stopService(Intent(context, AlarmPlaybackService::class.java))
                if (alarmId == AlarmTestStore.TEST_ID) AlarmTestStore.clear(context)
            }
        }
    }
}
