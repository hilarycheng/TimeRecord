package com.quickstamp.timerecorder.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudioProfileAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPLY = "com.quickstamp.timerecorder.audio.APPLY_PROFILE"
        const val ACTION_AFTER_WORK = "com.quickstamp.timerecorder.audio.AFTER_WORK"
        const val EXTRA_RECORDED_AT = "recorded_at"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_APPLY && intent?.action != ACTION_AFTER_WORK) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent?.action == ACTION_AFTER_WORK) {
                    AudioProfileManager.onAfterWorkRecorded(
                        context.applicationContext, intent.getLongExtra(EXTRA_RECORDED_AT, 0L),
                    )
                } else {
                    AudioProfileManager.onScheduledTrigger(context.applicationContext)
                }
            }
            finally { pending.finish() }
        }
    }
}
