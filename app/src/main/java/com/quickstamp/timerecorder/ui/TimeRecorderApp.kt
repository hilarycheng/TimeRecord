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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            modifier = Modifier
                .fillMaxSize()
                .background(WebBg)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionTile("返工", Modifier.weight(1f), onClick = { recordAction("返工") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("返工", timestamp = it, manual = true) }
                    })
                    ActionTile("放工", Modifier.weight(1f), onClick = { recordAction("放工") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("放工", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BusTile("38", Modifier.weight(1f), onClick = { recordAction("上 38", EventKind.BUS, "38") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("上 38", EventKind.BUS, "38", timestamp = it, manual = true) }
                    })
                    BusTile("42C", Modifier.weight(1f), onClick = { recordAction("上 42C", EventKind.BUS, "42C") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("上 42C", EventKind.BUS, "42C", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionTile("到餐廳", Modifier.weight(1f), onClick = { recordAction("到餐廳") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("到餐廳", timestamp = it, manual = true) }
                    })
                    ActionTile("到公司", Modifier.weight(1f), onClick = { recordAction("到公司") }, onLongClick = {
                        showTimePicker(context, viewedDate, now) { recordAction("到公司", timestamp = it, manual = true) }
                    })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
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
                    SmallAction("其他事", Modifier.weight(1f), UtilityStyle.Warm) { recordAction("其他事", EventKind.EXTRA) }
                    SmallAction("補記", Modifier.weight(1f), UtilityStyle.Accent) { backfillOpen = true }
                    SmallAction("Undo", Modifier.weight(0.72f), UtilityStyle.Neutral) { AppStore.undo(context, viewedDate); reload() }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("Timeline", fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text("長按可修改", fontSize = 10.sp, color = WebMuted)
                }
            }

            item {
                TimelineCard(
                    events = events,
                    viewedDate = viewedDate,
                    now = now,
                    showSeconds = state.settings.showSeconds,
                    onLongClick = { editing = it },
                )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, modifier = Modifier.size(width = 58.dp, height = 50.dp)) {
                Text("‹", fontSize = 31.sp, color = Color(0xFFC8CED9))
            }
            Text(
                HkTime.displayDate(date),
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = {}, onLongClick = onPickDate),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFE7EAF0),
            )
            TextButton(onClick = onNext, modifier = Modifier.size(width = 58.dp, height = 50.dp)) {
                Text("›", fontSize = 31.sp, color = Color(0xFFC8CED9))
            }
        }
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

    WebGradientCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(station, fontSize = 12.sp, color = Color(0xFFC9CFDA), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (tracking) {
                    Surface(shape = RoundedCornerShape(99.dp), color = WebBusSoft, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF265157))) {
                        Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF9AF1E5), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            EtaLine("38", snapshot, now)
            Spacer(Modifier.height(5.dp))
            EtaLine("42C", snapshot, now)
            Spacer(Modifier.height(7.dp))
            Text(
                status,
                fontSize = 10.sp,
                color = when {
                    snapshot.refreshingStartedAt > 0L -> Color(0xFF9AF1E5)
                    snapshot.errorAt > snapshot.updatedAt -> Color(0xFFFF9AAA)
                    else -> WebMuted
                },
            )
        }
    }
}

@Composable
private fun EtaLine(route: String, snapshot: EtaSnapshot, now: Long) {
    val arrivals = snapshot.routes[route].orEmpty().filter { it.timestamp >= now - 60_000L }.take(3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(route, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(78.dp), color = WebBus)
        Text(
            if (arrivals.isEmpty()) "—" else arrivals.joinToString("   ") { etaCountdown(it.timestamp, now) },
            fontSize = 27.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFFD0FFF8),
            maxLines = 1,
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
private fun WebGradientCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(WebCard2, WebCard)))
            .border(1.dp, WebLine, shape)
    ) {
        content()
    }
}

@Composable
private fun ActionTile(text: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    WebGradientCard(modifier = modifier.height(88.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick), shape = shape) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.Black, color = WebInk)
        }
    }
}

@Composable
private fun BusTile(text: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .height(96.dp)
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF183236), Color(0xFF102326))))
            .border(1.dp, Color(0xFF265157), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(text, fontSize = 31.sp, fontWeight = FontWeight.Black, color = Color(0xFFA5F5E9))
            Text("上車", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF77CBBF))
        }
    }
}

private enum class UtilityStyle { Warm, Accent, Neutral }

@Composable
private fun SmallAction(text: String, modifier: Modifier, style: UtilityStyle, onClick: () -> Unit) {
    val (bg, fg, border) = when (style) {
        UtilityStyle.Warm -> Triple(WebWarmSoft, Color(0xFFFFD38E), Color(0xFF44351E))
        UtilityStyle.Accent -> Triple(WebAccentSoft, Color(0xFFC9C3FF), Color(0xFF39335F))
        UtilityStyle.Neutral -> Triple(WebCard, Color(0xFFAEB5C3), WebLine)
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(43.dp),
        shape = RoundedCornerShape(13.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Black, color = fg)
        }
    }
}

