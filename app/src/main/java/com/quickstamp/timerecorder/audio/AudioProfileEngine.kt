package com.quickstamp.timerecorder.audio

import com.quickstamp.timerecorder.model.HkTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object AudioProfileEngine {
    fun isWorkingDay(date: LocalDate, holidays: Set<LocalDate>): Boolean =
        date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && date !in holidays

    private fun isWorkingDay(
        date: LocalDate,
        holidays: Set<LocalDate>,
        personalLeaveDates: Set<LocalDate>,
    ): Boolean = isWorkingDay(date, holidays) && date !in personalLeaveDates

    fun resolve(
        config: AudioScheduleConfig,
        now: Long,
        holidays: Set<LocalDate>,
        personalLeaveDates: Set<LocalDate> = emptySet(),
    ): AudioProfileResolution {
        val z = Instant.ofEpochMilli(now).atZone(HkTime.zone)
        val date = z.toLocalDate()
        val time = z.toLocalTime()
        val working = isWorkingDay(date, holidays, personalLeaveDates)

        val active = if (!working) {
            AudioProfileId.DEFAULT
        } else {
            config.scheduled().filter { !it.time.isAfter(time) }.maxByOrNull { it.time }?.id ?: AudioProfileId.DEFAULT
        }

        val next = nextActualTransition(config, now, holidays, personalLeaveDates, active)
        val dayLabel = when {
            date in personalLeaveDates -> "放假"
            date in holidays -> "公眾假期"
            date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> "Weekend"
            else -> "Working Day"
        }
        return AudioProfileResolution(
            profileId = active,
            label = config.label(active),
            levels = config.levels(active),
            dayLabel = dayLabel,
            nextTransitionAt = next?.first,
            nextProfileLabel = next?.second,
        )
    }

    private fun nextActualTransition(
        config: AudioScheduleConfig,
        now: Long,
        holidays: Set<LocalDate>,
        personalLeaveDates: Set<LocalDate>,
        current: AudioProfileId,
    ): Pair<Long, String>? {
        val currentZ = Instant.ofEpochMilli(now).atZone(HkTime.zone)
        val startDate = currentZ.toLocalDate()
        val candidates = mutableListOf<Pair<Long, AudioProfileId>>()

        for (offset in 0..10) {
            val date = startDate.plusDays(offset.toLong())
            // Midnight is a deliberate return to Default. This also guarantees
            // weekends/public holidays never keep an Office profile overnight.
            val midnight = LocalDateTime.of(date, LocalTime.MIDNIGHT).atZone(HkTime.zone).toInstant().toEpochMilli()
            candidates += midnight to AudioProfileId.DEFAULT
            if (isWorkingDay(date, holidays, personalLeaveDates)) {
                config.scheduled().forEach { p ->
                    val at = LocalDateTime.of(date, p.time).atZone(HkTime.zone).toInstant().toEpochMilli()
                    candidates += at to p.id
                }
            }
        }

        return candidates
            .asSequence()
            .filter { it.first > now + 500L }
            .sortedBy { it.first }
            .firstOrNull { it.second != current }
            ?.let { it.first to config.label(it.second) }
    }
}
