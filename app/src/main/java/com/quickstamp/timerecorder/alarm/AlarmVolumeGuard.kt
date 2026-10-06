package com.quickstamp.timerecorder.alarm

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

object AlarmVolumeGuard {
    private const val PREFS = "alarm_volume_guard_v1"
    private const val ACTIVE = "active"
    private const val ORIGINAL_INDEX = "original_index"
    private const val PENDING_PERCENT = "pending_percent"
    private const val ALARM_ID = "alarm_id"
    private const val STARTED_AT = "started_at"

    fun isActive(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)

    fun boost(context: Context, alarmId: String, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) {
            prefs.edit()
                .putBoolean(ACTIVE, true)
                .putInt(ORIGINAL_INDEX, audio.getStreamVolume(AudioManager.STREAM_ALARM))
                .putInt(PENDING_PERCENT, -1)
                .putString(ALARM_ID, alarmId)
                .putLong(STARTED_AT, System.currentTimeMillis())
                .apply()
        }
        setPercent(context, percent.coerceIn(1, 100))
    }

    /** Returns true when an active alarm boost owns STREAM_ALARM and the caller should not overwrite it. */
    fun deferProfileAlarmLevel(context: Context, percent: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) return false
        prefs.edit().putInt(PENDING_PERCENT, percent.coerceIn(0, 100)).apply()
        return true
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ACTIVE, false)) return
        val pending = prefs.getInt(PENDING_PERCENT, -1)
        val original = prefs.getInt(ORIGINAL_INDEX, -1)
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching {
            if (pending >= 0) {
                setPercent(context, pending)
            } else if (original >= 0) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
                val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(AudioManager.STREAM_ALARM) else 0
                audio.setStreamVolume(AudioManager.STREAM_ALARM, original.coerceIn(min, max), 0)
            }
        }
        prefs.edit().clear().apply()
    }

    fun recoverIfNeeded(context: Context) {
        if (isActive(context) && !AlarmPlaybackService.isRunning) restore(context)
    }

    private fun setPercent(context: Context, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(AudioManager.STREAM_ALARM) else 0
        val index = (min + (max - min) * (percent.coerceIn(0, 100) / 100.0)).roundToInt().coerceIn(min, max)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
    }
}
