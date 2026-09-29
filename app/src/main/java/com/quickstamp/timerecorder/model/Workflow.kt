package com.quickstamp.timerecorder.model

import java.time.LocalDate

data class WorkflowAction(
    val label: String,
    val kind: EventKind = EventKind.DEFAULT,
    val route: String? = null,
    val terminal: Boolean = false,
)

sealed interface NextAction {
    data class Single(val action: WorkflowAction) : NextAction
    data object BusChoices : NextAction
    data object Done : NextAction
}

/**
 * Smart Next is intentionally deterministic. It follows the user's commute workflow and
 * never tries to skip a physical step based on the clock.
 *
 * Work: 返工 → 到巴士站 → 上車 → 落車 → 到餐廳 → 到公司 → 放工
 * Home: 放工 → 到巴士站 → 上車 → 落車 → 到屋企
 *
 * EXTRA events open free choices when not riding; while riding, 落車 always wins.
 */
object WorkflowPlanner {
    fun next(events: List<RecorderEvent>, date: LocalDate, now: Long = HkTime.now()): NextAction {
        val allDay = events
            .filter { HkTime.date(it.timestamp) == date }
            .sortedBy { it.timestamp }

        if (allDay.isEmpty()) {
            return if (HkTime.modeAt(now) == CommuteMode.WORK) {
                NextAction.Single(WorkflowAction("返工"))
            } else {
                NextAction.Single(WorkflowAction("放工"))
            }
        }
        if (allDay.any { it.terminal }) return NextAction.Done

        // Riding always wins: even an EXTRA event must never replace the required alight action.
        val lastBus = allDay.lastOrNull { it.kind == EventKind.BUS }
        val lastAlight = allDay.lastOrNull { it.label == "落車" }
        if (lastBus != null && (lastAlight == null || lastBus.timestamp > lastAlight.timestamp)) {
            return NextAction.Single(WorkflowAction("落車"))
        }

        // EXTRA is deliberately free-form. Once it is recorded and we are not riding,
        // the next action may be another EXTRA or any boarding choice.
        if (allDay.last().kind == EventKind.EXTRA) return NextAction.BusChoices

        val day = allDay.filter { it.kind != EventKind.EXTRA }
        val last = day.lastOrNull() ?: return NextAction.BusChoices
        val lastCommute = last.commute ?: day.asReversed().firstNotNullOfOrNull { it.commute }
            ?: HkTime.modeAt(last.timestamp)

        return when {
            last.label == "返工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.label == "放工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.kind == EventKind.STOP -> NextAction.BusChoices
            last.label == "落車" && lastCommute == CommuteMode.WORK -> NextAction.Single(WorkflowAction("到餐廳"))
            last.label == "落車" && lastCommute == CommuteMode.HOME -> NextAction.Single(WorkflowAction("到屋企", terminal = true))
            last.label == "到餐廳" -> NextAction.Single(WorkflowAction("到公司"))
            last.label == "到公司" -> NextAction.Single(WorkflowAction("放工"))
            else -> if (lastCommute == CommuteMode.WORK) {
                NextAction.Single(WorkflowAction("到公司"))
            } else {
                NextAction.Single(WorkflowAction("到屋企", terminal = true))
            }
        }
    }
}
