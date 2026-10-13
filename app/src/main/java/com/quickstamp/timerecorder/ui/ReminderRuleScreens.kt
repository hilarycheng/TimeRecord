package com.quickstamp.timerecorder.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.EligibleDays
import com.quickstamp.timerecorder.data.NoticeAnchor
import com.quickstamp.timerecorder.data.NoticeUnit
import com.quickstamp.timerecorder.data.PrepUnit
import com.quickstamp.timerecorder.data.PreparationStep
import com.quickstamp.timerecorder.data.RelatedTaskTemplate
import com.quickstamp.timerecorder.data.ReminderNotice
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderRuleKind
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

// Each editor is a real page, not an AlertDialog. Form states live above the
// navigation switch so opening a child editor never loses unsaved parent edits.
private enum class RuleScreen { LIST, RULE, RELATED, PREPARATION, NOTICE, SIMULATION }
private enum class NoticeOwner { RULE, RELATED, PREPARATION }

private class RuleDraft(initial: ReminderRule?) {
    val existing = initial != null
    val base = initial ?: ReminderRule(name = "")
    var name by mutableStateOf(base.name)
    var keywords by mutableStateOf(base.keywords.joinToString(", "))
    var legacyWindow by mutableStateOf(base.kind == ReminderRuleKind.REQUIRED_WINDOW)
    var windowStart by mutableStateOf(base.windowStartDaysBefore.toString())
    var windowEnd by mutableStateOf(base.windowEndDaysBefore.toString())
    var persistentBefore by mutableStateOf(base.persistentDaysBefore.toString())
    var notices by mutableStateOf(base.notices)
    var related by mutableStateOf(base.relatedTasks)
    var steps by mutableStateOf(base.preparationSteps)
    var enabled by mutableStateOf(base.enabled)

    val valid: Boolean
        get() = name.isNotBlank() && validBoundedNumber(persistentBefore, 365) &&
            (!legacyWindow || validRange(windowStart, windowEnd))

    fun build(): ReminderRule = base.copy(
        name = name.trim(),
        keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
        kind = if (legacyWindow) ReminderRuleKind.REQUIRED_WINDOW
            else if (related.isNotEmpty()) ReminderRuleKind.COMBINED else ReminderRuleKind.COUNTDOWN,
        windowStartDaysBefore = windowStart.toIntOrNull()?.coerceIn(0, 1825) ?: base.windowStartDaysBefore,
        windowEndDaysBefore = windowEnd.toIntOrNull()?.coerceIn(0, 1825) ?: base.windowEndDaysBefore,
        persistentDaysBefore = persistentBefore.toIntOrNull()?.coerceIn(0, 365) ?: 0,
        notices = notices, relatedTasks = related, preparationSteps = steps, enabled = enabled,
    )
}

private class RelatedDraft(initial: RelatedTaskTemplate?) {
    val base = initial ?: RelatedTaskTemplate(
        title = "",
        notices = listOf(
            ReminderNotice(unit = NoticeUnit.DAY, amount = 28, anchor = NoticeAnchor.EVENT),
            ReminderNotice(unit = NoticeUnit.WINDOW_DAILY),
        ),
    )
    var title by mutableStateOf(base.title)
    var start by mutableStateOf(base.startDaysBefore.toString())
    var end by mutableStateOf(base.endDaysBefore.toString())
    var policy by mutableStateOf(base.eligibleDays)
    var excludePH by mutableStateOf(base.excludePublicHolidays)
    var lead by mutableStateOf(base.displayDaysBeforeWindow.toString())
    var notices by mutableStateOf(base.notices)

    val valid: Boolean
        get() = title.isNotBlank() && validRange(start, end) && validBoundedNumber(lead, 365)

    fun build(): RelatedTaskTemplate = base.copy(
        title = title.trim(),
        startDaysBefore = start.toInt(), endDaysBefore = end.toInt(),
        eligibleDays = policy, excludePublicHolidays = excludePH,
        displayDaysBeforeWindow = lead.toInt(), notices = notices,
    )
}

private class PreparationDraft(initial: PreparationStep?) {
    val isNew = initial == null
    val base = initial ?: PreparationStep(
        title = "", notices = listOf(ReminderNotice(unit = NoticeUnit.DAY, anchor = NoticeAnchor.TASK)),
    )
    var title by mutableStateOf(base.title)
    var unit by mutableStateOf(base.unit)
    var amount by mutableStateOf(base.amount.toString())
    var hour by mutableStateOf(base.hour)
    var minute by mutableStateOf(base.minute)
    var lead by mutableStateOf(base.displayDaysBefore.toString())
    var notices by mutableStateOf(base.notices)

