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
 * EXTRA events never affect the state machine.
 */
object WorkflowPlanner {
    fun next(events: List<RecorderEvent>, date: LocalDate, now: Long = HkTime.now()): NextAction {
        val day = events
            .filter { HkTime.date(it.timestamp) == date && it.kind != EventKind.EXTRA }
            .sortedBy { it.timestamp }
        val last = day.lastOrNull()

        if (last == null) {
            return if (HkTime.modeAt(now) == CommuteMode.WORK) {
                NextAction.Single(WorkflowAction("返工"))
            } else {
                NextAction.Single(WorkflowAction("放工"))
            }
        }
        if (last.terminal) return NextAction.Done

        val lastCommute = last.commute ?: day.asReversed().firstNotNullOfOrNull { it.commute }
            ?: HkTime.modeAt(last.timestamp)

        return when {
            last.label == "返工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.label == "放工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.kind == EventKind.STOP -> NextAction.BusChoices
            last.kind == EventKind.BUS -> NextAction.Single(WorkflowAction("落車"))
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
