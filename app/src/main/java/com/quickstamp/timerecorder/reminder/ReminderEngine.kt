package com.quickstamp.timerecorder.reminder

import android.content.Context
import com.quickstamp.timerecorder.data.EligibleDays
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.data.NoticeAnchor
import com.quickstamp.timerecorder.data.NoticeUnit
import com.quickstamp.timerecorder.data.PrepUnit
import com.quickstamp.timerecorder.data.ReminderNotice
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderRuleKind
import com.quickstamp.timerecorder.data.ReminderSource
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.data.ReminderTask
import com.quickstamp.timerecorder.data.ReminderTaskKind
import com.quickstamp.timerecorder.data.ReminderTaskStatus
import com.quickstamp.timerecorder.data.SystemCalendarReader
import com.quickstamp.timerecorder.model.HkTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** All dates, including day-offset notifications, use the actual Asia/Hong_Kong zone. */
object ReminderEngine {
    private data class SourceEvent(
        val id: String,
        val source: ReminderSource,
        val title: String,
        val sourceLabel: String,
        val date: LocalDate,
        val start: ZonedDateTime?,
        val end: ZonedDateTime,
        val rule: ReminderRule,
    )

    private data class TaskInstance(
        val source: SourceEvent,
        val key: String,
        val title: String,
        val kind: ReminderTaskKind,
        val due: ZonedDateTime,
        val expires: ZonedDateTime,
        val visibleFrom: ZonedDateTime,
        val notices: List<ReminderNotice>,
        val windowStart: LocalDate? = null,
        val windowEnd: LocalDate? = null,
        val eligibleDates: List<LocalDate> = emptyList(),
        val saturdayMorning: Boolean = false,
        val holidayDataMissing: Boolean = false,
    )

    data class NotificationOccurrence(
        val key: String,
        val taskKey: String,
        val title: String,
        val description: String,
        val at: Instant,
        val urgent: Boolean,
        val canComplete: Boolean,
        // An explicitly scheduled post-event notice is independent of task completion.
        val keepAfterCompletion: Boolean = false,
    )

    /** Read-only Rule simulation. The Rule and source event may be unsaved drafts. */
    data class SimulationTask(
        val title: String,
        val kind: ReminderTaskKind,
        val due: Instant,
        val expires: Instant,
        val windowStart: LocalDate?,
        val windowEnd: LocalDate?,
        val eligibleDates: List<LocalDate>,
        val saturdayMorningOnly: Boolean,
        val holidayDataMissing: Boolean,
    )

    data class SimulationResult(
        val tasks: List<SimulationTask>,
        val notifications: List<NotificationOccurrence>,
        val totalNotifications: Int,
        val warnings: List<String>,
    )