    val valid: Boolean
        get() = title.isNotBlank() && validBoundedNumber(amount, 1825) && validBoundedNumber(lead, 365)

    fun build(): PreparationStep {
        val fixedNotices = if (isNew && unit == PrepUnit.HOUR && notices.size == 1 &&
            notices[0].unit == NoticeUnit.DAY && notices[0].amount == 0 &&
            notices[0].anchor == NoticeAnchor.TASK) {
            listOf(ReminderNotice(unit = NoticeUnit.HOUR, anchor = NoticeAnchor.TASK, amount = 0))
        } else notices
        return base.copy(
            title = title.trim(), unit = unit, amount = amount.toInt(),
            hour = hour, minute = minute, displayDaysBefore = lead.toInt(), notices = fixedNotices,
        )
    }
}

/** Signed day offsets: D-2 = -2; D-Day = 0; D+2 = +2. */
private enum class DayPosition { BEFORE, ON, AFTER }

private fun dayPosition(offset: Int): DayPosition = when {
    offset < 0 -> DayPosition.BEFORE
    offset > 0 -> DayPosition.AFTER
    else -> DayPosition.ON
}

private fun dayOffset(position: DayPosition, amount: String): Int = when (position) {
    DayPosition.BEFORE -> -(amount.toIntOrNull() ?: 0)
    DayPosition.ON -> 0
    DayPosition.AFTER -> amount.toIntOrNull() ?: 0
}

private fun validDayOffset(position: DayPosition, amount: String): Boolean =
    position == DayPosition.ON || amount.toIntOrNull()?.let { it in 1..1825 } == true

private class NoticeDraft(initial: ReminderNotice?, defaultAnchor: NoticeAnchor) {
    val base = initial ?: ReminderNotice(anchor = defaultAnchor)
    var unit by mutableStateOf(base.unit)
    var amount by mutableStateOf(kotlin.math.abs(base.amount).toString())
    var position by mutableStateOf(dayPosition(-base.amount))
    var anchor by mutableStateOf(base.anchor)
    var hour by mutableStateOf(base.hour)
    var minute by mutableStateOf(base.minute)
    var rangeStartPosition by mutableStateOf(dayPosition(base.rangeStartOffsetDays))
    var rangeStartAmount by mutableStateOf(kotlin.math.abs(base.rangeStartOffsetDays).toString())
    var rangeEndPosition by mutableStateOf(dayPosition(base.rangeEndOffsetDays))
    var rangeEndAmount by mutableStateOf(kotlin.math.abs(base.rangeEndOffsetDays).toString())

    val valid: Boolean
        get() = when (unit) {
            NoticeUnit.DAY -> validDayOffset(position, amount)
            NoticeUnit.HOUR -> validBoundedNumber(amount, 1825)
            NoticeUnit.DAY_RANGE -> validDayOffset(rangeStartPosition, rangeStartAmount) &&
                validDayOffset(rangeEndPosition, rangeEndAmount) &&
                dayOffset(rangeStartPosition, rangeStartAmount) <= dayOffset(rangeEndPosition, rangeEndAmount)
            NoticeUnit.WINDOW_DAILY -> true
        }

    fun build(): ReminderNotice = base.copy(
        unit = unit,
        amount = when (unit) {
            NoticeUnit.DAY -> -dayOffset(position, amount)
            NoticeUnit.HOUR -> amount.toInt()
            else -> base.amount
        },
        rangeStartOffsetDays = dayOffset(rangeStartPosition, rangeStartAmount),
        rangeEndOffsetDays = dayOffset(rangeEndPosition, rangeEndAmount),
        hour = hour, minute = minute,
        anchor = if (unit == NoticeUnit.WINDOW_DAILY) NoticeAnchor.TASK else anchor,
    )
}

private fun validBoundedNumber(text: String, max: Int): Boolean =
    text.toIntOrNull()?.let { it in 0..max } == true

private fun validRange(start: String, end: String): Boolean =
    validBoundedNumber(start, 1825) && validBoundedNumber(end, 1825) && start.toInt() >= end.toInt()

private fun <T> replaceById(
    items: List<T>, item: T, idOf: (T) -> String,
): List<T> = if (items.any { idOf(it) == idOf(item) }) {
    items.map { if (idOf(it) == idOf(item)) item else it }
} else items + item

