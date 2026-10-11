package com.quickstamp.timerecorder.reminder

import android.content.Context
import com.quickstamp.timerecorder.data.PrivateReminder
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderRuleKind
import com.quickstamp.timerecorder.data.ReminderSource
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.data.ReminderTask
import com.quickstamp.timerecorder.data.ReminderTaskStatus
import com.quickstamp.timerecorder.data.SystemCalendarEvent
import com.quickstamp.timerecorder.data.SystemCalendarReader
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object ReminderEngine {
    fun tasksForDate(context: Context, date: LocalDate): List<ReminderTask> =
        tasksInRange(context, date, date.plusDays(1)).filter { taskDate(it) == date }

    fun tasksInRange(context: Context, from: LocalDate, toExclusive: LocalDate): List<ReminderTask> {
        if (!toExclusive.isAfter(from)) return emptyList()
        val rules = ReminderStore.rules(context).filter { it.enabled }
        if (rules.isEmpty()) return emptyList()
        val ruleById = rules.associateBy { it.id }
        val maxLead = rules.maxOfOrNull { rule ->
            when (rule.kind) {
                ReminderRuleKind.COUNTDOWN -> rule.countdownDaysBefore.maxOrNull() ?: 0
                ReminderRuleKind.REQUIRED_WINDOW -> maxOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore)
            }
        }?.coerceAtLeast(0) ?: 0
        val maxTail = 31L
        val sourceFrom = from.minusDays(maxTail)
        val sourceTo = toExclusive.plusDays(maxLead.toLong() + 2L)

        val out = mutableListOf<ReminderTask>()
        ReminderStore.privateReminders(context).filter { it.enabled }.forEach { privateItem ->
            val rule = ruleById[privateItem.ruleId] ?: return@forEach
            out += buildTasksForSource(
                source = ReminderSource.PRIVATE,
                sourceId = privateItem.id,
                title = privateItem.title,
                sourceLabel = "Private",
                anchor = privateItem.anchorDate,
                rule = rule,
                from = from,
                toExclusive = toExclusive,
            )
        }

        if (SystemCalendarReader.hasPermission(context)) {
            val calendarEvents = SystemCalendarReader.events(context, sourceFrom, sourceTo)
            calendarEvents.forEach { event ->
                rules.filter { matchesKeyword(event, it) }.forEach { rule ->
                    out += buildTasksForSource(
                        source = ReminderSource.CALENDAR,
                        sourceId = event.key,
                        title = event.title,
                        sourceLabel = event.calendarName.ifBlank { "Calendar" },
                        anchor = event.date,
                        rule = rule,
                        from = from,
                        toExclusive = toExclusive,
                    )
                }
            }
        }

        val completed = ReminderStore.completedKeys(context)
        return out
            .filterNot { it.taskKey in completed }
            .distinctBy { it.taskKey to taskDate(it) }
            .sortedWith(compareBy<ReminderTask> { statusRank(it.status) }.thenBy { it.anchorDate }.thenBy { it.title })
    }

    private fun matchesKeyword(event: SystemCalendarEvent, rule: ReminderRule): Boolean {
        if (rule.keywords.isEmpty()) return false
        return rule.keywords.any { keyword -> event.title.contains(keyword, ignoreCase = true) }
    }

    private fun buildTasksForSource(
        source: ReminderSource,
        sourceId: String,
        title: String,
        sourceLabel: String,
        anchor: LocalDate,
        rule: ReminderRule,
        from: LocalDate,
        toExclusive: LocalDate,
    ): List<ReminderTask> {
        val taskKey = "${source.name}|$sourceId|$anchor|${rule.id}"
        val out = mutableListOf<ReminderTask>()
        when (rule.kind) {
            ReminderRuleKind.COUNTDOWN -> {
                rule.countdownDaysBefore.distinct().forEach { before ->
                    val dueDate = anchor.minusDays(before.toLong())
                    if (!dueDate.isBefore(from) && dueDate.isBefore(toExclusive)) {
                        out += ReminderTask(
                            taskKey = taskKey,
                            title = title,
                            source = source,
                            sourceLabel = sourceLabel,
                            anchorDate = anchor,
                            ruleId = rule.id,
                            ruleName = rule.name,
                            notifyHour = rule.notifyHour,
                            notifyMinute = rule.notifyMinute,
                            status = ReminderTaskStatus.COUNTDOWN,
                            daysUntilAnchor = ChronoUnit.DAYS.between(dueDate, anchor),
                        )
                    }
                }
            }
            ReminderRuleKind.REQUIRED_WINDOW -> {
                val startBefore = maxOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore).coerceAtLeast(0)
                val endBefore = minOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore).coerceAtLeast(0)
                val windowStart = anchor.minusDays(startBefore.toLong())
                val windowEnd = anchor.minusDays(endBefore.toLong())
                var date = maxOfDate(from, windowStart)
                val stop = minOfDate(toExclusive.minusDays(1), anchor)
                while (!date.isAfter(stop)) {
                    val status = when {
                        date.isBefore(windowStart) -> null
                        !date.isAfter(windowEnd) -> if (date == windowEnd) ReminderTaskStatus.LAST_DAY else ReminderTaskStatus.WINDOW_ACTIVE
                        else -> ReminderTaskStatus.OVERDUE
                    }
                    if (status != null) {
                        out += ReminderTask(
                            taskKey = taskKey,
                            title = title,
                            source = source,
                            sourceLabel = sourceLabel,
                            anchorDate = anchor,
                            ruleId = rule.id,
                            ruleName = rule.name,
                            notifyHour = rule.notifyHour,
                            notifyMinute = rule.notifyMinute,
                            status = status,
                            daysUntilAnchor = ChronoUnit.DAYS.between(date, anchor),
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                        )
                    }
                    date = date.plusDays(1)
                }
            }
        }
        return out
    }

    fun displayStatus(task: ReminderTask, date: LocalDate): String = when (task.status) {
        ReminderTaskStatus.COUNTDOWN -> when (task.daysUntilAnchor) {
            0L -> "今日"
            1L -> "明日到期"
            else -> "${task.daysUntilAnchor} 日後"
        }
        ReminderTaskStatus.WINDOW_ACTIVE -> {
            val end = task.windowEnd ?: return "有效期內"
            val left = ChronoUnit.DAYS.between(date, end)
            "必做期間 · 剩餘 ${left + 1} 日"
        }
        ReminderTaskStatus.LAST_DAY -> "最後一日"
        ReminderTaskStatus.OVERDUE -> "已錯過有效期限"
    }

    private fun taskDate(task: ReminderTask): LocalDate = when (task.status) {
        ReminderTaskStatus.COUNTDOWN -> task.anchorDate.minusDays(task.daysUntilAnchor)
        else -> task.anchorDate.minusDays(task.daysUntilAnchor)
    }

    private fun statusRank(status: ReminderTaskStatus): Int = when (status) {
        ReminderTaskStatus.OVERDUE -> 0
        ReminderTaskStatus.LAST_DAY -> 1
        ReminderTaskStatus.WINDOW_ACTIVE -> 2
        ReminderTaskStatus.COUNTDOWN -> 3
    }

    private fun maxOfDate(a: LocalDate, b: LocalDate) = if (a.isAfter(b)) a else b
    private fun minOfDate(a: LocalDate, b: LocalDate) = if (a.isBefore(b)) a else b
}
