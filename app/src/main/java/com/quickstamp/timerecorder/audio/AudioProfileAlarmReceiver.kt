package com.quickstamp.timerecorder.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudioProfileAlarmReceiver : BroadcastReceiver() {
    companion object { const val ACTION_APPLY = "com.quickstamp.timerecorder.audio.APPLY_PROFILE" }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_APPLY) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AudioProfileManager.onScheduledTrigger(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}