@Composable
internal fun ReminderRulesPage(context: Context, onBack: () -> Unit, onChanged: () -> Unit) {
    var rules by remember { mutableStateOf(ReminderStore.rules(context)) }
    var screen by remember { mutableStateOf(RuleScreen.LIST) }
    var ruleDraft by remember { mutableStateOf<RuleDraft?>(null) }
    var relatedDraft by remember { mutableStateOf<RelatedDraft?>(null) }
    var preparationDraft by remember { mutableStateOf<PreparationDraft?>(null) }
    var noticeDraft by remember { mutableStateOf<NoticeDraft?>(null) }
    var simulationDraft by remember { mutableStateOf<RuleSimulationDraft?>(null) }
    var noticeOwner by remember { mutableStateOf(NoticeOwner.RULE) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Scroll positions belong to the navigation host, not a child page.
    // Editing a notification and returning should keep the Rule at the same section.
    val listScroll = rememberScrollState()
    val ruleScroll = rememberScrollState()
    val relatedScroll = rememberScrollState()
    val preparationScroll = rememberScrollState()
    val noticeScroll = rememberScrollState()
    val simulationScroll = rememberScrollState()

    fun reload() {
        rules = ReminderStore.rules(context)
        scope.launch(Dispatchers.IO) { ReminderScheduler.scheduleAll(context.applicationContext) }
        onChanged()
    }

    fun back() {
        screen = when (screen) {
            RuleScreen.LIST -> { onBack(); RuleScreen.LIST }
            RuleScreen.RULE -> RuleScreen.LIST
            RuleScreen.RELATED, RuleScreen.PREPARATION, RuleScreen.SIMULATION -> RuleScreen.RULE
            RuleScreen.NOTICE -> when (noticeOwner) {
                NoticeOwner.RULE -> RuleScreen.RULE
                NoticeOwner.RELATED -> RuleScreen.RELATED
                NoticeOwner.PREPARATION -> RuleScreen.PREPARATION
            }
        }
    }

    fun editNotice(owner: NoticeOwner, existing: ReminderNotice?) {
        scope.launch { noticeScroll.scrollTo(0) }
        noticeOwner = owner
        noticeDraft = NoticeDraft(existing, if (owner == NoticeOwner.RULE) NoticeAnchor.EVENT else NoticeAnchor.TASK)
        screen = RuleScreen.NOTICE
    }

    BackHandler {
        if (deleteConfirmation) deleteConfirmation = false else back()
    }

    when (screen) {
        RuleScreen.LIST -> RuleListScreen(
            rules = rules, scrollState = listScroll, onBack = onBack,
            onAdd = { scope.launch { ruleScroll.scrollTo(0) }; ruleDraft = RuleDraft(null); screen = RuleScreen.RULE },
            onEdit = { scope.launch { ruleScroll.scrollTo(0) }; ruleDraft = RuleDraft(it); screen = RuleScreen.RULE },
        )
        RuleScreen.RULE -> ruleDraft?.let { draft ->
            RuleFormScreen(
                draft = draft, scrollState = ruleScroll, onBack = { back() },
                onSave = {
                    if (draft.valid) {
                        ReminderStore.upsertRule(context, draft.build())
                        screen = RuleScreen.LIST
                        reload()
                    }
                },
                onDelete = if (draft.existing) ({ deleteConfirmation = true }) else null,
                onSimulate = {
                    if (draft.valid) {
                        scope.launch { simulationScroll.scrollTo(0) }
                        simulationDraft = RuleSimulationDraft(draft.build())
                        screen = RuleScreen.SIMULATION
                    }
                },
                onAddRelated = { initial ->
                    scope.launch { relatedScroll.scrollTo(0) }
                    relatedDraft = RelatedDraft(initial)
                    screen = RuleScreen.RELATED
                },
                onAddPreparation = { initial ->
                    scope.launch { preparationScroll.scrollTo(0) }
                    preparationDraft = PreparationDraft(initial)
                    screen = RuleScreen.PREPARATION
                },
                onAddNotice = { initial -> editNotice(NoticeOwner.RULE, initial) },
            )
        }
        RuleScreen.RELATED -> relatedDraft?.let { draft ->
            RelatedFormScreen(
                draft = draft, scrollState = relatedScroll, onBack = { back() },
                onSave = {
                    if (draft.valid) {
                        val saved = draft.build()
                        ruleDraft?.let { it.related = replaceById(it.related, saved) { task -> task.id } }
                        screen = RuleScreen.RULE
                    }
                },
                onAddNotice = { initial -> editNotice(NoticeOwner.RELATED, initial) },
            )
        }
        RuleScreen.PREPARATION -> preparationDraft?.let { draft ->
            PreparationFormScreen(
                context = context, draft = draft, scrollState = preparationScroll, onBack = { back() },
                onSave = {
                    if (draft.valid) {
                        val saved = draft.build()
                        ruleDraft?.let { it.steps = replaceById(it.steps, saved) { task -> task.id } }
                        screen = RuleScreen.RULE
                    }
                },
                onAddNotice = { initial -> editNotice(NoticeOwner.PREPARATION, initial) },
            )
        }
        RuleScreen.SIMULATION -> simulationDraft?.let { draft ->
            RuleSimulationScreen(context, draft, simulationScroll, onBack = { back() })
        }
        RuleScreen.NOTICE -> noticeDraft?.let { draft ->
            val allowWindow = when (noticeOwner) {
                NoticeOwner.RULE -> ruleDraft?.legacyWindow == true
                NoticeOwner.RELATED -> true
                NoticeOwner.PREPARATION -> false
            }
            NoticeFormScreen(
                context = context, draft = draft, scrollState = noticeScroll, allowWindow = allowWindow,
                onBack = { back() },
                onSave = {
                    if (draft.valid) {
                        val saved = draft.build()
                        when (noticeOwner) {
                            NoticeOwner.RULE -> ruleDraft?.let { it.notices = replaceById(it.notices, saved) { n -> n.id } }
                            NoticeOwner.RELATED -> relatedDraft?.let { it.notices = replaceById(it.notices, saved) { n -> n.id } }
                            NoticeOwner.PREPARATION -> preparationDraft?.let { it.notices = replaceById(it.notices, saved) { n -> n.id } }
                        }
                        back()
                    }
                },
            )
        }
    }

    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("刪除 Reminder Rule？") },
            text = { Text("刪除此 Rule 會一併刪除連結嘅私人事項。呢個操作不能 Undo。") },
            confirmButton = {
                TextButton(onClick = {
                    ruleDraft?.base?.id?.let { ReminderStore.deleteRule(context, it) }
                    deleteConfirmation = false
                    screen = RuleScreen.LIST
                    reload()
                }) { Text("確定刪除", color = WebDanger) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("取消") } },
            containerColor = WebCard2,
        )
    }
}