    fun simulateRule(
        context: Context, rule: ReminderRule, date: LocalDate,
        startTime: LocalTime?, endTime: LocalTime?,
    ): SimulationResult {
        val eventStart = startTime?.let { date.atTime(it).atZone(HkTime.zone) }
        // The sample end is only for this simulation; it does not create a private event.
        val eventEnd = if (eventStart == null) {
            date.plusDays(1).atStartOfDay(HkTime.zone)
        } else {
            val sameDayEnd = date.atTime(endTime ?: eventStart.toLocalTime().plusHours(1)).atZone(HkTime.zone)
            if (sameDayEnd.isAfter(eventStart)) sameDayEnd else sameDayEnd.plusDays(1)
        }
        val source = SourceEvent(
            id = "RULE_SIMULATION_ONLY", source = ReminderSource.PRIVATE,
            title = rule.name.ifBlank { "模擬事項" }, sourceLabel = "Simulated Event",
            date = date, start = eventStart, end = eventEnd, rule = rule,
        )
        val holidays = HolidayCalendarStore.load(context).holidays
        val instances = expandSource(source, holidays.mapTo(hashSetOf()) { it.date },
            holidays.mapTo(hashSetOf()) { it.date.year })
        // 1825-day offsets on a related window may compound. Cover all valid input bounds.
        val from = date.minusDays(5500).atStartOfDay(HkTime.zone).toInstant()
        val to = date.plusDays(5501).atStartOfDay(HkTime.zone).toInstant()
        val all = instances.flatMap { noticesFor(it, from, to) }
        // Match the live scheduler's deduplication: same task and instant -> one notification.
        val ordered = all.distinctBy { it.taskKey to it.at }.sortedBy { it.at }
        val warnings = mutableListOf<String>()
        if (!rule.enabled) warnings += "此 Rule 目前已停用；模擬仍會計算，但正式唔會排程。"
        instances.filter { it.kind == ReminderTaskKind.RELATED }.forEach { task ->
            if (task.eligibleDates.isEmpty()) warnings += "「${task.title}」喺指定 Range 內冇合資格日期。"
            if (task.holidayDataMissing) warnings += "「${task.title}」部分年份缺少官方 PH 資料，合資格日期只供參考。"
        }
        if (eventStart == null) {
            if (rule.preparationSteps.any { it.unit == PrepUnit.HOUR })
                warnings += "Full Day Event 冇開始時間，H-N 準備 Task 唔會生成。"
            val needsEventTime = rule.notices.any { it.unit == NoticeUnit.HOUR &&
                (it.anchor == NoticeAnchor.EVENT || it.anchor == NoticeAnchor.TASK) } ||
                rule.relatedTasks.any { task -> task.notices.any { it.unit == NoticeUnit.HOUR &&
                    (it.anchor == NoticeAnchor.EVENT || it.anchor == NoticeAnchor.TASK) } } ||
                rule.preparationSteps.any { step -> step.notices.any { it.unit == NoticeUnit.HOUR &&
                    it.anchor == NoticeAnchor.EVENT } }
            if (needsEventTime)
                warnings += "Full Day Event 冇 Event Start Time；以主事項時間倒數嘅 H-N 通知唔會觸發。"
        }
        if (all.size > ordered.size)
            warnings += "有 ${all.size - ordered.size} 個同 Task 同時間重疊嘅通知，正式只會發一次。"
        if (ordered.isEmpty()) warnings += "此模擬冇產生任何有效通知，請檢查 Rule 同 Event 時間。"
        if (ordered.size > 100) warnings += "共有 ${ordered.size} 個通知；可於 Schedule Preview 翻頁查看全部。"
        return SimulationResult(
            tasks = instances.map {
                SimulationTask(it.title, it.kind, it.due.toInstant(), it.expires.toInstant(),
                    it.windowStart, it.windowEnd, it.eligibleDates, it.saturdayMorning, it.holidayDataMissing)
            },
            notifications = ordered, totalNotifications = ordered.size,
            warnings = warnings.distinct(),
        )
    }

    fun tasksForDate(context: Context, date: LocalDate): List<ReminderTask> =
        tasksInRange(context, date, date.plusDays(1))

    fun tasksInRange(context: Context, from: LocalDate, toExclusive: LocalDate): List<ReminderTask> {
        if (!toExclusive.isAfter(from)) return emptyList()
        val instances = instances(context, from.minusDays(45), toExclusive.plusDays(2))
        val completed = ReminderStore.completedKeys(context)
        val out = mutableListOf<ReminderTask>()
        instances.filterNot { it.key in completed }.forEach { inst ->
            if (inst.windowStart == null) {
                val date = inst.due.toLocalDate()
                if (!date.isBefore(from) && date.isBefore(toExclusive)) out += task(inst, date)
            } else {
                inst.eligibleDates.filter { !it.isBefore(from) && it.isBefore(toExclusive) }
                    .forEach { date -> out += task(inst, date) }
                // If there is no eligible date, surface the problem at the start of the window.
                if (inst.eligibleDates.isEmpty() && !inst.windowStart.isBefore(from) && inst.windowStart.isBefore(toExclusive)) {
                    out += task(inst, inst.windowStart)
                }
            }
        }
        return out.distinctBy { it.taskKey to it.taskDate }
            .sortedWith(compareBy<ReminderTask> { rank(it.status) }.thenBy { it.taskDate }.thenBy { it.title })
    }

