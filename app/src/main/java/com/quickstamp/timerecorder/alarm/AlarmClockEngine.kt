package com.quickstamp.timerecorder.alarm

import com.quickstamp.timerecorder.audio.AudioProfileEngine
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant
import java.time.LocalDateTime

object AlarmClockEngine {
    fun nextTrigger(
        alarm: UserAlarm,
        now: Long,
        holidays: Set<java.time.LocalDate>,
        personalLeaveDates: Set<java.time.LocalDate> = emptySet(),
    ): Long? {
        if (!alarm.enabled) return null
        val nowZ = Instant.ofEpochMilli(now).atZone(HkTime.zone)
        val startDate = nowZ.toLocalDate()
        for (offset in 0..370) {
            val date = startDate.plusDays(offset.toLong())
            if (alarm.workingDayOnly && (!AudioProfileEngine.isWorkingDay(date, holidays) || date in personalLeaveDates)) continue
            val candidate = LocalDateTime.of(date, alarm.time).atZone(HkTime.zone).toInstant().toEpochMilli()
            if (candidate > now + 500L) return candidate
        }
        return null
    }
}