@Composable
private fun RuleListScreen(
    rules: List<ReminderRule>, scrollState: ScrollState, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (ReminderRule) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(WebBg).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = WebAccent, fontWeight = FontWeight.Bold) }
            Text("Reminder Rules", color = WebInk, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = WebAccent)) { Text("+ Rule") }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            EditorSection("自訂提醒規則", "一條 Rule 可以有多個 Notification、Related Task 同事前準備步驟。") {
                Text("D-N 只定日期，通知時間由你設定。Rule 編輯會用獨立全屏頁面。", color = WebMuted, fontSize = 12.sp)
                Text("私人事項只儲喺 App；System Calendar 保持只讀。", color = WebBus, fontSize = 11.sp)
            }
            if (rules.isEmpty()) Text("尚未建立 Rule，按右上角 + Rule 開始。", color = WebMuted)
            rules.forEach { rule ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onEdit(rule) },
                    shape = RoundedCornerShape(16.dp), color = WebCard2,
                    border = BorderStroke(1.dp, if (rule.enabled) WebAccent.copy(alpha = .45f) else WebLine),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(rule.name, modifier = Modifier.weight(1f), color = WebInk, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            Text(if (rule.enabled) "ON" else "OFF", color = if (rule.enabled) WebGood else WebMuted, fontSize = 11.sp)
                        }
                        if (rule.keywords.isNotEmpty()) Text("Keyword: ${rule.keywords.joinToString(" / ")}", color = WebAccent2, fontSize = 11.sp)
                        Text(ruleSummary(rule), color = WebMuted, fontSize = 12.sp)
                        Text("通知 ${rule.notices.size} · Related ${rule.relatedTasks.size} · 準備 ${rule.preparationSteps.size}", color = WebWarm, fontSize = 12.sp)
                        Text("點擊編輯 ›", color = WebAccent2, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenRuleForm(
    title: String, subtitle: String, scrollState: ScrollState, onBack: () -> Unit,
    saveEnabled: Boolean, onSave: () -> Unit,
    saveLabel: String = "儲存", content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(WebBg).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = WebAccent, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f)) {
                Text(title, color = WebInk, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text(subtitle, color = WebMuted, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = WebLine)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content,
        )
        HorizontalDivider(color = WebLine)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = onSave, enabled = saveEnabled, modifier = Modifier.weight(2f),
                colors = ButtonDefaults.buttonColors(containerColor = WebAccent),
            ) { Text(saveLabel, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun EditorSection(
    title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = WebCard, border = BorderStroke(1.dp, WebLine),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = WebInk, fontSize = 15.sp, fontWeight = FontWeight.Black)
            if (subtitle != null) Text(subtitle, color = WebMuted, fontSize = 11.sp)
            content()
        }
    }
}

