package com.quickstamp.timerecorder.model

import java.time.DayOfWeek
import java.time.LocalDate

data class JourneySample(
    val bus: RecorderEvent,
    val end: RecorderEvent,
    val durationMs: Long,
)

data class WaitSample(
    val stop: RecorderEvent,
    val bus: RecorderEvent,
    val durationMs: Long,
)

data class DaySummary(
    val workCommuteMs: Long? = null,
    val workWaitMs: Long = 0L,
    val workBusMs: Long = 0L,
    val workMs: Long? = null,
    val homeCommuteMs: Long? = null,
    val homeWaitMs: Long = 0L,
    val homeBusMs: Long = 0L,
    val complete: Boolean = false,
)

object RecorderAnalytics {
    fun journeySamples(events: List<RecorderEvent>, route: String, mode: CommuteMode): List<JourneySample> {
        val all = events.sortedBy { it.timestamp }
        return all.filter { it.kind == EventKind.BUS && it.route == route && commuteOf(it) == mode }.mapNotNull { bus ->
            val sameDay = all.filter { it.timestamp > bus.timestamp && HkTime.date(it.timestamp) == HkTime.date(bus.timestamp) }
            val preferred = sameDay.firstOrNull { e ->
                if (mode == CommuteMode.WORK) e.label in setOf("到餐廳", "到公司", "落車")
                else e.label in setOf("落車", "到屋企")
            }
            val end = preferred ?: sameDay.firstOrNull { it.kind != EventKind.EXTRA && it.kind != EventKind.STOP }
            end?.let { JourneySample(bus, it, it.timestamp - bus.timestamp) }
        }
    }

    fun waitJourneySamples(
        events: List<RecorderEvent>,
        route: String? = null,
        mode: CommuteMode? = null,
    ): List<WaitSample> {
        val all = events.sortedBy { it.timestamp }
        return all.filter { stop ->
            stop.kind == EventKind.STOP && (mode == null || commuteOf(stop) == mode)
        }.mapNotNull { stop ->
            val stopMode = commuteOf(stop)
            val bus = all.firstOrNull {
                it.timestamp >= stop.timestamp &&
                    HkTime.date(it.timestamp) == HkTime.date(stop.timestamp) &&
                    it.kind == EventKind.BUS &&
                    commuteOf(it) == stopMode
            }
            bus?.takeIf { route == null || it.route == route }
                ?.let { WaitSample(stop, it, it.timestamp - stop.timestamp) }
        }
    }

    fun waitSamples(events: List<RecorderEvent>, mode: CommuteMode? = null): List<Long> =
        waitJourneySamples(events, mode = mode).map { it.durationMs }

    fun daySummary(events: List<RecorderEvent>, date: LocalDate): DaySummary {
        val day = events.filter { HkTime.date(it.timestamp) == date }.sortedBy { it.timestamp }

        fun segment(startLabel: String, endLabels: Set<String>): Long? {
            val start = day.firstOrNull { it.label == startLabel } ?: return null
            val end = day.firstOrNull { it.timestamp >= start.timestamp && it.label in endLabels } ?: return null
            return end.timestamp - start.timestamp
        }

        fun waits(mode: CommuteMode): Long = waitJourneySamples(day, mode = mode).sumOf { it.durationMs }
        fun buses(mode: CommuteMode): Long {
            val routes = day
                .filter { it.kind == EventKind.BUS && commuteOf(it) == mode }
                .mapNotNull { it.route }
                .distinct()
            return routes.sumOf { route -> journeySamples(day, route, mode).sumOf { it.durationMs } }
        }

        return DaySummary(
            workCommuteMs = segment("返工", setOf("到公司")),
            workWaitMs = waits(CommuteMode.WORK),
            workBusMs = buses(CommuteMode.WORK),
            workMs = segment("到公司", setOf("放工")),
            homeCommuteMs = segment("放工", setOf("到屋企")),
            homeWaitMs = waits(CommuteMode.HOME),
            homeBusMs = buses(CommuteMode.HOME),
            complete = day.any { it.terminal },
        )
    }

    fun medianLong(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2L
    }

    fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    fun percentileLong(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val index = ((s.lastIndex) * p.coerceIn(0.0, 1.0)).toInt()
        return s[index]
    }

    fun weekdayJourneyMedians(samples: List<JourneySample>): Map<DayOfWeek, Long> =
        samples.groupBy { HkTime.date(it.bus.timestamp).dayOfWeek }
            .mapValues { (_, v) -> medianLong(v.map { it.durationMs }) }
            .filterKeys { it.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value }

    fun weekdayBoardingMedians(events: List<RecorderEvent>): Map<DayOfWeek, Double> =
        events.groupBy { HkTime.date(it.timestamp).dayOfWeek }
            .mapValues { (_, v) -> medianDouble(v.map { HkTime.secondOfDay(it.timestamp).toDouble() }) }
            .filterKeys { it.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value }

    fun weekdayWaitMedians(samples: List<WaitSample>): Map<DayOfWeek, Long> =
        samples.groupBy { HkTime.date(it.bus.timestamp).dayOfWeek }
            .mapValues { (_, v) -> medianLong(v.map { it.durationMs }) }
            .filterKeys { it.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value }

    fun commuteOf(event: RecorderEvent): CommuteMode = event.commute ?: HkTime.modeAt(event.timestamp)
}
