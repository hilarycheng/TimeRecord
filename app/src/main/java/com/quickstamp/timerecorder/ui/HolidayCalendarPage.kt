package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.alarm.AlarmClockScheduler
import com.quickstamp.timerecorder.data.HolidayCalendarCache
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.data.PrivateReminder
import com.quickstamp.timerecorder.data.PublicHoliday
import com.quickstamp.timerecorder.data.ReminderRule
import com.quickstamp.timerecorder.data.ReminderSource
import com.quickstamp.timerecorder.data.ReminderStore
import com.quickstamp.timerecorder.data.ReminderTask
import com.quickstamp.timerecorder.data.SystemCalendarEvent
import com.quickstamp.timerecorder.data.SystemCalendarReader
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.reminder.ReminderEngine
import com.quickstamp.timerecorder.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HolidayCalendarPage(
    context: Context,
    onBack: () -> Unit,
) {
    var cache by remember { mutableStateOf(HolidayCalendarStore.load(context)) }
    var month by remember { mutableStateOf(YearMonth.from(HkTime.today())) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var rulesOpen by remember { mutableStateOf(false) }
    var addPrivateOpen by remember { mutableStateOf(false) }
    var editingPrivate by remember { mutableStateOf<PrivateReminder?>(null) }
    var undoCompleted by remember { mutableStateOf<ReminderTask?>(null) }
    var localRevision by remember { mutableIntStateOf(ReminderStore.version(context)) }
    var rules by remember { mutableStateOf(ReminderStore.rules(context)) }
    var privateReminders by remember { mutableStateOf(ReminderStore.privateReminders(context)) }
    var systemEvents by remember { mutableStateOf<List<SystemCalendarEvent>>(emptyList()) }
    var monthTasks by remember { mutableStateOf<List<ReminderTask>>(emptyList()) }
    var calendarGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    var notificationGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    var exactGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
    }
    val scope = rememberCoroutineScope()

    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
        localRevision += 1
        scope.launch(Dispatchers.IO) { runCatching { ReminderScheduler.scheduleAll(context.applicationContext) } }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }
    val exactPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exactGranted = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        scope.launch(Dispatchers.IO) { ReminderScheduler.scheduleAll(context.applicationContext) }
    }

    suspend fun refreshOfficial() {
        if (refreshing) return
        refreshing = true
        cache = withContext(Dispatchers.IO) { HolidayCalendarStore.refresh(context.applicationContext) }
        runCatching { AlarmClockScheduler.scheduleAll(context.applicationContext) }
        scope.launch(Dispatchers.IO) { runCatching { ReminderScheduler.scheduleAll(context.applicationContext) } }
        localRevision += 1
        refreshing = false
    }

    fun localChanged() {
        localRevision = ReminderStore.version(context)
        scope.launch(Dispatchers.IO) { runCatching { ReminderScheduler.scheduleAll(context.applicationContext) } }
    }

    LaunchedEffect(Unit) {
        if (HolidayCalendarStore.shouldRefresh(cache)) refreshOfficial()
    }

    LaunchedEffect(month, calendarGranted, localRevision) {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val loaded = withContext(Dispatchers.IO) {
            val r = ReminderStore.rules(context)
            val p = ReminderStore.privateReminders(context)
            val e = if (calendarGranted) SystemCalendarReader.events(context, start, end) else emptyList()
            val t = ReminderEngine.tasksInRange(context, start, end)
            arrayOf(r, p, e, t)
        }
        @Suppress("UNCHECKED_CAST")
        run {
            rules = loaded[0] as List<ReminderRule>
            privateReminders = loaded[1] as List<PrivateReminder>
            systemEvents = loaded[2] as List<SystemCalendarEvent>
            monthTasks = loaded[3] as List<ReminderTask>
        }
    }

    if (rulesOpen) {
        ReminderRulesPage(
            context = context,
            onBack = { rulesOpen = false; localChanged() },
            onChanged = { localChanged() },
        )
        return
    }

    val byDate = cache.holidays.groupBy { it.date }
    val systemByDate = systemEvents.groupBy { it.date }
    val privateByDate = privateReminders.groupBy { it.anchorDate }
    val taskByDate = monthTasks.groupBy { it.taskDate }
    val selected = selectedDate
    val selectedHolidays = selected?.let { byDate[it].orEmpty() }.orEmpty()
    val selectedEvents = selected?.let { systemByDate[it].orEmpty() }.orEmpty()
    val selectedPrivate = selected?.let { privateByDate[it].orEmpty() }.orEmpty()
    val selectedTasks = selected?.let { taskByDate[it].orEmpty() }.orEmpty()

    Column(
        Modifier.fillMaxSize().background(WebBg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onBack, shape = RoundedCornerShape(14.dp), color = Color(0xFF131A26), border = BorderStroke(1.dp, Color(0xFF243044))) {
                Text("‹", modifier = Modifier.padding(horizontal = 17.dp, vertical = 8.dp), fontSize = 27.sp, color = WebInk, fontWeight = FontWeight.Bold)
            }
            Text("Calendar", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Black, color = WebInk)
            Spacer(Modifier.width(50.dp))
        }

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFF131A26), border = BorderStroke(1.dp, Color(0xFF273449))) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MonthArrow("‹") { month = month.minusMonths(1); selectedDate = null }
                    Text("${month.year}年${month.monthValue}月", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.Black, color = WebInk)
                    MonthArrow("›") { month = month.plusMonths(1); selectedDate = null }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, label ->
                        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (index == 0) WebDanger else WebMuted)
                    }
                }
                MonthGrid(
                    month = month,
                    holidays = byDate,
                    systemEvents = systemByDate,
                    privateReminders = privateByDate,
                    taskDates = taskByDate.keys,
                    selectedDate = selectedDate,
                    onSelect = { selectedDate = it },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CalendarLegend("假", Color(0xFFFF9EC6), "公眾假期", Modifier.weight(1f))
                    CalendarLegend("•", WebBus, "Calendar", Modifier.weight(1f))
                    CalendarLegend("◆", WebAccent2, "Private", Modifier.weight(1f))
                    CalendarLegend("!", WebWarm, "必做", Modifier.weight(1f))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (selectedDate == null) selectedDate = HkTime.today()
                    addPrivateOpen = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = WebAccentSoft, contentColor = Color(0xFFE7E1FF)),
            ) { Text("+ 私人事項", fontWeight = FontWeight.Black) }
            OutlinedButton(onClick = { rulesOpen = true }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, WebAccent.copy(alpha = .6f))) {
                Text("Reminder Rules", color = WebAccent2, fontWeight = FontWeight.Black)
            }
        }

        if (!calendarGranted) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("System Calendar（只讀）", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("授權後可喺同一個月曆睇 Calendar event，並用 keyword rule 自動計提醒。App 唔會修改 Google/System Calendar。", color = WebMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    Button(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.fillMaxWidth()) { Text("Grant Calendar Access", fontWeight = FontWeight.Black) }
                }
            }
        }

        if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Reminder Notifications", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("要喺指定日期自動提醒，需要 Notification permission。", color = WebMuted, fontSize = 10.sp)
                    Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Grant Notifications", fontWeight = FontWeight.Black) }
                }
            }
        }

        if (!exactGranted && Build.VERSION.SDK_INT >= 31) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = WebCard, border = BorderStroke(1.dp, WebWarm)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("精確通知權限未開", color = WebWarm, fontWeight = FontWeight.Black)
                    Text("指定 09:00／H-12 等通知可能被 Android 延遲。請開啟 Alarms & reminders 特別權限。", color = WebMuted, fontSize = 11.sp)
                    OutlinedButton(onClick = {
                        exactPermission.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }, modifier = Modifier.fillMaxWidth()) { Text("開啟精確通知權限") }
                }
            }
        }

        if (selected != null) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, if (selectedTasks.isNotEmpty()) WebWarm.copy(alpha = .65f) else if (selectedHolidays.isNotEmpty()) WebAccent else WebLine)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(selected.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEE", Locale.TRADITIONAL_CHINESE)), fontSize = 12.sp, color = WebMuted, fontWeight = FontWeight.Bold)
                    if (selectedHolidays.isEmpty() && selectedEvents.isEmpty() && selectedPrivate.isEmpty() && selectedTasks.isEmpty()) {
                        Text("無特別事項", fontSize = 14.sp, color = WebInk, fontWeight = FontWeight.Bold)
                    }
                    selectedHolidays.forEach { Text("假 · ${it.name}", fontSize = 14.sp, color = Color(0xFFFF9EC6), fontWeight = FontWeight.Black) }
                    selectedEvents.forEach { e ->
                        val time = if (e.allDay) "Full Day" else "${e.startAt?.toLocalTime()}–${e.endAt?.toLocalTime()}"
                        Text("● ${e.title} · $time · ${e.calendarName}", fontSize = 12.sp, color = WebBus, fontWeight = FontWeight.Bold)
                    }
                    selectedPrivate.forEach { reminder ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editingPrivate = reminder }) {
                                Text("◆ ${reminder.title} · ${if (reminder.startTime == null) "Full Day" else "${reminder.startTime}–${reminder.endTime}"}",
                                    fontSize = 13.sp, color = WebAccent2, fontWeight = FontWeight.Black)
                                val rule = rules.firstOrNull { it.id == reminder.ruleId }
                                if (rule != null) Text(ruleSummary(rule), fontSize = 9.sp, color = WebMuted)
                            }
                            TextButton(onClick = { ReminderStore.deletePrivateReminder(context, reminder.id); localChanged() }) { Text("刪除", color = WebDanger, fontSize = 10.sp) }
                        }
                    }
                    selectedTasks.forEach { task ->
                        Surface(shape = RoundedCornerShape(12.dp), color = WebWarmSoft, border = BorderStroke(1.dp, WebWarm.copy(alpha = .35f))) {
                            Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("! ${task.title}", color = WebInk, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                    Text("${task.ruleName} · ${ReminderEngine.displayStatus(task, selected)}", color = WebWarm, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    if (task.windowStart != null) {
                                        Text("合資格日期：${ReminderEngine.eligibleDatesSummary(task)}", color = WebBus, fontSize = 10.sp)
                                    }
                                }
                                Button(
                                    enabled = (task.windowStart == null || task.eligibleDates.isNotEmpty()) &&
                                        !task.taskDate.isAfter(HkTime.today()) &&
                                        (task.taskDate != HkTime.today() || task.canCompleteFrom == null ||
                                            !Instant.now().isBefore(task.canCompleteFrom)),
                                    onClick = { scope.launch(Dispatchers.IO) {
                                        ReminderScheduler.complete(context, task.taskKey)
                                        withContext(Dispatchers.Main) {
                                            undoCompleted = task
                                            localChanged()
                                        }
                                    } },
                                    colors = ButtonDefaults.buttonColors(containerColor = WebGoodSoft, contentColor = WebGood),
                                ) { Text("完成", fontWeight = FontWeight.Black, fontSize = 10.sp) }
                            }
                        }
                    }
                    undoCompleted?.let { completed ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("已完成：${completed.title}", color = WebGood, modifier = Modifier.weight(1f), fontSize = 11.sp)
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ReminderScheduler.undoComplete(context, completed.taskKey)
                                    withContext(Dispatchers.Main) { undoCompleted = null; localChanged() }
                                }
                            }) { Text("Undo", color = WebAccent2) }
                        }
                    }
                }
            }
        }

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(HolidayCalendarStore.sourceName, fontSize = 12.sp, fontWeight = FontWeight.Black, color = WebInk)
                InfoLine("資料更新", formatCacheTime(cache.displayUpdatedAt, dateOnly = true))
                InfoLine("最後檢查", formatCacheTime(cache.checkedAt, dateOnly = false))
                if (cache.errorMessage != null) {
                    Text(if (cache.holidays.isEmpty()) "更新失敗 · 尚未有快取資料" else "更新失敗 · 使用快取資料", fontSize = 10.sp, color = WebDanger, fontWeight = FontWeight.Bold)
                } else if (cache.holidays.isEmpty() && !refreshing) {
                    Text("尚未下載假期資料", fontSize = 10.sp, color = WebMuted)
                } else if (refreshing) {
                    Text("正在更新…舊資料會繼續顯示", fontSize = 10.sp, color = WebBus)
                }
                Button(onClick = { scope.launch { refreshOfficial() } }, enabled = !refreshing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WebAccentSoft, contentColor = Color(0xFFE7E1FF))) {
                    Text(if (refreshing) "正在更新…" else "立即更新", fontWeight = FontWeight.Black)
                }
            }
        }
    }

    editingPrivate?.let { item ->
        PrivateReminderDialog(
            context = context, initialDate = item.anchorDate, rules = rules,
            initial = item, onDismiss = { editingPrivate = null },
            onSave = { updated ->
                ReminderStore.upsertPrivateReminder(context, updated)
                editingPrivate = null
                month = YearMonth.from(updated.anchorDate)
                selectedDate = updated.anchorDate
                localChanged()
            },
        )
    }
    if (addPrivateOpen) {
        PrivateReminderDialog(
            context = context,
            initialDate = selectedDate ?: HkTime.today(),
            rules = rules.filter { it.enabled },
            onDismiss = { addPrivateOpen = false },
            onSave = {
                ReminderStore.upsertPrivateReminder(context, it)
                addPrivateOpen = false
                month = YearMonth.from(it.anchorDate)
                selectedDate = it.anchorDate
                localChanged()
            },
        )
    }
}

