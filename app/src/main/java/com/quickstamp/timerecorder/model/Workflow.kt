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

object WorkflowPlanner {
    fun next(events: List<RecorderEvent>, date: LocalDate, now: Long = HkTime.now()): NextAction {
        val day = events.filter { HkTime.date(it.timestamp) == date }.sortedBy { it.timestamp }
        val meaningful = day.filter { it.kind != EventKind.EXTRA }
        val last = meaningful.lastOrNull()
        if (last == null) {
            return if (HkTime.modeAt(now) == CommuteMode.WORK) {
                NextAction.Single(WorkflowAction("返工"))
            } else {
                NextAction.Single(WorkflowAction("放工"))
            }
        }
        if (last.terminal) return NextAction.Done
        return when {
            last.label == "返工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.kind == EventKind.STOP -> NextAction.BusChoices
            last.kind == EventKind.BUS && (last.commute ?: HkTime.modeAt(last.timestamp)) == CommuteMode.WORK ->
                NextAction.Single(WorkflowAction("到餐廳"))
            last.label == "到餐廳" -> NextAction.Single(WorkflowAction("到公司"))
            last.label == "到公司" -> NextAction.Single(WorkflowAction("放工"))
            last.label == "放工" -> NextAction.Single(WorkflowAction("到巴士站", EventKind.STOP))
            last.kind == EventKind.BUS && (last.commute ?: HkTime.modeAt(last.timestamp)) == CommuteMode.HOME ->
                NextAction.Single(WorkflowAction("落車"))
            last.label == "落車" -> NextAction.Single(WorkflowAction("到屋企", terminal = true))
            else -> if (HkTime.modeAt(now) == CommuteMode.WORK) {
                NextAction.Single(WorkflowAction("到公司"))
            } else {
                NextAction.Single(WorkflowAction("到屋企", terminal = true))
            }
        }
    }
}
