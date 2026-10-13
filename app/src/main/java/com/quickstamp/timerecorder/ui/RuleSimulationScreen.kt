package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderTaskKind
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.reminder.ReminderEngine
import com.quickstamp.timerecorder.reminder.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The draft is in-memory only; neither the sample Event nor Rule is saved by Simulate. */
internal class RuleSimulationDraft(val rule: ReminderRule) {
    var eventDate by mutableStateOf(LocalDate.now(HkTime.zone).plusDays(30))
    var fullDay by mutableStateOf(false)
    var startTime by mutableStateOf(LocalTime.of(15, 0))
    var endTime by mutableStateOf(LocalTime.of(16, 0))
}

private val simulationDateTimeFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE) HH:mm", Locale.TRADITIONAL_CHINESE)
private val simulationClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Composable
internal fun RuleSimulationScreen(
    context: Context,
    draft: RuleSimulationDraft,
    scrollState: ScrollState,
    onBack: () -> Unit,
) {
    var result by remember(draft) { mutableStateOf<ReminderEngine.SimulationResult?>(null) }
    var error by remember(draft) { mutableStateOf<String?>(null) }
    var calculating by remember(draft) { mutableStateOf(true) }
    var testMessage by remember(draft) { mutableStateOf<String?>(null) }
    var notificationPage by remember(draft) { mutableStateOf(0) }
    var expandedDateTasks by remember(draft) { mutableStateOf(setOf<String>()) }
    var pendingTest by remember(draft) { mutableStateOf<ReminderEngine.NotificationOccurrence?>(null) }

    LaunchedEffect(draft, draft.eventDate, draft.fullDay, draft.startTime, draft.endTime) {
        calculating = true
        notificationPage = 0
        expandedDateTasks = emptySet()
        result = null
        error = null
        runCatching {
            withContext(Dispatchers.Default) {
                ReminderEngine.simulateRule(context.applicationContext, draft.rule, draft.eventDate,
                    if (draft.fullDay) null else draft.startTime,
                    if (draft.fullDay) null else draft.endTime)
            }
        }.onSuccess { result = it }.onFailure {
            if (it is CancellationException) throw it
            error = "無法預覽：${it.message ?: "未知錯誤"}"
        }
        calculating = false
    }

    fun sendTest(notice: ReminderEngine.NotificationOccurrence) {
        testMessage = try {
            when (ReminderScheduler.testNotificationNow(context, notice)) {
                ReminderScheduler.TestNotificationResult.SENT -> "測試通知已送出；聲音及震動依照現有 Channel 設定。"
                ReminderScheduler.TestNotificationResult.SENT_SILENT -> "測試通知已送出，但目前 Channel 可能係靜音。"
                ReminderScheduler.TestNotificationResult.PERMISSION_REQUIRED -> "未有通知權限，無法發送測試通知。"
                ReminderScheduler.TestNotificationResult.DISABLED -> "通知或 Reminder Channel 已被系統關閉。"
            }
        } catch (ex: SecurityException) {
            "通知權限不足：${ex.message.orEmpty()}"
        } catch (ex: RuntimeException) {
            "測試通知失敗：${ex.message.orEmpty()}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val notice = pendingTest
        pendingTest = null
        if (granted && notice != null) sendTest(notice)
        else testMessage = "通知權限未獲批准，測試通知未送出。"
    }
    fun requestTest(notice: ReminderEngine.NotificationOccurrence) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingTest = notice
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else sendTest(notice)
    }

    val notifications = result?.notifications.orEmpty()
    val firstTest = notifications.firstOrNull { it.at.isAfter(Instant.now()) } ?: notifications.firstOrNull()
    val pageCount = ((notifications.size + 99) / 100).coerceAtLeast(1)
    val visibleNotifications = notifications.drop(notificationPage * 100).take(100)

    Column(Modifier.fillMaxSize().background(WebBg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Rules", color = WebAccent, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f)) {
                Text("Simulate Rule", color = WebInk, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text("唯讀預覽 · 不會儲存 Event、Rule 或更改正式通知", color = WebMuted, fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = WebLine)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SimulationSection("模擬 Event", "用自選日期／時間，直接試目前未儲存嘅 Rule 草稿。") {
                Text(draft.rule.name, color = WebInk, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(context, { _, year, month, day ->
                            draft.eventDate = LocalDate.of(year, month + 1, day)
                        }, draft.eventDate.year, draft.eventDate.monthValue - 1, draft.eventDate.dayOfMonth).show()
                    }, modifier = Modifier.fillMaxWidth(),
                ) { Text("Event 日期：${draft.eventDate}") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Full Day", color = WebInk, modifier = Modifier.weight(1f))
                    Switch(checked = draft.fullDay, onCheckedChange = { draft.fullDay = it })
                }
                if (!draft.fullDay) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                TimePickerDialog(context, { _, h, m -> draft.startTime = LocalTime.of(h, m) },
                                    draft.startTime.hour, draft.startTime.minute, true).show()
                            }, modifier = Modifier.weight(1f),
                        ) { Text("開始 ${draft.startTime.format(simulationClockFormat)}", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                TimePickerDialog(context, { _, h, m -> draft.endTime = LocalTime.of(h, m) },
                                    draft.endTime.hour, draft.endTime.minute, true).show()
                            }, modifier = Modifier.weight(1f),
                        ) { Text("結束 ${draft.endTime.format(simulationClockFormat)}", fontSize = 12.sp) }
                    }
                    if (!draft.endTime.isAfter(draft.startTime))
                        Text("結束時間早於／等於開始時間：模擬為翌日結束。", color = WebWarm, fontSize = 11.sp)
                } else {
                    Text("Full Day 冇開始時刻；以 Event 開始時間計嘅 H-N 通知不會觸發。", color = WebWarm, fontSize = 11.sp)
                }
                Text("所有時間均按 Asia/Hong_Kong 計算。", color = WebMuted, fontSize = 11.sp)
            }
            if (calculating) Text("計算通知時間中…", color = WebMuted)
            error?.let { Text(it, color = WebDanger, fontSize = 12.sp) }
            result?.let { preview ->
                if (preview.warnings.isNotEmpty()) {
                    SimulationSection("注意事項 / 衝突") {
                        preview.warnings.forEach { Text("• $it", color = WebWarm, fontSize = 12.sp) }
                    }
                }
                SimulationSection("生成 Tasks", "顯示 Range 合資格日期；唔使揀實際執行日。") {
                    preview.tasks.forEach { task ->
                        val label = when (task.kind) {
                            ReminderTaskKind.EVENT -> "主 Event"
                            ReminderTaskKind.RELATED -> "Related Task"
                            ReminderTaskKind.PREPARATION -> "事前準備"
                        }
                        Text("$label · ${task.title}", color = WebInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (task.windowStart != null && task.windowEnd != null) {
                            Text("Range：${task.windowStart} 至 ${task.windowEnd}", color = WebMuted, fontSize = 12.sp)
                            val showAll = task.title in expandedDateTasks || task.eligibleDates.size <= 35
                            val datesToShow = if (showAll) task.eligibleDates else task.eligibleDates.take(35)
                            val eligibleText = if (task.eligibleDates.isEmpty()) "冇合資格日期" else
                                "可做：" + datesToShow.joinToString("、") { day ->
                                    "${day.monthValue}/${day.dayOfMonth}" +
                                        (if (task.saturdayMorningOnly && day.dayOfWeek.value == 6) "上午" else "")
                                } + (if (showAll) "" else " … 共 ${task.eligibleDates.size} 日")
                            Text(eligibleText, color = if (task.eligibleDates.isEmpty()) WebDanger else WebBus, fontSize = 11.sp)
                            if (task.eligibleDates.size > 35) {
                                TextButton(onClick = {
                                    expandedDateTasks = if (showAll) expandedDateTasks - task.title else expandedDateTasks + task.title
                                }) { Text(if (showAll) "收起日期" else "顯示全部合資格日期", color = WebAccent2, fontSize = 11.sp) }
                            }
                        } else {
                            Text("時間：${task.due.atZone(HkTime.zone).format(simulationDateTimeFormat)}",
                                color = WebMuted, fontSize = 12.sp)
                        }
                        if (task.holidayDataMissing) Text("⚠ 官方 PH 資料未齊", color = WebWarm, fontSize = 11.sp)
                        HorizontalDivider(color = WebLine.copy(alpha = .5f))
                    }
                }
                SimulationSection("Schedule Preview · ${preview.totalNotifications} 個通知",
                    "以下係正式 Rule Engine 計出嘅時間；過去時間實際唔會補發。") {
                    if (preview.notifications.isEmpty()) {
                        Text("冇可以預覽嘅 Notification", color = WebMuted)
                    }
                    if (pageCount > 1) {
                        Text("第 ${notificationPage + 1} / $pageCount 頁（每頁 100 個）", color = WebAccent2, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { notificationPage-- }, enabled = notificationPage > 0,
                                modifier = Modifier.weight(1f)) { Text("‹ 上一頁") }
                            OutlinedButton(onClick = { notificationPage++ }, enabled = notificationPage + 1 < pageCount,
                                modifier = Modifier.weight(1f)) { Text("下一頁 ›") }
                        }
                    }
                    val now = Instant.now()
                    visibleNotifications.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.at.atZone(HkTime.zone).format(simulationDateTimeFormat),
                                    color = if (item.at.isBefore(now)) WebMuted else WebWarm,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(item.title + if (item.at.isBefore(now)) " · 已過" else "",
                                    color = WebInk, fontSize = 12.sp)
                                Text(item.description, color = WebMuted, fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { requestTest(item) }) { Text("Test", fontSize = 11.sp) }
                        }
                        HorizontalDivider(color = WebLine.copy(alpha = .45f))
                    }
                }
            }
            Text("Test 會立即用正式 Reminder Notification Channel 發一則測試通知；唔會新增鬧鐘或改動 Task Completion。聲音、震動及彈出方式由系統 Channel／勿擾設定控制。",
                color = WebMuted, fontSize = 11.sp)
        }
        testMessage?.let {
            Text(it, color = if (it.contains("失敗") || it.contains("未") || it.contains("關閉")) WebWarm else WebGood,
                fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp))
        }
        HorizontalDivider(color = WebLine)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回編輯") }
            Button(onClick = { firstTest?.let(::requestTest) }, enabled = firstTest != null,
                modifier = Modifier.weight(1.4f),
                colors = ButtonDefaults.buttonColors(containerColor = WebAccent)) {
                Text("Test Notification Now", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SimulationSection(
    title: String, subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = WebCard, border = BorderStroke(1.dp, WebLine)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = WebInk, fontSize = 15.sp, fontWeight = FontWeight.Black)
            if (subtitle != null) Text(subtitle, color = WebMuted, fontSize = 11.sp)
            content()
        }
    }
}