@Composable
private fun NotificationsList(
    notices: List<ReminderNotice>,
    onEdit: (ReminderNotice?) -> Unit,
    onRemove: (ReminderNotice) -> Unit,
) {
    if (notices.isEmpty()) Text("尚未加入通知", color = WebMuted, fontSize = 12.sp)
    notices.forEach { notice ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable { onEdit(notice) }.padding(vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(noticeSummary(notice), color = WebWarm, fontSize = 12.sp)
                Text("點擊修改", color = WebMuted, fontSize = 10.sp)
            }
            TextButton(onClick = { onRemove(notice) }) { Text("刪除", color = WebDanger, fontSize = 11.sp) }
        }
        HorizontalDivider(color = WebLine.copy(alpha = .5f))
    }
    val duplicateCount = notices.groupingBy {
        listOf(it.unit, it.amount, it.anchor, it.hour, it.minute, it.rangeStartOffsetDays, it.rangeEndOffsetDays)
    }
        .eachCount().values.count { it > 1 }
    if (duplicateCount > 0) {
        Text("注意：有相同時間及條件嘅重複 Notification。", color = WebWarm, fontSize = 11.sp)
    }
    val overlapping = notices.withIndex().any { (index, first) ->
        notices.drop(index + 1).any { second ->
            if (first.anchor != second.anchor || first.hour != second.hour || first.minute != second.minute) false
            else {
                fun bounds(n: ReminderNotice): IntRange? = when (n.unit) {
                    NoticeUnit.DAY -> (-n.amount)..(-n.amount)
                    NoticeUnit.DAY_RANGE -> n.rangeStartOffsetDays..n.rangeEndOffsetDays
                    else -> null
                }
                val a = bounds(first)
                val b = bounds(second)
                a != null && b != null && a.first <= b.last && b.first <= a.last
            }
        }
    }
    if (overlapping && duplicateCount == 0) {
        Text("注意：指定日期與連續日期範圍有重疊；同一 Task 同一時間只會發一次通知。", color = WebWarm, fontSize = 11.sp)
    }
    OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) { Text("+ Notification") }
}

