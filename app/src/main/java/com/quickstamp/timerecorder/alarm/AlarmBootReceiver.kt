package com.quickstamp.timerecorder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val accepted = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
        if (intent?.action !in accepted) return
        AlarmVolumeGuard.recoverIfNeeded(context.applicationContext)
        AlarmClockScheduler.scheduleAll(context.applicationContext)
    }
}
