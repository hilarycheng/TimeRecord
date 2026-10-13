package com.quickstamp.timerecorder.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.EligibleDays
import com.quickstamp.timerecorder.data.NoticeAnchor
import com.quickstamp.timerecorder.data.NoticeUnit
import com.quickstamp.timerecorder.data.PrepUnit
import com.quickstamp.timerecorder.data.PreparationStep
import com.quickstamp.timerecorder.data.PrivateReminder
import com.quickstamp.timerecorder.data.RelatedTaskTemplate
import com.quickstamp.timerecorder.data.ReminderNotice
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderRuleKind
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private fun hm(hour: Int, minute: Int) = "%02d:%02d".format(Locale.ENGLISH, hour, minute)

internal fun noticeSummary(notice: ReminderNotice): String {
    val prefix = when (notice.unit) {
        NoticeUnit.DAY -> "D-${notice.amount}"
        NoticeUnit.HOUR -> "H-${notice.amount}"
        NoticeUnit.WINDOW_DAILY -> "有效期每日"
    }
    val anchor = when (notice.anchor) {
        NoticeAnchor.EVENT -> "主事項"
        NoticeAnchor.TASK -> "此 Task"
        NoticeAnchor.WINDOW_START -> "Range 開始"
        NoticeAnchor.WINDOW_END -> "Range 最後"
    }
    return "$prefix · ${if (notice.unit == NoticeUnit.HOUR) "按開始時間計" else hm(notice.hour, notice.minute)}" +
        if (notice.unit == NoticeUnit.WINDOW_DAILY) "" else " · $anchor"
}

@Composable
internal fun ReminderRulesPage(context: Context, onBack: () -> Unit, onChanged: () -> Unit) {
    var rules by remember { mutableStateOf(ReminderStore.rules(context)) }
    var editing by remember { mutableStateOf<ReminderRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        rules = ReminderStore.rules(context)
        scope.launch(Dispatchers.IO) { ReminderScheduler.scheduleAll(context.applicationContext) }
        onChanged()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = WebAccent, fontWeight = FontWeight.Black) }
            Text("Reminder Rules", color = WebInk, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = WebAccent)) {
                Text("+ Rule", fontWeight = FontWeight.Black)
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Event → Related Tasks → Multiple Notifications", color = WebInk, fontWeight = FontWeight.Bold)
                Text("D-N 只計日期，通知時間逐個設定；H-N 以 Event 開始時間計。每個 Task 獨立完成。", color = WebMuted, fontSize = 11.sp)
                Text("私人事項只儲喺 App；System Calendar 保持只讀。", color = WebBus, fontSize = 10.sp)
            }
        }
        if (rules.isEmpty()) Text("尚未設定規則", color = WebMuted)
        rules.forEach { rule ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { editing = rule },
                shape = RoundedCornerShape(17.dp), color = WebCard2,
                border = BorderStroke(1.dp, if (rule.enabled) WebAccent.copy(alpha = .45f) else WebLine),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.name, color = WebInk, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Text(if (rule.enabled) "ON" else "OFF", color = if (rule.enabled) WebGood else WebMuted, fontSize = 10.sp)
                    }
                    if (rule.keywords.isNotEmpty()) Text("Keyword: ${rule.keywords.joinToString(" / ")}", color = WebAccent2, fontSize = 10.sp)
                    Text(ruleSummary(rule), color = WebMuted, fontSize = 11.sp)
                    Text("通知 ${rule.notices.size} 個 · Related Tasks ${rule.relatedTasks.size} 個 · 準備步驟 ${rule.preparationSteps.size} 個", color = WebWarm, fontSize = 11.sp)
                }
            }
        }
    }

    if (adding) RuleEditorDialog(
        context = context, initial = null, onDismiss = { adding = false },
        onSave = { ReminderStore.upsertRule(context, it); adding = false; reload() }, onDelete = null,
    )
    editing?.let { rule ->
        RuleEditorDialog(
            context = context, initial = rule, onDismiss = { editing = null },
            onSave = { ReminderStore.upsertRule(context, it); editing = null; reload() },
            onDelete = { ReminderStore.deleteRule(context, rule.id); editing = null; reload() },
        )
    }
}

