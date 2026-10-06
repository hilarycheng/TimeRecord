package com.quickstamp.timerecorder.alarm

import java.time.LocalTime
import java.util.UUID

data class UserAlarm(
    val id: String = UUID.randomUUID().toString(),
    val time: LocalTime,
    val enabled: Boolean = true,
    val workingDayOnly: Boolean = true,
    val ringVolume: Int = 100,
    val label: String = "Alarm",
) {
    fun normalized() = copy(ringVolume = ringVolume.coerceIn(1, 100))
}