@Composable
private fun MonthArrow(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = WebCard2, border = BorderStroke(1.dp, WebLine)) {
        Text(label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp), fontSize = 23.sp, color = WebInk, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    holidays: Map<LocalDate, List<PublicHoliday>>,
    systemEvents: Map<LocalDate, List<SystemCalendarEvent>>,
    privateReminders: Map<LocalDate, List<PrivateReminder>>,
    taskDates: Set<LocalDate>,
    selectedDate: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value % 7
    val total = month.lengthOfMonth()
    val today = HkTime.today()
    repeat(6) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { weekday ->
                val index = week * 7 + weekday
                val day = index - offset + 1
                if (day !in 1..total) {
                    Spacer(Modifier.weight(1f).height(52.dp))
                } else {
                    val date = month.atDay(day)
                    val holiday = holidays[date].orEmpty().isNotEmpty()
                    val hasSystem = systemEvents[date].orEmpty().isNotEmpty()
                    val hasPrivate = privateReminders[date].orEmpty().isNotEmpty()
                    val hasTask = date in taskDates
                    val selected = date == selectedDate
                    val isToday = date == today
                    Box(
                        modifier = Modifier.weight(1f).height(52.dp).padding(2.dp)
                            .background(when { selected -> WebAccentSoft; holiday -> Color(0xFF251B32); else -> Color.Transparent }, RoundedCornerShape(11.dp))
                            .then(if (isToday) Modifier.border(1.dp, WebBus, RoundedCornerShape(11.dp)) else if (selected) Modifier.border(1.dp, WebAccent, RoundedCornerShape(11.dp)) else Modifier)
                            .clickable { onSelect(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(day.toString(), fontSize = 13.sp, fontWeight = if (holiday || isToday) FontWeight.Black else FontWeight.Medium, color = when { holiday -> Color(0xFFFF9EC6); weekday == 0 -> WebDanger; else -> WebInk })
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (holiday) Text("假", fontSize = 7.sp, color = Color(0xFFFF9EC6), fontWeight = FontWeight.Black)
                                if (hasSystem) Text("•", fontSize = 10.sp, color = WebBus, fontWeight = FontWeight.Black)
                                if (hasPrivate) Text("◆", fontSize = 7.sp, color = WebAccent2, fontWeight = FontWeight.Black)
                                if (hasTask) Text("!", fontSize = 8.sp, color = WebWarm, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarLegend(symbol: String, color: Color, label: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(symbol, color = color, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(3.dp))
        Text(label, color = WebMuted, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = WebMuted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.sp, color = WebInk, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatCacheTime(timestamp: Long, dateOnly: Boolean): String {
    if (timestamp <= 0L) return "—"
    val z = Instant.ofEpochMilli(timestamp).atZone(HkTime.zone)
    return z.format(if (dateOnly) DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH) else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH))
}