@Composable
private fun RuleFormScreen(
    draft: RuleDraft, scrollState: ScrollState, onBack: () -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?,
    onSimulate: () -> Unit,
    onAddRelated: (RelatedTaskTemplate?) -> Unit,
    onAddPreparation: (PreparationStep?) -> Unit,
    onAddNotice: (ReminderNotice?) -> Unit,
) {
    FullScreenRuleForm(
        title = if (draft.existing) "編輯 Reminder Rule" else "新增 Reminder Rule",
        subtitle = "完整設定 · 所有修改會喺儲存後生效", scrollState = scrollState,
        onBack = onBack, saveEnabled = draft.valid, onSave = onSave,
    ) {
        EditorSection("基本資料", "私人 Event 可直接選 Rule；System Calendar 按 keyword 配對。") {
            OutlinedTextField(
                value = draft.name, onValueChange = { draft.name = it }, label = { Text("Rule 名稱") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = draft.keywords, onValueChange = { draft.keywords = it },
                label = { Text("Calendar keywords（逗號分隔）") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        EditorSection("主事項", "主 Event 可係當日 Task，亦可使用舊式 Range。") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("舊式主事項 Range", color = WebInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = draft.legacyWindow, onCheckedChange = { draft.legacyWindow = it })
            }
            if (draft.legacyWindow) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = draft.windowStart, onValueChange = { draft.windowStart = it.filter(Char::isDigit) },
                        label = { Text("D-開始") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.windowEnd, onValueChange = { draft.windowEnd = it.filter(Char::isDigit) },
                        label = { Text("D-最後") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                }
                if (!validRange(draft.windowStart, draft.windowEnd)) {
                    Text("D-開始必須 ≥ D-最後，兩者均為 0–1825 日。", color = WebDanger, fontSize = 11.sp)
                }
            }
            OutlinedTextField(
                value = draft.persistentBefore, onValueChange = { draft.persistentBefore = it.filter(Char::isDigit) },
                label = { Text("提前幾日常駐頂部顯示") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("0 = 當日開始；顯示至 Event 結束或 Task 完成") },
            )
            if (!validBoundedNumber(draft.persistentBefore, 365)) {
                Text("請輸入 0–365 日。", color = WebDanger, fontSize = 11.sp)
            }
        }
        EditorSection("主事項 Notifications", "每個 D-N 可以加多個指定時間，例如 09:00、18:00；不會自動 00:00 通知。") {
            NotificationsList(
                notices = draft.notices, onEdit = onAddNotice,
                onRemove = { removed -> draft.notices = draft.notices.filterNot { it.id == removed.id } },
            )
        }
        EditorSection("Related Range Tasks", "相對 Event 自動生成，Range 只需完成一次，唔使預先揀日期。") {
            draft.related.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onAddRelated(item) }.padding(vertical = 8.dp)) {
                        Text(item.title, color = WebAccent2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("D-${item.startDaysBefore} 至 D-${item.endDaysBefore} · ${item.notices.size} 個通知", color = WebMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { draft.related = draft.related.filterNot { it.id == item.id } }) { Text("刪除", color = WebDanger) }
                }
            }
            OutlinedButton(onClick = { onAddRelated(null) }, modifier = Modifier.fillMaxWidth()) { Text("+ Related Task") }
        }
        EditorSection("事前準備 Tasks", "例如 D-2 準備物品、H-12 開始禁食，每項可有獨立通知。") {
            draft.steps.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onAddPreparation(item) }.padding(vertical = 8.dp)) {
                        Text(item.title, color = WebAccent2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${if (item.unit == PrepUnit.DAY) "D" else "H"}-${item.amount} · ${item.notices.size} 個通知", color = WebMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { draft.steps = draft.steps.filterNot { it.id == item.id } }) { Text("刪除", color = WebDanger) }
                }
            }
            OutlinedButton(onClick = { onAddPreparation(null) }, modifier = Modifier.fillMaxWidth()) { Text("+ 準備 Task") }
        }
        EditorSection("Simulate / Test", "唔使儲存 Rule，都可以睇生成嘅 Tasks、日期同所有 Notification 時間。") {
            OutlinedButton(onClick = onSimulate, enabled = draft.valid, modifier = Modifier.fillMaxWidth()) {
                Text("Simulate Rule · Schedule Preview")
            }
            Text("模擬資料不寫入 SQLite；Test Notification 只會即刻發一次測試通知。", color = WebMuted, fontSize = 11.sp)
        }
        EditorSection("Rule 狀態") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", color = WebInk, modifier = Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft.enabled = it })
            }
        }
        if (onDelete != null) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text("刪除 Rule 及連結嘅私人事項", color = WebDanger)
            }
        }
    }
}