@Composable
private fun RuleEditorDialog(
    context: Context,
    initial: ReminderRule?,
    onDismiss: () -> Unit,
    onSave: (ReminderRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val base = remember(initial?.id) { initial ?: ReminderRule(name = "") }
    var name by remember(initial?.id) { mutableStateOf(base.name) }
    var keywords by remember(initial?.id) { mutableStateOf(base.keywords.joinToString(", ")) }
    var legacyWindow by remember(initial?.id) { mutableStateOf(base.kind == ReminderRuleKind.REQUIRED_WINDOW) }
    var windowStart by remember(initial?.id) { mutableStateOf(base.windowStartDaysBefore.toString()) }
    var windowEnd by remember(initial?.id) { mutableStateOf(base.windowEndDaysBefore.toString()) }
    var persistentBefore by remember(initial?.id) { mutableStateOf(base.persistentDaysBefore.toString()) }
    var notices by remember(initial?.id) { mutableStateOf(base.notices) }
    var related by remember(initial?.id) { mutableStateOf(base.relatedTasks) }
    var steps by remember(initial?.id) { mutableStateOf(base.preparationSteps) }
    var enabled by remember(initial?.id) { mutableStateOf(base.enabled) }
    var addingRelated by remember { mutableStateOf(false) }
    var editingRelated by remember { mutableStateOf<RelatedTaskTemplate?>(null) }
    var addingStep by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<PreparationStep?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 Reminder Rule" else "修改 Reminder Rule", color = WebInk, fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("Calendar keywords（逗號分隔）") },
                    supportingText = { Text("Private Event 可直接選 Rule；System Calendar 按 keyword 配對") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("舊式主事項 Range", color = WebInk, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = legacyWindow, onCheckedChange = { legacyWindow = it })
                }
                if (legacyWindow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = windowStart, onValueChange = { windowStart = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f),
                            label = { Text("D-開始") }, singleLine = true)
                        OutlinedTextField(value = windowEnd, onValueChange = { windowEnd = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f),
                            label = { Text("D-最後") }, singleLine = true)
                    }
                }
                OutlinedTextField(value = persistentBefore, onValueChange = { persistentBefore = it.filter(Char::isDigit) },
                    label = { Text("提前幾日常駐頂部顯示") }, singleLine = true,
                    supportingText = { Text("0 = 當日開始；顯示至 Event 結束或 Task 完成") })
                NoticesEditor(context, "主事項 Notifications", notices, { notices = it }, allowWindow = legacyWindow)
                Text("Related Tasks · 自動由 Event 日期生成", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                related.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.title} · D-${r.startDaysBefore}～D-${r.endDaysBefore}",
                            color = WebAccent2, modifier = Modifier.weight(1f).clickable { editingRelated = r }, fontSize = 12.sp)
                        TextButton(onClick = { related = related.filterNot { it.id == r.id } }) { Text("刪除", color = WebDanger) }
                    }
                }
                OutlinedButton(onClick = { addingRelated = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Related Range Task") }
                Text("事前準備步驟 · D-N / H-N", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                steps.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.title} · ${if (s.unit == PrepUnit.DAY) "D" else "H"}-${s.amount}",
                            color = WebAccent2, modifier = Modifier.weight(1f).clickable { editingStep = s }, fontSize = 12.sp)
                        TextButton(onClick = { steps = steps.filterNot { it.id == s.id } }) { Text("刪除", color = WebDanger) }
                    }
                }
                OutlinedButton(onClick = { addingStep = true }, modifier = Modifier.fillMaxWidth()) { Text("+ 準備步驟") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", color = WebInk, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete Rule（連結嘅私人事項亦會刪除）", color = WebDanger)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty(), onClick = {
                onSave(base.copy(
                    name = name.trim(), keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
                    kind = if (legacyWindow) ReminderRuleKind.REQUIRED_WINDOW else if (related.isNotEmpty()) ReminderRuleKind.COMBINED else ReminderRuleKind.COUNTDOWN,
                    windowStartDaysBefore = windowStart.toIntOrNull()?.coerceAtMost(1825) ?: 18,
                    windowEndDaysBefore = windowEnd.toIntOrNull()?.coerceAtMost(1825) ?: 14,
                    persistentDaysBefore = persistentBefore.toIntOrNull()?.coerceAtMost(365) ?: 0,
                    notices = notices, relatedTasks = related, preparationSteps = steps, enabled = enabled,
                ))
            }) { Text("Save", color = WebAccent, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = WebCard2,
    )
    if (addingRelated || editingRelated != null) RelatedTaskEditorDialog(
        context, editingRelated, onDismiss = { addingRelated = false; editingRelated = null },
        onSave = { item -> related = related.filterNot { it.id == item.id } + item; addingRelated = false; editingRelated = null },
    )
    if (addingStep || editingStep != null) PreparationStepEditorDialog(
        context, editingStep, onDismiss = { addingStep = false; editingStep = null },
        onSave = { item -> steps = steps.filterNot { it.id == item.id } + item; addingStep = false; editingStep = null },
    )
}

