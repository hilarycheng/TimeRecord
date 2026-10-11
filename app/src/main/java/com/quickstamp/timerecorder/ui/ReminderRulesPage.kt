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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.PrivateReminder
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderRuleKind
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.reminder.ReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

@Composable
internal fun ReminderRulesPage(
    context: Context,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    var rules by remember { mutableStateOf(ReminderStore.rules(context)) }
    var editing by remember { mutableStateOf<ReminderRule?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun reload() { rules = ReminderStore.rules(context); ReminderScheduler.scheduleAll(context); onChanged() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = WebAccent, fontWeight = FontWeight.Black) }
            Text("Reminder Rules", color = WebInk, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Button(
                onClick = { adding = true },
                colors = ButtonDefaults.buttonColors(containerColor = WebAccent, contentColor = Color.White),
            ) { Text("+ Rule", fontWeight = FontWeight.Black) }
        }

        Surface(shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Calendar keyword → 自動計日子", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text("Countdown：只喺指定 D-N 日提醒。Required Window：例如覆診前 18–14 日每日列入『今日必做』，完成後停止。", color = WebMuted, fontSize = 10.sp, lineHeight = 15.sp)
                Text("System/Google Calendar 只讀；App 唔會修改原本 event。", color = WebBus, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (rules.isEmpty()) {
            Text("未有 Reminder Rule", color = WebMuted, modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp), fontSize = 12.sp)
        }
        rules.forEach { rule ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { editing = rule },
                shape = RoundedCornerShape(17.dp),
                color = WebCard2,
                border = BorderStroke(1.dp, if (rule.enabled) WebAccent.copy(alpha = .45f) else WebLine),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.name, color = WebInk, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        Text(if (rule.enabled) "ON" else "OFF", color = if (rule.enabled) WebGood else WebMuted, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    if (rule.keywords.isNotEmpty()) Text("Keyword：${rule.keywords.joinToString(" / ")}", color = WebAccent2, fontSize = 10.sp)
                    Text(ruleSummary(rule), color = WebMuted, fontSize = 10.sp)
                    Text("通知 ${"%02d:%02d".format(Locale.ENGLISH, rule.notifyHour, rule.notifyMinute)}", color = WebWarm, fontSize = 10.sp)
                }
            }
        }
    }

    if (adding) {
        RuleEditorDialog(
            context = context,
            initial = null,
            onDismiss = { adding = false },
            onSave = { ReminderStore.upsertRule(context, it); adding = false; reload() },
            onDelete = null,
        )
    }
    editing?.let { rule ->
        RuleEditorDialog(
            context = context,
            initial = rule,
            onDismiss = { editing = null },
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
    val base = initial ?: ReminderRule(name = "")
    var name by remember(initial?.id) { mutableStateOf(base.name) }
    var keywords by remember(initial?.id) { mutableStateOf(base.keywords.joinToString(", ")) }
    var kind by remember(initial?.id) { mutableStateOf(base.kind) }
    var countdown by remember(initial?.id) { mutableStateOf(base.countdownDaysBefore.joinToString(",")) }
    var windowStart by remember(initial?.id) { mutableStateOf(base.windowStartDaysBefore.toString()) }
    var windowEnd by remember(initial?.id) { mutableStateOf(base.windowEndDaysBefore.toString()) }
    var hour by remember(initial?.id) { mutableStateOf(base.notifyHour) }
    var minute by remember(initial?.id) { mutableStateOf(base.notifyMinute) }
    var enabled by remember(initial?.id) { mutableStateOf(base.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 Reminder Rule" else "修改 Reminder Rule", color = WebInk, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("Calendar keywords（逗號分隔）") }, supportingText = { Text("例如：覆診, 醫生；Private Reminder 可直接揀 Rule，唔靠 keyword") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { kind = ReminderRuleKind.COUNTDOWN }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, if (kind == ReminderRuleKind.COUNTDOWN) WebAccent else WebLine)) { Text("Countdown") }
                    OutlinedButton(onClick = { kind = ReminderRuleKind.REQUIRED_WINDOW }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, if (kind == ReminderRuleKind.REQUIRED_WINDOW) WebAccent else WebLine)) { Text("Required Window") }
                }
                if (kind == ReminderRuleKind.COUNTDOWN) {
                    OutlinedTextField(value = countdown, onValueChange = { countdown = it }, label = { Text("幾日前提醒") }, supportingText = { Text("例如 14,3,1,0") }, singleLine = true)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(value = windowStart, onValueChange = { windowStart = it.filter(Char::isDigit) }, label = { Text("窗口開始（日前）") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = windowEnd, onValueChange = { windowEnd = it.filter(Char::isDigit) }, label = { Text("窗口最後（日前）") }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
                OutlinedButton(
                    onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("通知時間  ${"%02d:%02d".format(Locale.ENGLISH, hour, minute)}") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", color = WebInk, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete Rule", color = WebDanger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = {
                    val days = countdown.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it.coerceAtLeast(0) }.distinct().ifEmpty { listOf(14,3,1,0) }
                    onSave(
                        base.copy(
                            name = name.trim(),
                            keywords = keywords.split(',').map { it.trim() }.filter { it.isNotBlank() },
                            kind = kind,
                            countdownDaysBefore = days,
                            windowStartDaysBefore = (windowStart.toIntOrNull() ?: 18).coerceAtLeast(0),
                            windowEndDaysBefore = (windowEnd.toIntOrNull() ?: 14).coerceAtLeast(0),
                            notifyHour = hour,
                            notifyMinute = minute,
                            enabled = enabled,
                        )
                    )
                }
            ) { Text("Save", color = WebAccent, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = WebCard2,
    )
}

@Composable
internal fun PrivateReminderDialog(
    context: Context,
    initialDate: LocalDate,
    rules: List<ReminderRule>,
    onDismiss: () -> Unit,
    onSave: (PrivateReminder) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate) }
    var selectedRuleId by remember(rules) { mutableStateOf(rules.firstOrNull { it.enabled }?.id ?: rules.firstOrNull()?.id.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增 Private Reminder", color = WebInk, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("事情") }, singleLine = true)
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("基準日期  $date") }
                Text("Reminder Rule", color = WebMuted, fontSize = 10.sp)
                if (rules.isEmpty()) {
                    Text("請先建立 Reminder Rule", color = WebDanger, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    rules.forEach { rule ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { selectedRuleId = rule.id },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedRuleId == rule.id) WebAccentSoft else WebCard,
                            border = BorderStroke(1.dp, if (selectedRuleId == rule.id) WebAccent else WebLine),
                        ) {
                            Column(Modifier.padding(9.dp)) {
                                Text(rule.name, color = WebInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(ruleSummary(rule), color = WebMuted, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.trim().isNotEmpty() && selectedRuleId.isNotBlank(),
                onClick = { onSave(PrivateReminder(title = title.trim(), anchorDate = date, ruleId = selectedRuleId)) },
            ) { Text("Save", color = WebAccent, fontWeight = FontWeight.Black) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = WebCard2,
    )
}

internal fun ruleSummary(rule: ReminderRule): String = when (rule.kind) {
    ReminderRuleKind.COUNTDOWN -> "${rule.countdownDaysBefore.sortedDescending().joinToString(" / ")} 日前"
    ReminderRuleKind.REQUIRED_WINDOW -> "${maxOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore)}–${minOf(rule.windowStartDaysBefore, rule.windowEndDaysBefore)} 日前必做"
}