@Composable
private fun RelatedFormScreen(
    draft: RelatedDraft, scrollState: ScrollState, onBack: () -> Unit, onSave: () -> Unit,
    onAddNotice: (ReminderNotice?) -> Unit,
) {
    FullScreenRuleForm(
        title = "Related Range Task", subtitle = "相對主 Event 計算合資格日子", scrollState = scrollState,
        onBack = onBack, saveEnabled = draft.valid, onSave = onSave,
    ) {
        EditorSection("Task 資料", "指定 D-開始及 D-最後，Range 內只做一次。") {
            OutlinedTextField(
                value = draft.title, onValueChange = { draft.title = it },
                label = { Text("要完成嘅事情，例如：血液檢查") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = draft.start, onValueChange = { draft.start = it.filter(Char::isDigit) },
                    label = { Text("D-開始，例如 18") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = draft.end, onValueChange = { draft.end = it.filter(Char::isDigit) },
                    label = { Text("D-最後，例如 14") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            if (!validRange(draft.start, draft.end)) {
                Text("D-開始必須 ≥ D-最後，兩者均為 0–1825 日。", color = WebDanger, fontSize = 11.sp)
            }
        }
        EditorSection("合資格日期", "會列出所有符合條件嘅日子，不需要你揀其中一日。") {
            listOf(
                EligibleDays.EVERY_DAY to "每日",
                EligibleDays.MON_FRI to "Mon–Fri",
                EligibleDays.MON_SAT_MORNING to "Mon–Sat Morning",
            ).forEach { (choice, label) ->
                OutlinedButton(
                    onClick = { draft.policy = choice }, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, if (draft.policy == choice) WebAccent else WebLine),
                ) { Text(label, color = if (draft.policy == choice) WebAccent2 else WebInk) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("排除香港官方 Public Holiday", color = WebInk, modifier = Modifier.weight(1f), fontSize = 12.sp)
                Switch(checked = draft.excludePH, onCheckedChange = { draft.excludePH = it })
            }
            OutlinedTextField(
                value = draft.lead, onValueChange = { draft.lead = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Range 開始前幾日常駐提示") },
            )
            if (!validBoundedNumber(draft.lead, 365)) Text("請輸入 0–365 日。", color = WebDanger, fontSize = 11.sp)
        }
        EditorSection("此 Task Notifications", "可同時用 D-28 提醒、Range 期間每日多個時間。") {
            NotificationsList(
                notices = draft.notices, onEdit = onAddNotice,
                onRemove = { removed -> draft.notices = draft.notices.filterNot { it.id == removed.id } },
            )
        }
    }
}

@Composable
private fun PreparationFormScreen(
    context: Context, draft: PreparationDraft, scrollState: ScrollState, onBack: () -> Unit, onSave: () -> Unit,
    onAddNotice: (ReminderNotice?) -> Unit,
) {
    FullScreenRuleForm(
        title = "事前準備 Task", subtitle = "按主 Event 日期或開始時間倒數", scrollState = scrollState,
        onBack = onBack, saveEnabled = draft.valid, onSave = onSave,
    ) {
        EditorSection("Task 資料") {
            OutlinedTextField(
                value = draft.title, onValueChange = { draft.title = it }, label = { Text("例如：開始禁食") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = { draft.unit = PrepUnit.DAY }, modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (draft.unit == PrepUnit.DAY) WebAccent else WebLine),
                ) { Text("D-N") }
                OutlinedButton(
                    onClick = { draft.unit = PrepUnit.HOUR }, modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (draft.unit == PrepUnit.HOUR) WebAccent else WebLine),
                ) { Text("H-N") }
            }
            OutlinedTextField(
                value = draft.amount, onValueChange = { draft.amount = it.filter(Char::isDigit) },
                label = { Text(if (draft.unit == PrepUnit.HOUR) "開始前 N 小時" else "開始前 N 日") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            if (!validBoundedNumber(draft.amount, 1825)) Text("請輸入 0–1825。", color = WebDanger, fontSize = 11.sp)
            if (draft.unit == PrepUnit.DAY) {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, h, m -> draft.hour = h; draft.minute = m },
                            draft.hour, draft.minute, true).show()
                    }, modifier = Modifier.fillMaxWidth(),
                ) { Text("Task 時間 ${ruleClock(draft.hour, draft.minute)}") }
            } else {
                Text("H-N 必須配合有 Start Time 嘅 Event；不能以 Full Day 00:00 倒數。", color = WebWarm, fontSize = 11.sp)
            }
            OutlinedTextField(
                value = draft.lead, onValueChange = { draft.lead = it.filter(Char::isDigit) },
                label = { Text("提前幾日常駐顯示") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            if (!validBoundedNumber(draft.lead, 365)) Text("請輸入 0–365 日。", color = WebDanger, fontSize = 11.sp)
        }
        EditorSection("此 Task Notifications", "每個準備步驟可有獨立嘅多個 Notification。") {
            NotificationsList(
                notices = draft.notices, onEdit = onAddNotice,
                onRemove = { removed -> draft.notices = draft.notices.filterNot { it.id == removed.id } },
            )
        }
    }
}

@Composable
private fun RelativeDaySelector(
    title: String, position: DayPosition, amount: String,
    onPositionChange: (DayPosition) -> Unit, onAmountChange: (String) -> Unit,
) {
    Text(title, color = WebInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf(DayPosition.BEFORE to "之前", DayPosition.ON to "當日", DayPosition.AFTER to "之後")
            .forEach { (choice, text) ->
                OutlinedButton(
                    onClick = {
                        if (choice != DayPosition.ON && amount.toIntOrNull() == 0) onAmountChange("1")
                        onPositionChange(choice)
                    },
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, if (position == choice) WebAccent else WebLine),
                ) { Text(text, color = if (position == choice) WebAccent2 else WebInk, fontSize = 12.sp) }
            }
    }
    if (position != DayPosition.ON) {
        OutlinedTextField(
            value = amount, onValueChange = { onAmountChange(it.filter(Char::isDigit)) },
            label = { Text("N 日（1–1825）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        if (!validDayOffset(position, amount)) Text("請輸入 1–1825 日。", color = WebDanger, fontSize = 11.sp)
    }
}

@Composable
private fun NoticeFormScreen(
    context: Context, draft: NoticeDraft, scrollState: ScrollState, allowWindow: Boolean, onBack: () -> Unit, onSave: () -> Unit,
) {
    FullScreenRuleForm(
        title = "Notification", subtitle = "指定日期／連續日期／小時倒數，可自由組合多個通知", scrollState = scrollState,
        onBack = onBack, saveEnabled = draft.valid, onSave = onSave,
    ) {
        EditorSection("通知日期規則") {
            val choices = buildList {
                add(NoticeUnit.DAY to "指定日期")
                add(NoticeUnit.DAY_RANGE to "連續日期")
                add(NoticeUnit.HOUR to "H-N 小時")
                if (allowWindow || draft.unit == NoticeUnit.WINDOW_DAILY) add(NoticeUnit.WINDOW_DAILY to "合資格日每日")
            }
            choices.chunked(2).forEach { rowChoices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowChoices.forEach { (choice, label) ->
                        OutlinedButton(
                            onClick = {
                                draft.unit = choice
                                if (choice == NoticeUnit.HOUR && draft.anchor in listOf(NoticeAnchor.WINDOW_START, NoticeAnchor.WINDOW_END)) {
                                    draft.anchor = NoticeAnchor.EVENT
                                }
                            }, modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, if (draft.unit == choice) WebAccent else WebLine),
                        ) { Text(label, color = if (draft.unit == choice) WebAccent2 else WebInk, fontSize = 12.sp) }
                    }
                    if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            when (draft.unit) {
                NoticeUnit.DAY -> {
                    Text("指定相對日期，例如 D-2、當日、D+2。", color = WebMuted, fontSize = 12.sp)
                    RelativeDaySelector(
                        title = "通知日期", position = draft.position, amount = draft.amount,
                        onPositionChange = { draft.position = it }, onAmountChange = { draft.amount = it },
                    )
                }
                NoticeUnit.DAY_RANGE -> {
                    Text("由開始至結束，每日通知一次；唔需要選實際執行日。", color = WebMuted, fontSize = 12.sp)
                    RelativeDaySelector(
                        title = "由（包括）", position = draft.rangeStartPosition, amount = draft.rangeStartAmount,
                        onPositionChange = { draft.rangeStartPosition = it }, onAmountChange = { draft.rangeStartAmount = it },
                    )
                    HorizontalDivider(color = WebLine)
                    RelativeDaySelector(
                        title = "至（包括）", position = draft.rangeEndPosition, amount = draft.rangeEndAmount,
                        onPositionChange = { draft.rangeEndPosition = it }, onAmountChange = { draft.rangeEndAmount = it },
                    )
                    if (!draft.valid) Text("開始日期不可遲過結束日期，每項 N 為 1–1825。", color = WebDanger, fontSize = 11.sp)
                }
                NoticeUnit.HOUR -> {
                    Text("根據 Event／準備 Task 開始時間倒數，Full Day 無時間基準。", color = WebMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = draft.amount, onValueChange = { draft.amount = it.filter(Char::isDigit) },
                        label = { Text("N 小時前（0–1825）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    if (!draft.valid) Text("請輸入 0–1825 小時。", color = WebDanger, fontSize = 11.sp)
                }
                NoticeUnit.WINDOW_DAILY -> {
                    Text("喺 Related Task Range 嘅所有合資格日期通知；會遵守星期六上午限制。", color = WebMuted, fontSize = 12.sp)
                }
            }
            if (draft.unit != NoticeUnit.WINDOW_DAILY) {
                Text("相對邊個日期／時間計", color = WebInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val anchors = buildList {
                    add(NoticeAnchor.EVENT to "主事項")
                    add(NoticeAnchor.TASK to "此 Task")
                    if (allowWindow && draft.unit != NoticeUnit.HOUR) {
                        add(NoticeAnchor.WINDOW_START to "Range 開始")
                        add(NoticeAnchor.WINDOW_END to "Range 最後")
                    }
                }
                anchors.chunked(2).forEach { anchorRow ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        anchorRow.forEach { (anchor, label) ->
                            OutlinedButton(
                                onClick = { draft.anchor = anchor }, modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, if (draft.anchor == anchor) WebAccent else WebLine),
                            ) { Text(label, color = if (draft.anchor == anchor) WebAccent2 else WebInk, fontSize = 12.sp) }
                        }
                        if (anchorRow.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (draft.unit != NoticeUnit.HOUR) {
            EditorSection("通知時間", "相對日數只定日期；唔會喺午夜 00:00 自動通知。") {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, h, m -> draft.hour = h; draft.minute = m },
                            draft.hour, draft.minute, true).show()
                    }, modifier = Modifier.fillMaxWidth(),
                ) { Text("通知時間 ${ruleClock(draft.hour, draft.minute)}") }
                Text("每條通知規則對應一個時間；要每日 09:00、18:00 都通知，新增第二條同樣日期規則即可。", color = WebMuted, fontSize = 11.sp)
            }
        }
    }
}

private fun ruleClock(h: Int, m: Int): String = "%02d:%02d".format(Locale.ENGLISH, h, m)