@Composable
private fun NoticesEditor(
    context: Context,
    label: String,
    notices: List<ReminderNotice>,
    onChange: (List<ReminderNotice>) -> Unit,
    allowWindow: Boolean,
) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = WebInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("可新增多個相同 D-N、不同時間；不會喺 00:00 自動通知。", color = WebMuted, fontSize = 10.sp)
        notices.forEach { n ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(noticeSummary(n), color = WebWarm, modifier = Modifier.weight(1f), fontSize = 11.sp)
                TextButton(onClick = { onChange(notices.filterNot { it.id == n.id }) }) { Text("×", color = WebDanger) }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Notification") }
    }
    if (adding) NoticeEditorDialog(context, allowWindow, onDismiss = { adding = false }, onSave = {
        onChange(notices + it); adding = false
    })
}

@Composable
private fun NoticeEditorDialog(context: Context, allowWindow: Boolean, onDismiss: () -> Unit, onSave: (ReminderNotice) -> Unit) {
    var unit by remember { mutableStateOf(NoticeUnit.DAY) }
    var amount by remember { mutableStateOf("0") }
    var anchor by remember { mutableStateOf(NoticeAnchor.EVENT) }
    var hour by remember { mutableStateOf(9) }
    var minute by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增通知時間", color = WebInk) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = { unit = NoticeUnit.DAY }, modifier = Modifier.weight(1f)) { Text("D-N") }
                    OutlinedButton(onClick = { unit = NoticeUnit.HOUR }, modifier = Modifier.weight(1f)) { Text("H-N") }
                    if (allowWindow) OutlinedButton(onClick = { unit = NoticeUnit.WINDOW_DAILY }, modifier = Modifier.weight(1f)) { Text("每日") }
                }
                Text(when (unit) {
                    NoticeUnit.DAY -> "按日期計，例如 D-28 09:00；不會用半夜 00:00。"
                    NoticeUnit.HOUR -> "按實際開始時間倒數，例如 H-12；Full Day Event 不能用 H-N。"
                    NoticeUnit.WINDOW_DAILY -> "有效期內每個合資格日期提醒；週六上午只會喺中午前通知。"
                }, color = WebMuted, fontSize = 11.sp)
                if (unit != NoticeUnit.WINDOW_DAILY) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                        label = { Text(if (unit == NoticeUnit.HOUR) "N 小時前" else "N 日前") }, singleLine = true)
                    Text("時間基準", color = WebInk, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(NoticeAnchor.EVENT, NoticeAnchor.TASK).forEach { option ->
                            OutlinedButton(onClick = { anchor = option }, modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, if (anchor == option) WebAccent else WebLine)) {
                                Text(if (option == NoticeAnchor.EVENT) "主事項" else "此 Task", fontSize = 11.sp)
                            }
                        }
                    }
                    if (allowWindow && unit == NoticeUnit.DAY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(NoticeAnchor.WINDOW_START, NoticeAnchor.WINDOW_END).forEach { option ->
                                OutlinedButton(onClick = { anchor = option }, modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, if (anchor == option) WebAccent else WebLine)) {
                                    Text(if (option == NoticeAnchor.WINDOW_START) "Range 開始" else "Range 最後", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                if (unit != NoticeUnit.HOUR) OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("通知時間 ${hm(hour, minute)}") }
            }
        },
        confirmButton = { TextButton(enabled = unit == NoticeUnit.WINDOW_DAILY || amount.toIntOrNull() != null, onClick = {
            onSave(ReminderNotice(unit = unit, amount = (amount.toIntOrNull() ?: 0).coerceIn(0, 1825),
                hour = hour, minute = minute, anchor = if (unit == NoticeUnit.WINDOW_DAILY) NoticeAnchor.TASK else anchor))
        }) { Text("新增", color = WebAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, containerColor = WebCard2,
    )
}

@Composable
private fun RelatedTaskEditorDialog(context: Context, initial: RelatedTaskTemplate?, onDismiss: () -> Unit, onSave: (RelatedTaskTemplate) -> Unit) {
    val base = remember(initial?.id) { initial ?: RelatedTaskTemplate(title = "", notices = listOf(
        ReminderNotice(unit = NoticeUnit.DAY, amount = 28, anchor = NoticeAnchor.EVENT),
        ReminderNotice(unit = NoticeUnit.WINDOW_DAILY),
    )) }
    var title by remember(initial?.id) { mutableStateOf(base.title) }
    var start by remember(initial?.id) { mutableStateOf(base.startDaysBefore.toString()) }
    var end by remember(initial?.id) { mutableStateOf(base.endDaysBefore.toString()) }
    var policy by remember(initial?.id) { mutableStateOf(base.eligibleDays) }
    var excludePH by remember(initial?.id) { mutableStateOf(base.excludePublicHolidays) }
    var lead by remember(initial?.id) { mutableStateOf(base.displayDaysBeforeWindow.toString()) }
    var notices by remember(initial?.id) { mutableStateOf(base.notices) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Related Range Task", color = WebInk) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("要完成嘅事情，例如：血液檢查") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit) }, label = { Text("D-開始（如 18）") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it.filter(Char::isDigit) }, label = { Text("D-最後（如 14）") }, modifier = Modifier.weight(1f))
                }
                Text("Range 內只需完成一次，無需預先揀日期。", color = WebMuted, fontSize = 11.sp)
                listOf(EligibleDays.EVERY_DAY to "每日", EligibleDays.MON_FRI to "Mon–Fri", EligibleDays.MON_SAT_MORNING to "Mon–Sat Morning").forEach { (choice, label) ->
                    OutlinedButton(onClick = { policy = choice }, modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, if (policy == choice) WebAccent else WebLine)) { Text(label, color = WebInk) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("排除香港官方 Public Holiday", color = WebInk, modifier = Modifier.weight(1f), fontSize = 11.sp)
                    Switch(checked = excludePH, onCheckedChange = { excludePH = it })
                }
                OutlinedTextField(value = lead, onValueChange = { lead = it.filter(Char::isDigit) },
                    label = { Text("Range 開始前幾日常駐提示") }, singleLine = true)
                NoticesEditor(context, "此 Related Task Notifications", notices, { notices = it }, allowWindow = true)
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && start.toIntOrNull() != null && end.toIntOrNull() != null, onClick = {
            onSave(base.copy(title = title.trim(), startDaysBefore = (start.toIntOrNull() ?: 18).coerceIn(0, 1825),
                endDaysBefore = (end.toIntOrNull() ?: 14).coerceIn(0, 1825), eligibleDays = policy,
                excludePublicHolidays = excludePH, displayDaysBeforeWindow = (lead.toIntOrNull() ?: 0).coerceIn(0, 365), notices = notices))
        }) { Text("Save", color = WebAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, containerColor = WebCard2,
    )
}

@Composable
private fun PreparationStepEditorDialog(context: Context, initial: PreparationStep?, onDismiss: () -> Unit, onSave: (PreparationStep) -> Unit) {
    val base = remember(initial?.id) { initial ?: PreparationStep(title = "", notices = listOf(ReminderNotice(unit = NoticeUnit.DAY, anchor = NoticeAnchor.TASK))) }
    var title by remember(initial?.id) { mutableStateOf(base.title) }
    var unit by remember(initial?.id) { mutableStateOf(base.unit) }
    var amount by remember(initial?.id) { mutableStateOf(base.amount.toString()) }
    var hour by remember(initial?.id) { mutableStateOf(base.hour) }
    var minute by remember(initial?.id) { mutableStateOf(base.minute) }
    var lead by remember(initial?.id) { mutableStateOf(base.displayDaysBefore.toString()) }
    var notices by remember(initial?.id) { mutableStateOf(base.notices) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("事前準備 Task", color = WebInk) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("例如：開始禁食") })
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { unit = PrepUnit.DAY }, modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (unit == PrepUnit.DAY) WebAccent else WebLine)) { Text("D-N") }
                    OutlinedButton(onClick = { unit = PrepUnit.HOUR }, modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, if (unit == PrepUnit.HOUR) WebAccent else WebLine)) { Text("H-N") }
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text(if (unit == PrepUnit.HOUR) "開始前 N 小時" else "開始前 N 日") }, singleLine = true)
                if (unit == PrepUnit.DAY) OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("事前步驟時間 ${hm(hour, minute)}") }
                else Text("H-N 必須係有 Start Time 嘅 Event；會準確倒數，唔會當成 00:00。", color = WebMuted, fontSize = 11.sp)
                OutlinedTextField(value = lead, onValueChange = { lead = it.filter(Char::isDigit) },
                    label = { Text("提前幾日常駐顯示") }, singleLine = true)
                NoticesEditor(context, "此步驟 Notifications", notices, { notices = it }, allowWindow = false)
                Text("提示：H-12 步驟可加入 H-0（此 Task）Notification，準確喺開始禁食時通知。", color = WebMuted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && amount.toIntOrNull() != null, onClick = {
            val fixedNotices = if (initial == null && unit == PrepUnit.HOUR && notices.size == 1 &&
                notices[0].unit == NoticeUnit.DAY && notices[0].amount == 0 && notices[0].anchor == NoticeAnchor.TASK) {
                listOf(ReminderNotice(unit = NoticeUnit.HOUR, anchor = NoticeAnchor.TASK, amount = 0))
            } else notices
            onSave(base.copy(title = title.trim(), unit = unit, amount = (amount.toIntOrNull() ?: 0).coerceIn(0, 1825),
                hour = hour, minute = minute, displayDaysBefore = (lead.toIntOrNull() ?: 0).coerceIn(0, 365), notices = fixedNotices))
        }) { Text("Save", color = WebAccent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, containerColor = WebCard2,
    )
}

