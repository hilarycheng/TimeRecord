package com.quickstamp.timerecorder.data

import android.content.Context
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EventKind
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.model.RecorderEvent
import com.quickstamp.timerecorder.service.EtaForegroundService
import com.quickstamp.timerecorder.widget.TimeRecorderWidgetProvider

data class RecordResult(
    val event: RecorderEvent,
    val autoStop: RecorderEvent? = null,
)

object RecorderActions {
    fun record(
        context: Context,
        label: String,
        kind: EventKind = EventKind.DEFAULT,
        route: String? = null,
        terminal: Boolean = false,
        timestamp: Long = HkTime.now(),
        manual: Boolean = false,
    ): RecordResult {
        val app = context.applicationContext
        val state = AppStore.load(app)
        val commute = inferCommute(label, kind, state.tracking.mode, timestamp, state.settings.morningCutoffHour, state.events)

        val autoStop = if (kind == EventKind.BUS) ensureStopAtBoarding(app, state.events, commute, timestamp) else null
        val event = AppStore.record(
            app,
            label = label,
            kind = kind,
            timestamp = timestamp,
            route = route,
            commute = commute,
            terminal = terminal,
        )

        if (!manual) {
            when {
                label == "返工" -> runCatching { EtaForegroundService.start(app, CommuteMode.WORK) }
                label == "放工" -> runCatching { EtaForegroundService.start(app, CommuteMode.HOME) }
                kind == EventKind.BUS || terminal -> runCatching { EtaForegroundService.stop(app) }
            }
        }
        runCatching { TimeRecorderWidgetProvider.updateAll(app) }
        return RecordResult(event, autoStop)
    }

    private fun inferCommute(
        label: String,
        kind: EventKind,
        tracking: CommuteMode?,
        timestamp: Long,
        cutoff: Int,
        events: List<RecorderEvent>,
    ): CommuteMode? {
        if (label in setOf("返工", "到餐廳", "到公司")) return CommuteMode.WORK
        if (label in setOf("放工", "到屋企")) return CommuteMode.HOME
        if (label == "落車") {
            val latestBus = events
                .asSequence()
                .filter { it.kind == EventKind.BUS && it.timestamp <= timestamp && HkTime.date(it.timestamp) == HkTime.date(timestamp) }
                .maxByOrNull { it.timestamp }
            return latestBus?.commute ?: tracking ?: HkTime.modeAt(timestamp, cutoff)
        }
        if (kind == EventKind.BUS || kind == EventKind.STOP) return tracking ?: HkTime.modeAt(timestamp, cutoff)
        return null
    }

    private fun ensureStopAtBoarding(
        context: Context,
        events: List<RecorderEvent>,
        commute: CommuteMode?,
        timestamp: Long,
    ): RecorderEvent? {
        val mode = commute ?: HkTime.modeAt(timestamp)
        val sameDay = events.filter {
            HkTime.date(it.timestamp) == HkTime.date(timestamp) && it.timestamp <= timestamp
        }.sortedBy { it.timestamp }
        val startLabel = if (mode == CommuteMode.WORK) "返工" else "放工"
        val startAt = sameDay.lastOrNull { it.label == startLabel }?.timestamp ?: 0L
        val hasStop = sameDay.any {
            it.kind == EventKind.STOP &&
                (it.commute ?: HkTime.modeAt(it.timestamp)) == mode &&
                it.timestamp >= startAt
        }
        if (hasStop) return null
        return AppStore.record(
            context,
            label = "到巴士站",
            kind = EventKind.STOP,
            timestamp = timestamp,
            commute = mode,
            auto = true,
        )
    }
}
