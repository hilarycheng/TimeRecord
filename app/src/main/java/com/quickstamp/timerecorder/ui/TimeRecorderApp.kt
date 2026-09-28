@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EtaSnapshot
import com.quickstamp.timerecorder.model.EventKind
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.model.RecorderEvent
import com.quickstamp.timerecorder.model.RecorderState
import com.quickstamp.timerecorder.network.KmbEtaClient
import com.quickstamp.timerecorder.service.EtaForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.ceil

@Composable
fun TimeRecorderApp() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(AppStore.load(context)) }
    var now by remember { mutableLongStateOf(HkTime.now()) }
    var viewedDate by remember { mutableStateOf(HkTime.today()) }
    var lastVersion by remember { mutableStateOf(AppStore.version(context)) }
    var editing by remember { mutableStateOf<RecorderEvent?>(null) }
    var renameTarget by remember { mutableStateOf<RecorderEvent?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var backfillOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val io = remember { Executors.newSingleThreadExecutor() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(Unit) {
        onDispose { io.shutdownNow() }
    }

    fun reload() {
        state = AppStore.load(context)
        lastVersion = AppStore.version(context)
    }

    fun refreshEta(mode: CommuteMode) {
        io.execute { KmbEtaClient.refresh(context.applicationContext, mode) }
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun recordAction(
        label: String,
        kind: EventKind = EventKind.DEFAULT,
        route: String? = null,
        terminal: Boolean = false,
        timestamp: Long = HkTime.now(),
        manual: Boolean = false,
    ) {
        val commute = when {
            kind == EventKind.BUS -> state.tracking.mode ?: HkTime.modeAt(timestamp, state.settings.morningCutoffHour)
            else -> null
        }
        AppStore.record(context, label, kind, timestamp, route, commute, terminal)
        when {
            !manual && label == "返工" -> {
                ensureNotificationPermission()
                runCatching { EtaForegroundService.start(context, CommuteMode.WORK) }
            }
            !manual && label == "放工" -> {
                ensureNotificationPermission()
                runCatching { EtaForegroundService.start(context, CommuteMode.HOME) }
            }
            !manual && kind == EventKind.BUS -> runCatching { EtaForegroundService.stop(context) }
            !manual && terminal -> runCatching { EtaForegroundService.stop(context) }
        }
        reload()
    }

    LaunchedEffect(Unit) {
        var lastDay = HkTime.today()
        while (true) {
            delay(1_000L)
            now = HkTime.now()
            val day = HkTime.today(now)
            if (day != lastDay) {
                AppStore.autoClosePastDays(context, now)
                viewedDate = day
                lastDay = day
            }
            val version = AppStore.version(context)
            if (version != lastVersion) {
                state = AppStore.load(context)
                lastVersion = version
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            SettingsDrawer(
                state = state,
                onSeconds = { AppStore.setShowSeconds(context, it); reload() },
                onRefresh = {
                    val mode = state.tracking.mode ?: HkTime.modeAt(now, state.settings.morningCutoffHour)
                    refreshEta(mode)
                    scope.launch { drawerState.close() }
                },
                onHistory = { historyOpen = true; scope.launch { drawerState.close() } },
                onStopTracking = {
                    EtaForegroundService.stop(context)
                    reload()
                },
            )
        }
    ) {
        val mode = state.tracking.mode ?: HkTime.modeAt(now, state.settings.morningCutoffHour)
        val snapshot = if (mode == CommuteMode.WORK) state.etaWork else state.etaHome
        val events = state.events.filter { HkTime.date(it.timestamp) == viewedDate }.sortedBy { it.timestamp }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DateHeader(
                    date = viewedDate,
                    onPrevious = { viewedDate = viewedDate.minusDays(1) },
                    onNext = { if (viewedDate < HkTime.today(now)) viewedDate = viewedDate.plusDays(1) },
                    onPickDate = { showDatePicker(context, viewedDate) { viewedDate = it } },
                )
            }

            item { EtaPanel(mode = mode, snapshot = snapshot, now = now, tracking = state.tracking.active) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("返工", Modifier.weight(1f), onClick = { recordAction("返工") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("返工", timestamp = it, manual = true) }
                    })
                    ActionTile("放工", Modifier.weight(1f), onClick = { recordAction("放工") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("放工", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BusTile("38", Modifier.weight(1f), onClick = { recordAction("上 38", EventKind.BUS, "38") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("上 38", EventKind.BUS, "38", timestamp = it, manual = true) }
                    })
                    BusTile("42C", Modifier.weight(1f), onClick = { recordAction("上 42C", EventKind.BUS, "42C") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("上 42C", EventKind.BUS, "42C", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("到餐廳", Modifier.weight(1f), onClick = { recordAction("到餐廳") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("到餐廳", timestamp = it, manual = true) }
                    })
                    ActionTile("到公司", Modifier.weight(1f), onClick = { recordAction("到公司") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("到公司", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("落車", Modifier.weight(1f), onClick = { recordAction("落車") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("落車", timestamp = it, manual = true) }
                    })
                    ActionTile("到屋企", Modifier.weight(1f), onClick = { recordAction("到屋企", terminal = true) }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("到屋企", terminal = true, timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction("其他事", Modifier.weight(1f)) { recordAction("其他事", EventKind.EXTRA) }
                    SmallAction("補記", Modifier.weight(1f)) { backfillOpen = true }
                    SmallAction("Undo", Modifier.weight(1f)) { AppStore.undo(context, viewedDate); reload() }
                }
            }

            item {
                Text(
                    text = "Timeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (events.isEmpty()) {
                item {
                    Text("未有記錄", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
            } else {
                items(events, key = { it.id }) { event ->
                    val index = events.indexOf(event)
                    val next = events.getOrNull(index + 1)
                    TimelineRow(
                        event = event,
                        duration = durationLabel(event, next, viewedDate, now),
                        showSeconds = state.settings.showSeconds,
                        onLongClick = { editing = event },
                    )
                }
            }
        }
    }

    editing?.let { event ->
        EntryActionsDialog(
            event = event,
            onDismiss = { editing = null },
            onRename = { editing = null; renameTarget = event },
            onTime = {
                editing = null
                showTimePicker(context, HkTime.date(event.timestamp), event.timestamp) { ts ->
                    AppStore.updateEvent(context, event.copy(timestamp = ts)); reload()
                }
            },
            onDelete = { AppStore.deleteEvent(context, event.id); editing = null; reload() },
        )
    }

    renameTarget?.let { event ->
        RenameDialog(
            initial = event.label,
            onDismiss = { renameTarget = null },
            onSave = { name -> AppStore.updateEvent(context, event.copy(label = name)); renameTarget = null; reload() },
        )
    }

    if (backfillOpen) {
        BackfillDialog(
            onDismiss = { backfillOpen = false },
            onSelect = { label, kind, route, terminal ->
                backfillOpen = false
                showTimePicker(context, viewedDate, now) { ts ->
                    recordAction(label, kind, route, terminal, ts, manual = true)
                }
            }
        )
    }

    if (historyOpen) {
        BusHistoryDialog(state = state, onDismiss = { historyOpen = false })
    }
}

@Composable
private fun DateHeader(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious, modifier = Modifier.size(width = 58.dp, height = 48.dp)) { Text("‹", fontSize = 32.sp) }
        Text(
            HkTime.displayDate(date),
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = {}, onLongClick = onPickDate),
            textAlign = TextAlign.Center,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onNext, modifier = Modifier.size(width = 58.dp, height = 48.dp)) { Text("›", fontSize = 32.sp) }
    }
}

@Composable
private fun EtaPanel(mode: CommuteMode, snapshot: EtaSnapshot, now: Long, tracking: Boolean) {
    val station = if (mode == CommuteMode.WORK) "德福花園 · 返工" else "屏麗徑南行 · 放工"
    val status = when {
        snapshot.refreshingStartedAt > 0L -> "Refreshing…"
        snapshot.errorAt > snapshot.updatedAt -> "Stale · ${snapshot.errorMessage ?: "更新失敗"}"
        snapshot.updatedAt > 0L -> "Updated ${HkTime.formatTime(snapshot.updatedAt, false)}"
        else -> "未有 ETA"
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(station, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (tracking) Text("LIVE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(7.dp))
            EtaLine("38", snapshot, now)
            Spacer(Modifier.height(3.dp))
            EtaLine("42C", snapshot, now)
            Spacer(Modifier.height(6.dp))
            Text(status, fontSize = 12.sp, color = if (snapshot.errorAt > snapshot.updatedAt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EtaLine(route: String, snapshot: EtaSnapshot, now: Long) {
    val arrivals = snapshot.routes[route].orEmpty().filter { it.timestamp >= now - 60_000L }.take(3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(route, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(70.dp), color = MaterialTheme.colorScheme.secondary)
        Text(
            if (arrivals.isEmpty()) "—" else arrivals.joinToString("   ") { etaCountdown(it.timestamp, now) },
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun etaCountdown(timestamp: Long, now: Long): String {
    val remaining = timestamp - now
    return when {
        remaining <= 30_000L -> "Due"
        else -> "${ceil(remaining / 60_000.0).toInt()}m"
    }
}

@Composable
private fun ActionTile(text: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        modifier = modifier.height(68.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BusTile(text: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        modifier = modifier.height(82.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 31.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SmallAction(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun TimelineRow(event: RecorderEvent, duration: String, showSeconds: Boolean, onLongClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(HkTime.formatTime(event.timestamp, showSeconds), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(if (showSeconds) 90.dp else 68.dp))
            Column(Modifier.weight(1f)) {
                Text(event.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (event.auto) Text("AUTO", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
            Text(duration, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (event.terminal) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun durationLabel(event: RecorderEvent, next: RecorderEvent?, viewedDate: LocalDate, now: Long): String {
    if (event.terminal) return "Ending"
    if (next != null) return HkTime.formatDuration(next.timestamp - event.timestamp)
    return if (viewedDate == HkTime.today(now)) HkTime.formatDuration(now - event.timestamp) else "—"
}

@Composable
private fun SettingsDrawer(
    state: RecorderState,
    onSeconds: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onHistory: () -> Unit,
    onStopTracking: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.82f)) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("顯示秒數", modifier = Modifier.weight(1f))
                Switch(checked = state.settings.showSeconds, onCheckedChange = onSeconds)
            }
            HorizontalDivider()
            Text("ETA", fontWeight = FontWeight.Bold)
            Text("返工：德福花園\n放工：屏麗徑南行\n38 / 42C · 官方 KMB ETA", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("立即更新 ETA") }
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("巴士歷史 / Pattern") }
            if (state.tracking.active) {
                OutlinedButton(onClick = onStopTracking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("停止 ETA Tracking")
                }
            }
            Text("左邊緣向右 Swipe 可再次打開此頁。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EntryActionsDialog(event: RecorderEvent, onDismiss: () -> Unit, onRename: () -> Unit, onTime: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRename, modifier = Modifier.fillMaxWidth()) { Text("改名稱") }
                Button(onClick = onTime, modifier = Modifier.fillMaxWidth()) { Text("改時間") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改名稱") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BackfillDialog(onDismiss: () -> Unit, onSelect: (String, EventKind, String?, Boolean) -> Unit) {
    val entries = listOf(
        Backfill("返工"), Backfill("上 38", EventKind.BUS, "38"), Backfill("上 42C", EventKind.BUS, "42C"),
        Backfill("到餐廳"), Backfill("到公司"), Backfill("放工"), Backfill("落車"), Backfill("到屋企", terminal = true), Backfill("其他事", EventKind.EXTRA),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("補記") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                entries.forEach { e ->
                    TextButton(onClick = { onSelect(e.label, e.kind, e.route, e.terminal) }, modifier = Modifier.fillMaxWidth()) {
                        Text(e.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class Backfill(val label: String, val kind: EventKind = EventKind.DEFAULT, val route: String? = null, val terminal: Boolean = false)

@Composable
private fun BusHistoryDialog(state: RecorderState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("38 / 42C History") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HistorySection("返工", CommuteMode.WORK, state.events)
                HorizontalDivider()
                HistorySection("放工", CommuteMode.HOME, state.events)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HistorySection(title: String, mode: CommuteMode, events: List<RecorderEvent>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    listOf("38", "42C").forEach { route ->
        val bus = events.filter { it.kind == EventKind.BUS && it.route == route && (it.commute ?: HkTime.modeAt(it.timestamp)) == mode }.sortedBy { it.timestamp }
        val minutes = bus.map { HkTime.minuteOfDay(it.timestamp).toDouble() }.sorted()
        val durations = bus.mapNotNull { b ->
            val next = events.filter { it.timestamp > b.timestamp && HkTime.date(it.timestamp) == HkTime.date(b.timestamp) }.minByOrNull { it.timestamp }
            next?.let { it.timestamp - b.timestamp }
        }.sorted()
        val medianMinute = median(minutes)
        val medianDuration = medianLong(durations)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(route, fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                Text(
                    if (bus.isEmpty()) "未有資料" else "典型上車 ${HkTime.formatMinuteOfDay(medianMinute)} · 車程 ${HkTime.formatDuration(medianDuration)} · ${bus.size} trips",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bus.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    BoardingPatternChart(bus.takeLast(24))
                }
            }
        }
    }
}

@Composable
private fun BoardingPatternChart(events: List<RecorderEvent>) {
    val values = events.map { HkTime.minuteOfDay(it.timestamp).toFloat() }
    val axisColor = MaterialTheme.colorScheme.outline
    val dotColor = MaterialTheme.colorScheme.secondary
    val min = (values.minOrNull() ?: 0f) - 5f
    val max = (values.maxOrNull() ?: 1f) + 5f
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val width = size.width
        val height = size.height
        drawLine(axisColor, Offset(0f, height - 8f), Offset(width, height - 8f), strokeWidth = 2f, cap = StrokeCap.Round)
        values.forEachIndexed { index, value ->
            val x = if (values.size <= 1) width / 2f else index * width / (values.size - 1f)
            val ratio = if (max <= min) 0.5f else ((value - min) / (max - min)).coerceIn(0f, 1f)
            val y = height - 10f - ratio * (height - 20f)
            drawCircle(dotColor, radius = 4.5f, center = Offset(x, y))
        }
    }
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val middle = values.size / 2
    return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
}

private fun medianLong(values: List<Long>): Long {
    if (values.isEmpty()) return 0L
    val middle = values.size / 2
    return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2L
}

private fun showTimePicker(context: android.content.Context, date: LocalDate, initialTimestamp: Long, onPicked: (Long) -> Unit) {
    val time = Instant.ofEpochMilli(initialTimestamp).atZone(HkTime.zone)
    TimePickerDialog(context, { _, hour, minute -> onPicked(HkTime.at(date, hour, minute, 0)) }, time.hour, time.minute, true).show()
}

private fun showDatePicker(context: android.content.Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
