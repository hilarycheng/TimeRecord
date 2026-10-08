package com.quickstamp.timerecorder.alarm

import android.content.Context
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant

/** Hidden one-off alarm used only by the Alarms page's "test in 1 minute" action. */
object AlarmTestStore {
    const val TEST_ID = "__time_recorder_test_alarm__"
    private const val PREFS = "explicit_alarm_test_v1"
    private const val KEY_AT = "at"
    private const val KEY_VOLUME = "volume"

    fun prepare(context: Context, at: Long, volume: Int = 100): UserAlarm {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, at)
            .putInt(KEY_VOLUME, volume.coerceIn(1, 100))
            .apply()
        val localTime = Instant.ofEpochMilli(at).atZone(HkTime.zone).toLocalTime().withSecond(0).withNano(0)
        return UserAlarm(
            id = TEST_ID,
            time = localTime,
            enabled = true,
            workingDayOnly = false,
            ringVolume = volume.coerceIn(1, 100),
            label = "Test Alarm",
        )
    }

    fun find(context: Context, id: String): UserAlarm? {
        if (id != TEST_ID) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        return prepare(context, at, prefs.getInt(KEY_VOLUME, 100))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