    /** Pinned across every app page, until the specific task expires or is completed. */
    fun persistentTasks(context: Context, now: ZonedDateTime = ZonedDateTime.now(HkTime.zone)): List<ReminderTask> {
        val completed = ReminderStore.completedKeys(context)
        return instances(context, now.toLocalDate().minusDays(90), now.toLocalDate().plusDays(120))
            .asSequence()
            .filter { it.key !in completed && !now.isBefore(it.visibleFrom) && now.isBefore(it.expires) }
            .map { inst ->
                val t = task(inst, now.toLocalDate())
                if (inst.saturdayMorning && now.dayOfWeek == DayOfWeek.SATURDAY && now.hour >= 12 &&
                    now.toLocalDate() in inst.eligibleDates) t.copy(status = ReminderTaskStatus.UPCOMING)
                else t
            }
            .distinctBy { it.taskKey }
            .sortedWith(compareBy<ReminderTask> { rank(it.status) }.thenBy { it.taskDate }.thenBy { it.title })
            .toList()
    }

    /** Schedule individual notification instants, not midnight day-boundary alarms. */
    fun notificationOccurrences(context: Context, from: Instant, to: Instant): List<NotificationOccurrence> {
        if (!to.isAfter(from)) return emptyList()
        val fromDay = from.atZone(HkTime.zone).toLocalDate()
        val toDay = to.atZone(HkTime.zone).toLocalDate()
        val completed = ReminderStore.completedKeys(context)
        return instances(context, fromDay.minusDays(2), toDay.plusDays(3), includeNoticeBounds = true)
            .asSequence()
            .flatMap { inst ->
                noticesFor(inst, from, to).asSequence().filter { notice ->
                    inst.key !in completed || notice.keepAfterCompletion
                }
            }
            .filter { !it.at.isBefore(from) && it.at.isBefore(to) }
            .distinctBy { it.taskKey to it.at }
            .sortedBy { it.at }
            .toList()
    }

    private fun noticesFor(inst: TaskInstance, from: Instant, to: Instant): List<NotificationOccurrence> {
        val out = mutableListOf<NotificationOccurrence>()
        val event = inst.source
        // Narrow repeated day ranges to the queried time interval before materialising dates.
        val firstQueryDay = from.atZone(HkTime.zone).toLocalDate()
        val lastQueryDay = to.minusNanos(1).atZone(HkTime.zone).toLocalDate()
        inst.notices.forEach { notice ->
            val baseDate = when (notice.anchor) {
                NoticeAnchor.EVENT -> event.date
                NoticeAnchor.TASK -> inst.due.toLocalDate()
                NoticeAnchor.WINDOW_START -> inst.windowStart
                NoticeAnchor.WINDOW_END -> inst.windowEnd
            }
            val moments: List<ZonedDateTime> = when (notice.unit) {
                NoticeUnit.DAY -> listOfNotNull(
                    baseDate?.minusDays(notice.amount.toLong())?.atTime(notice.hour, notice.minute)?.atZone(HkTime.zone)
                )
                NoticeUnit.DAY_RANGE -> {
                    if (baseDate == null || notice.rangeStartOffsetDays > notice.rangeEndOffsetDays) emptyList()
                    else {
                        val first = maxOf(baseDate.plusDays(notice.rangeStartOffsetDays.toLong()), firstQueryDay)
                        val last = minOf(baseDate.plusDays(notice.rangeEndOffsetDays.toLong()), lastQueryDay)
                        datesBetween(first, last).map { it.atTime(notice.hour, notice.minute).atZone(HkTime.zone) }
                    }
                }
                NoticeUnit.HOUR -> {
                    val base = when (notice.anchor) {
                        NoticeAnchor.EVENT -> event.start // NEVER interpret an all-day event as midnight.
                        NoticeAnchor.TASK -> if (inst.kind == ReminderTaskKind.PREPARATION) inst.due else event.start
                        else -> null
                    }
                    listOfNotNull(base?.minusHours(notice.amount.toLong()))
                }
                NoticeUnit.WINDOW_DAILY -> {
                    inst.eligibleDates.mapNotNull { date ->
                        if (inst.saturdayMorning && date.dayOfWeek == DayOfWeek.SATURDAY && notice.hour >= 12) null
                        else date.atTime(notice.hour, notice.minute).atZone(HkTime.zone)
                    }
                }
            }
            moments.forEach { at ->
                // Even on D-day, a user-chosen clock time is intentional: never silently drop it.
                // A related task cannot be completed outside its valid date window.
                val actionAllowed = when (inst.kind) {
                    ReminderTaskKind.RELATED -> at.toLocalDate() in inst.eligibleDates && at.isBefore(inst.expires)
                    else -> !at.isBefore(inst.due) && at.isBefore(inst.expires)
                }
                out += NotificationOccurrence(
                    key = "${inst.key}|${notice.id}|${at.toInstant().toEpochMilli()}",
                    taskKey = inst.key,
                    title = inst.title,
                    description = if (inst.kind == ReminderTaskKind.RELATED) {
                        "${event.title} · ${inst.windowStart} 至 ${inst.windowEnd} · ${eligibleLabel(inst)}"
                    } else if (inst.kind == ReminderTaskKind.PREPARATION) {
                        "${event.title} · 事前準備"
                    } else "${event.rule.name} · ${event.sourceLabel}",
                    at = at.toInstant(),
                    urgent = inst.kind == ReminderTaskKind.RELATED && at.toLocalDate() == inst.eligibleDates.lastOrNull(),
                    canComplete = actionAllowed,
                    keepAfterCompletion = inst.kind == ReminderTaskKind.EVENT &&
                        at.toLocalDate().isAfter(event.date),
                )
            }
        }
        return out
    }