@Composable
private fun TimelineCard(
    events: List<RecorderEvent>,
    viewedDate: LocalDate,
    now: Long,
    showSeconds: Boolean,
    onLongClick: (RecorderEvent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(18.dp), clip = false),
        shape = RoundedCornerShape(18.dp),
        color = WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        if (events.isEmpty()) {
            Text(
                "未有記錄",
                color = WebMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            )
        } else {
            Column {
                events.forEachIndexed { index, event ->
                    val next = events.getOrNull(index + 1)
                    TimelineRow(
                        event = event,
                        duration = durationLabel(event, next, viewedDate, now),
                        showSeconds = showSeconds,
                        onLongClick = { onLongClick(event) },
                    )
                    if (index != events.lastIndex) HorizontalDivider(color = WebLine, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(event: RecorderEvent, duration: String, showSeconds: Boolean, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            HkTime.formatTime(event.timestamp, showSeconds),
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(if (showSeconds) 86.dp else 68.dp),
            color = WebInk,
        )
        Canvas(Modifier.size(7.dp)) {
            drawCircle(
                color = when (event.kind) {
                    EventKind.BUS -> WebBus
                    EventKind.EXTRA -> WebWarm
                    else -> WebAccent
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(event.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (event.auto) {
                Spacer(Modifier.width(5.dp))
                Surface(shape = RoundedCornerShape(99.dp), color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF353C4B))) {
                    Text("AUTO", fontSize = 8.sp, color = Color(0xFFB8BECB), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
        Text(
            duration,
            fontSize = 11.sp,
            fontWeight = if (event.terminal) FontWeight.Black else FontWeight.Medium,
            color = if (event.terminal) Color(0xFFBDB6FF) else Color(0xFFAEB6C7),
            textAlign = TextAlign.End,
        )
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
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.84f),
        drawerContainerColor = Color(0xFF12161E),
        drawerContentColor = WebInk,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("顯示秒數", fontWeight = FontWeight.Bold)
                    Text("關閉都會繼續保存秒數", fontSize = 10.sp, color = WebMuted)
                }
                Switch(checked = state.settings.showSeconds, onCheckedChange = onSeconds)
            }
            HorizontalDivider(color = WebLine)
            Text("ETA", fontSize = 11.sp, color = WebMuted, fontWeight = FontWeight.Black)
            Text("返工：德福花園\n放工：屏麗徑南行\n38 / 42C · 官方 KMB ETA", fontSize = 12.sp, lineHeight = 19.sp, color = Color(0xFFD3D7DF))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = WebBusSoft, contentColor = Color(0xFFA5F5E9)),
            ) { Text("立即更新 ETA", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC9C3FF))) {
                Text("巴士歷史 / Pattern", fontWeight = FontWeight.Bold)
            }
            if (state.tracking.active) {
                OutlinedButton(onClick = onStopTracking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebDanger)) {
                    Text("停止 ETA Tracking")
                }
            }
            Text("左邊緣向右 Swipe 可再次打開此頁。", fontSize = 10.sp, color = WebMuted)
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
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebDanger)) { Text("Delete") }
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
                HorizontalDivider(color = WebLine)
                HistorySection("放工", CommuteMode.HOME, state.events)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HistorySection(title: String, mode: CommuteMode, events: List<RecorderEvent>) {
    Text(title, fontWeight = FontWeight.Black, fontSize = 17.sp)
    listOf("38", "42C").forEach { route ->
        val bus = events.filter { it.kind == EventKind.BUS && it.route == route && (it.commute ?: HkTime.modeAt(it.timestamp)) == mode }.sortedBy { it.timestamp }
        val minutes = bus.map { HkTime.minuteOfDay(it.timestamp).toDouble() }.sorted()
        val durations = bus.mapNotNull { b ->
            val next = events.filter { it.timestamp > b.timestamp && HkTime.date(it.timestamp) == HkTime.date(b.timestamp) }.minByOrNull { it.timestamp }
            next?.let { it.timestamp - b.timestamp }
        }.sorted()
        val medianMinute = median(minutes)
        val medianDuration = medianLong(durations)
        Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF11151C), border = androidx.compose.foundation.BorderStroke(1.dp, WebLine), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(11.dp)) {
                Text(route, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFFA5F5E9))
                Text(
                    if (bus.isEmpty()) "未有資料" else "典型上車 ${HkTime.formatMinuteOfDay(medianMinute)} · 車程 ${HkTime.formatDuration(medianDuration)} · ${bus.size} trips",
                    fontSize = 10.sp,
                    color = Color(0xFFAEB5C3),
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
    val min = (values.minOrNull() ?: 0f) - 5f
    val max = (values.maxOrNull() ?: 1f) + 5f
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val width = size.width
        val height = size.height
        drawLine(WebLine, Offset(0f, height - 8f), Offset(width, height - 8f), strokeWidth = 2f, cap = StrokeCap.Round)
        values.forEachIndexed { index, value ->
            val x = if (values.size <= 1) width / 2f else index * width / (values.size - 1f)
            val ratio = if (max <= min) 0.5f else ((value - min) / (max - min)).coerceIn(0f, 1f)
            val y = height - 10f - ratio * (height - 20f)
            drawCircle(WebBus, radius = 4.5f, center = Offset(x, y))
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