@Composable
internal fun PrivateReminderDialog(
    context: Context,
    initialDate: LocalDate,
    rules: List<ReminderRule>,
    onDismiss: () -> Unit,
    onSave: (PrivateReminder) -> Unit,
    initial: PrivateReminder? = null,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember(initial?.id) { mutableStateOf(initial?.anchorDate ?: initialDate) }
    var fullDay by remember(initial?.id) { mutableStateOf(initial?.startTime == null) }
    var startTime by remember(initial?.id) { mutableStateOf(initial?.startTime ?: LocalTime.of(9, 0)) }
    var endDate by remember(initial?.id) { mutableStateOf(initial?.endDate ?: initial?.anchorDate ?: initialDate) }
    var endTime by remember(initial?.id) { mutableStateOf(initial?.endTime ?: LocalTime.of(10, 0)) }
    var selectedRuleId by remember(initial?.id, rules) {
        mutableStateOf(initial?.ruleId ?: rules.firstOrNull { it.enabled }?.id ?: rules.firstOrNull()?.id.orEmpty())
    }
    val validEnd = fullDay || (endDate.atTime(endTime).isAfter(date.atTime(startTime)))
    val selectedRule = rules.firstOrNull { it.id == selectedRuleId }
    val hasHourNotice = selectedRule?.let { rule ->
        rule.notices.any { it.unit == NoticeUnit.HOUR } ||
            rule.relatedTasks.any { child -> child.notices.any { it.unit == NoticeUnit.HOUR } } ||
            rule.preparationSteps.any { step -> step.unit == PrepUnit.HOUR || step.notices.any { it.unit == NoticeUnit.HOUR } }
    } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 Private Event" else "修改 Private Event", color = WebInk, fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("事情") }, singleLine = true)
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d ->
                        date = LocalDate.of(y, m + 1, d)
                        if (endDate.isBefore(date)) endDate = date
                    }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("Event 日期 $date") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Full Day", modifier = Modifier.weight(1f), color = WebInk)
                    Switch(checked = fullDay, onCheckedChange = { fullDay = it })
                }
                if (fullDay && hasHourNotice) {
                    Text("注意：呢條 Rule 包含 H-N 通知／Task。Full Day 冇開始時間，H-N 部分唔會觸發。", color = WebWarm, fontSize = 11.sp)
                }
                if (!fullDay) {
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> startTime = LocalTime.of(h, m) },
                        startTime.hour, startTime.minute, true).show() }, modifier = Modifier.fillMaxWidth()) { Text("Start ${startTime}") }
                    OutlinedButton(onClick = {
                        DatePickerDialog(context, { _, y, m, d -> endDate = LocalDate.of(y, m + 1, d) },
                            endDate.year, endDate.monthValue - 1, endDate.dayOfMonth).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("End Date $endDate") }
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> endTime = LocalTime.of(h, m) },
                        endTime.hour, endTime.minute, true).show() }, modifier = Modifier.fillMaxWidth()) { Text("End Time $endTime") }
                    if (!validEnd) Text("End 必須遲過 Start", color = WebDanger, fontSize = 11.sp)
                }
                Text("Reminder Rule", color = WebMuted, fontSize = 11.sp)
                if (rules.isEmpty()) Text("請先建立 Rule", color = WebDanger)
                rules.forEach { rule ->
                    Surface(modifier = Modifier.fillMaxWidth().clickable { selectedRuleId = rule.id },
                        color = if (selectedRuleId == rule.id) WebAccentSoft else WebCard,
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selectedRuleId == rule.id) WebAccent else WebLine)) {
                        Column(Modifier.padding(9.dp)) {
                            Text(rule.name, color = WebInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(ruleSummary(rule), color = WebMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && selectedRuleId.isNotBlank() && validEnd, onClick = {
            onSave(PrivateReminder(
                id = initial?.id ?: java.util.UUID.randomUUID().toString(), title = title.trim(), anchorDate = date,
                ruleId = selectedRuleId, enabled = initial?.enabled ?: true,
                startTime = if (fullDay) null else startTime,
                endDate = if (fullDay) null else endDate,
                endTime = if (fullDay) null else endTime,
            ))
        }) { Text("Save", color = WebAccent, fontWeight = FontWeight.Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, containerColor = WebCard2,
    )
}

internal fun ruleSummary(rule: ReminderRule): String =
    (if (rule.kind == ReminderRuleKind.REQUIRED_WINDOW) "主事項 Range D-${rule.windowStartDaysBefore}～D-${rule.windowEndDaysBefore}"
    else "主事項當日") +
    (if (rule.relatedTasks.isNotEmpty()) " · ${rule.relatedTasks.size} 個 Related Task" else "")
