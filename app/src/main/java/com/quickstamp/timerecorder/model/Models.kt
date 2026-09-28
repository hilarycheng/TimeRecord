package com.quickstamp.timerecorder.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class EventKind { DEFAULT, BUS, EXTRA }
enum class CommuteMode { WORK, HOME }

data class RecorderEvent(
    val id: String,
    val timestamp: Long,
    val label: String,
    val kind: EventKind,
    val route: String? = null,
    val commute: CommuteMode? = null,
    val terminal: Boolean = false,
    val auto: Boolean = false,
)

data class EtaArrival(
    val timestamp: Long,
    val destination: String = "",
    val remark: String = "",
)

data class EtaSnapshot(
    val updatedAt: Long = 0L,
    val routes: Map<String, List<EtaArrival>> = emptyMap(),
    val errorAt: Long = 0L,
    val errorMessage: String? = null,
    val refreshingStartedAt: Long = 0L,
)

data class TrackingState(
    val active: Boolean = false,
    val mode: CommuteMode? = null,
    val startedAt: Long = 0L,
    val heartbeatAt: Long = 0L,
)

data class EtaStops(
    val work: String? = null,
    val home: String? = null,
    val resolvedAt: Long = 0L,
)

data class RecorderSettings(
    val showSeconds: Boolean = false,
    val morningCutoffHour: Int = 10,
)

data class RecorderState(
    val events: List<RecorderEvent> = emptyList(),
    val settings: RecorderSettings = RecorderSettings(),
    val etaWork: EtaSnapshot = EtaSnapshot(),
    val etaHome: EtaSnapshot = EtaSnapshot(),
    val tracking: TrackingState = TrackingState(),
    val etaStops: EtaStops = EtaStops(),
)

object HkTime {
    val zone: ZoneId = ZoneId.of("Asia/Hong_Kong")
    private val dayFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateDisplay = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val timeMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val timeSecond = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    fun now(): Long = System.currentTimeMillis()

    fun date(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    fun today(now: Long = now()): LocalDate = date(now)

    fun dayKey(timestamp: Long): String = date(timestamp).format(dayFormatter)

    fun displayDate(date: LocalDate, today: LocalDate = today()): String = when (date) {
        today -> "今日 · ${date.format(dateDisplay)}"
        today.minusDays(1) -> "昨日 · ${date.format(dateDisplay)}"
        else -> date.format(dateDisplay)
    }

    fun formatTime(timestamp: Long, seconds: Boolean): String {
        val z = Instant.ofEpochMilli(timestamp).atZone(zone)
        return z.format(if (seconds) timeSecond else timeMinute)
    }

    fun at(date: LocalDate, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute, second)).atZone(zone).toInstant().toEpochMilli()

    fun modeAt(timestamp: Long, cutoffHour: Int = 10): CommuteMode {
        val hour = Instant.ofEpochMilli(timestamp).atZone(zone).hour
        return if (hour < cutoffHour) CommuteMode.WORK else CommuteMode.HOME
    }

    fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    fun minuteOfDay(timestamp: Long): Int {
        val z = Instant.ofEpochMilli(timestamp).atZone(zone)
        return z.hour * 60 + z.minute
    }

    fun formatMinuteOfDay(value: Double): String {
        val rounded = value.toInt().coerceIn(0, 1439)
        return "%02d:%02d".format(Locale.ENGLISH, rounded / 60, rounded % 60)
    }
}