    /** Relative date intervals used when looking backwards for D+N and forwards for D-N source events. */
    private data class DayOffsets(val earliest: Int = 0, val latest: Int = 0)

    private fun searchOffsets(rules: List<ReminderRule>, includeNotices: Boolean): DayOffsets {
        var earliest = 0
        var latest = 0
        fun include(a: Int, b: Int = a) {
            earliest = minOf(earliest, a)
            latest = maxOf(latest, b)
        }
        fun noticeOffsets(notice: ReminderNotice, anchorEarliest: Int, anchorLatest: Int) {
            when (notice.unit) {
                NoticeUnit.DAY -> include(anchorEarliest - notice.amount, anchorLatest - notice.amount)
                NoticeUnit.DAY_RANGE -> include(
                    anchorEarliest + notice.rangeStartOffsetDays,
                    anchorLatest + notice.rangeEndOffsetDays,
                )
                NoticeUnit.HOUR -> {
                    val days = (notice.amount + 23) / 24 + 1
                    include(anchorEarliest - days, anchorLatest)
                }
                NoticeUnit.WINDOW_DAILY -> include(anchorEarliest, anchorLatest)
            }
        }
        rules.forEach { rule ->
            val windowFirst = -maxOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore)
            val windowLast = -minOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore)
            val mainWindow = rule.kind == ReminderRuleKind.REQUIRED_WINDOW
            include(if (mainWindow) windowFirst else 0, if (mainWindow) windowLast else 0)
            include((if (mainWindow) windowFirst else 0) - rule.persistentDaysBefore)
            if (includeNotices) rule.notices.forEach { n ->
                when (n.anchor) {
                    NoticeAnchor.EVENT -> noticeOffsets(n, 0, 0)
                    NoticeAnchor.TASK -> noticeOffsets(n, if (mainWindow) windowFirst else 0, if (mainWindow) windowFirst else 0)
                    NoticeAnchor.WINDOW_START -> if (mainWindow) noticeOffsets(n, windowFirst, windowFirst)
                    NoticeAnchor.WINDOW_END -> if (mainWindow) noticeOffsets(n, windowLast, windowLast)
                }
                if (n.unit == NoticeUnit.WINDOW_DAILY && mainWindow) include(windowFirst, windowLast)
            }
            rule.relatedTasks.forEach { child ->
                val first = -maxOf(child.startDaysBefore, child.endDaysBefore)
                val last = -minOf(child.startDaysBefore, child.endDaysBefore)
                include(first - child.displayDaysBeforeWindow, last)
                if (includeNotices) child.notices.forEach { n ->
                    when (n.anchor) {
                        NoticeAnchor.EVENT -> noticeOffsets(n, 0, 0)
                        NoticeAnchor.TASK -> noticeOffsets(n, first, last) // first eligible day may vary with PH/weekday
                        NoticeAnchor.WINDOW_START -> noticeOffsets(n, first, first)
                        NoticeAnchor.WINDOW_END -> noticeOffsets(n, last, last)
                    }
                    if (n.unit == NoticeUnit.WINDOW_DAILY) include(first, last)
                }
            }
            rule.preparationSteps.forEach { prep ->
                val prepFirst = if (prep.unit == PrepUnit.DAY) -prep.amount else -(prep.amount + 23) / 24 - 1
                val prepLast = if (prep.unit == PrepUnit.DAY) -prep.amount else 0
                include(prepFirst - prep.displayDaysBefore, prepLast)
                if (includeNotices) prep.notices.forEach { n ->
                    when (n.anchor) {
                        NoticeAnchor.EVENT -> noticeOffsets(n, 0, 0)
                        NoticeAnchor.TASK -> noticeOffsets(n, prepFirst, prepLast)
                        else -> Unit
                    }
                }
            }
        }
        return DayOffsets(earliest, latest)
    }

    private fun instances(
        context: Context, sourceFrom: LocalDate, sourceTo: LocalDate,
        includeNoticeBounds: Boolean = false,
    ): List<TaskInstance> {
        val rules = ReminderStore.rules(context).filter { it.enabled }
        if (rules.isEmpty()) return emptyList()
        val byId = rules.associateBy { it.id }
        val offsets = searchOffsets(rules, includeNoticeBounds)
        // Task/notification time = event date + offset. Invert offsets to find source events.
        val calendarFrom = sourceFrom.minusDays(offsets.latest.toLong() + 2)
        val calendarTo = sourceTo.minusDays(offsets.earliest.toLong() - 2)
        val sources = mutableListOf<SourceEvent>()
        ReminderStore.privateReminders(context).filter { it.enabled }.forEach { item ->
            val rule = byId[item.ruleId] ?: return@forEach
            if (item.anchorDate.isBefore(calendarFrom) || !item.anchorDate.isBefore(calendarTo)) return@forEach
            val start = item.startTime?.let { item.anchorDate.atTime(it).atZone(HkTime.zone) }
            val end = if (start != null) {
                val endDate = item.endDate ?: item.anchorDate
                (item.endTime?.let { endDate.atTime(it).atZone(HkTime.zone) } ?: start).let {
                    if (it.isAfter(start)) it else start.plusMinutes(1)
                }
            } else item.anchorDate.plusDays(1).atStartOfDay(HkTime.zone)
            sources += SourceEvent(item.id, ReminderSource.PRIVATE, item.title, "Private", item.anchorDate, start, end, rule)
        }
        if (SystemCalendarReader.hasPermission(context)) {
            SystemCalendarReader.events(context, calendarFrom, calendarTo).forEach { e ->
                rules.filter { rule -> rule.keywords.any { e.title.contains(it, ignoreCase = true) } }
                    .forEach { rule ->
                        val start = e.startAt?.atZone(HkTime.zone)
                        val end = e.endAt?.atZone(HkTime.zone)
                            ?: if (start == null) e.date.plusDays(1).atStartOfDay(HkTime.zone) else start.plusMinutes(1)
                        sources += SourceEvent(e.key, ReminderSource.CALENDAR, e.title, e.calendarName.ifBlank { "Calendar" }, e.date, start, end, rule)
                    }
            }
        }
        val holidays = HolidayCalendarStore.load(context).holidays
        val holidayDates = holidays.mapTo(hashSetOf()) { it.date }
        val knownYears = holidays.mapTo(hashSetOf()) { it.date.year }
        return sources.flatMap { expandSource(it, holidayDates, knownYears) }
    }

    /** Production and simulation both expand a source using exactly the same rule logic. */
    private fun expandSource(
        e: SourceEvent, holidayDates: Set<LocalDate>, knownYears: Set<Int>,
    ): List<TaskInstance> {
            val key = "${e.source.name}|${e.id}|${e.date}|${e.rule.id}"
            val result = mutableListOf<TaskInstance>()
            val anchorDue = e.start ?: e.date.atStartOfDay(HkTime.zone)
            val mainWindow = e.rule.kind == ReminderRuleKind.REQUIRED_WINDOW
            val mainWindowStart = if (mainWindow) e.date.minusDays(maxOf(e.rule.windowStartDaysBefore, e.rule.windowEndDaysBefore).toLong()) else null
            val mainWindowEnd = if (mainWindow) e.date.minusDays(minOf(e.rule.windowStartDaysBefore, e.rule.windowEndDaysBefore).toLong()) else null
            val mainEligible = if (mainWindowStart != null && mainWindowEnd != null) datesBetween(mainWindowStart, mainWindowEnd) else emptyList()
            result += TaskInstance(
                source = e, key = key, title = e.title, kind = ReminderTaskKind.EVENT,
                due = if (mainWindowStart != null) mainWindowStart.atStartOfDay(HkTime.zone) else anchorDue,
                expires = if (mainWindowEnd != null) mainWindowEnd.plusDays(1).atStartOfDay(HkTime.zone) else e.end,
                visibleFrom = (mainWindowStart ?: e.date).minusDays(e.rule.persistentDaysBefore.toLong()).atStartOfDay(HkTime.zone),
                notices = e.rule.notices, windowStart = mainWindowStart, windowEnd = mainWindowEnd, eligibleDates = mainEligible,
            )
            e.rule.relatedTasks.forEach { related ->
                val start = e.date.minusDays(maxOf(related.startDaysBefore, related.endDaysBefore).toLong())
                val end = e.date.minusDays(minOf(related.startDaysBefore, related.endDaysBefore).toLong())
                val eligible = datesBetween(start, end).filter { date ->
                    val validWeekday = when (related.eligibleDays) {
                        EligibleDays.EVERY_DAY -> true
                        EligibleDays.MON_FRI -> date.dayOfWeek.value <= 5
                        EligibleDays.MON_SAT_MORNING -> date.dayOfWeek.value <= 6
                    }
                    validWeekday && (!related.excludePublicHolidays || date !in holidayDates)
                }
                val finalEligible = eligible.lastOrNull() ?: end
                val lastDayEndsAtNoon = related.eligibleDays == EligibleDays.MON_SAT_MORNING &&
                    finalEligible.dayOfWeek == DayOfWeek.SATURDAY
                result += TaskInstance(
                    source = e, key = "$key|RELATED|${related.id}", title = related.title,
                    kind = ReminderTaskKind.RELATED,
                    due = (eligible.firstOrNull() ?: start).atStartOfDay(HkTime.zone),
                    expires = if (lastDayEndsAtNoon) finalEligible.atTime(12, 0).atZone(HkTime.zone)
                        else finalEligible.plusDays(1).atStartOfDay(HkTime.zone),
                    visibleFrom = start.minusDays(related.displayDaysBeforeWindow.toLong()).atStartOfDay(HkTime.zone),
                    notices = related.notices, windowStart = start, windowEnd = end,
                    eligibleDates = eligible, saturdayMorning = related.eligibleDays == EligibleDays.MON_SAT_MORNING,
                    holidayDataMissing = related.excludePublicHolidays && datesBetween(start, end).any { it.year !in knownYears },
                )
            }
            e.rule.preparationSteps.forEach { prep ->
                val due = when (prep.unit) {
                    PrepUnit.DAY -> e.date.minusDays(prep.amount.toLong()).atTime(prep.hour, prep.minute).atZone(HkTime.zone)
                    PrepUnit.HOUR -> e.start?.minusHours(prep.amount.toLong())
                } ?: return@forEach // An H-N step requires a timed event.
                val expiry = e.start ?: e.end
                if (!due.isBefore(expiry)) return@forEach
                result += TaskInstance(
                    source = e, key = "$key|PREP|${prep.id}", title = prep.title,
                    kind = ReminderTaskKind.PREPARATION,
                    due = due, expires = expiry,
                    visibleFrom = due.toLocalDate().minusDays(prep.displayDaysBefore.toLong()).atStartOfDay(HkTime.zone),
                    notices = prep.notices,
                )
            }
            return result
    }

    private fun task(inst: TaskInstance, date: LocalDate): ReminderTask {
        val status = when {
            inst.windowStart != null && inst.eligibleDates.isEmpty() -> ReminderTaskStatus.OVERDUE
            inst.windowStart != null && date.isBefore(inst.windowStart) -> ReminderTaskStatus.UPCOMING
            inst.windowStart != null && date.isAfter(inst.eligibleDates.last()) -> ReminderTaskStatus.OVERDUE
            inst.windowStart != null && date == inst.eligibleDates.last() -> ReminderTaskStatus.LAST_DAY
            inst.windowStart != null -> ReminderTaskStatus.WINDOW_ACTIVE
            date.isAfter(inst.due.toLocalDate()) -> ReminderTaskStatus.OVERDUE
            date.isBefore(inst.due.toLocalDate()) -> ReminderTaskStatus.UPCOMING
            else -> ReminderTaskStatus.COUNTDOWN
        }
        return ReminderTask(
            taskKey = inst.key, title = inst.title, source = inst.source.source, sourceLabel = inst.source.sourceLabel,
            anchorDate = inst.source.date, ruleId = inst.source.rule.id, ruleName = inst.source.rule.name,
            notifyHour = inst.source.rule.notifyHour, notifyMinute = inst.source.rule.notifyMinute,
            status = status, daysUntilAnchor = ChronoUnit.DAYS.between(date, inst.source.date),
            windowStart = inst.windowStart, windowEnd = inst.windowEnd,
            taskDate = date, taskKind = inst.kind, eligibleDates = inst.eligibleDates,
            saturdayMorningOnly = inst.saturdayMorning, holidayDataMissing = inst.holidayDataMissing,
            canCompleteFrom = inst.due.toInstant(), taskExpiresAt = inst.expires.toInstant(),
        )
    }

    fun displayStatus(task: ReminderTask, date: LocalDate): String = when (task.status) {
        ReminderTaskStatus.COUNTDOWN -> "今日事項"
        ReminderTaskStatus.UPCOMING -> if (task.windowStart != null && !date.isBefore(task.windowStart))
            "今日已過合資格時段 · ${eligibleDatesSummary(task)}"
            else "${ChronoUnit.DAYS.between(date, task.windowStart ?: task.taskDate).coerceAtLeast(0)} 日後開始"
        ReminderTaskStatus.WINDOW_ACTIVE -> "可於有效期內完成一次 · ${eligibleDatesSummary(task)}"
        ReminderTaskStatus.LAST_DAY -> "最後合資格日期 · ${eligibleDatesSummary(task)}"
        ReminderTaskStatus.OVERDUE -> if (task.eligibleDates.isEmpty() && task.windowStart != null) "沒有合資格日期，請調整規則" else "已超過完成期限"
    }

    fun eligibleDatesSummary(task: ReminderTask): String {
        val valid = task.eligibleDates
        if (valid.isEmpty()) return "沒有合資格日期"
        return valid.joinToString("、") { date ->
            "${date.monthValue}/${date.dayOfMonth}${if (task.saturdayMorningOnly && date.dayOfWeek == DayOfWeek.SATURDAY) "上午" else ""}"
        } + if (task.holidayDataMissing) " · ⚠ 部分官方假期資料未齊" else ""
    }

    private fun eligibleLabel(inst: TaskInstance) = when {
        inst.saturdayMorning -> "週一至五／週六上午"
        else -> "合資格日期"
    }

    private fun datesBetween(start: LocalDate, end: LocalDate): List<LocalDate> {
        if (end.isBefore(start)) return emptyList()
        return (0..ChronoUnit.DAYS.between(start, end).coerceAtMost(3660).toInt()).map { start.plusDays(it.toLong()) }
    }

    private fun rank(status: ReminderTaskStatus): Int = when (status) {
        ReminderTaskStatus.OVERDUE -> 0
        ReminderTaskStatus.LAST_DAY -> 1
        ReminderTaskStatus.WINDOW_ACTIVE -> 2
        ReminderTaskStatus.COUNTDOWN -> 3
        ReminderTaskStatus.UPCOMING -> 4
    }
}
