package com.quickstamp.timerecorder.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

object AlarmClockStore {
    private const val PREFS = "explicit_alarm_clock_v1"
    private const val KEY = "alarms"

    fun load(context: Context): List<UserAlarm> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isBlank()) continue
                    val hour = o.optInt("hour", -1)
                    val minute = o.optInt("minute", -1)
                    if (hour !in 0..23 || minute !in 0..59) continue
                    add(
                        UserAlarm(
                            id = id,
                            time = LocalTime.of(hour, minute),
                            enabled = o.optBoolean("enabled", true),
                            workingDayOnly = o.optBoolean("workingDayOnly", true),
                            ringVolume = o.optInt("ringVolume", 100).coerceIn(1, 100),
                            label = o.optString("label", "Alarm").ifBlank { "Alarm" },
                        )
                    )
                }
            }.sortedBy { it.time }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, alarms: List<UserAlarm>) {
        val array = JSONArray()
        alarms.sortedBy { it.time }.forEach { alarm ->
            val a = alarm.normalized()
            array.put(JSONObject().apply {
                put("id", a.id)
                put("hour", a.time.hour)
                put("minute", a.time.minute)
                put("enabled", a.enabled)
                put("workingDayOnly", a.workingDayOnly)
                put("ringVolume", a.ringVolume)
                put("label", a.label)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun upsert(context: Context, alarm: UserAlarm) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.id == alarm.id }
        if (index >= 0) current[index] = alarm.normalized() else current += alarm.normalized()
        save(context, current)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun find(context: Context, id: String): UserAlarm? = load(context).firstOrNull { it.id == id }
}
