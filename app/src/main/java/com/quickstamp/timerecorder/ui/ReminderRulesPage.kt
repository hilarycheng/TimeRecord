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
