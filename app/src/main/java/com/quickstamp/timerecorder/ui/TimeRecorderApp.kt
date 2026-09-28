@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.data.RecorderActions
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EtaSnapshot
import com.quickstamp.timerecorder.model.EventKind
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.model.NextAction
import com.quickstamp.timerecorder.model.RecorderAnalytics
import com.quickstamp.timerecorder.model.RecorderEvent
import com.quickstamp.timerecorder.model.RecorderState
import com.quickstamp.timerecorder.model.WorkflowAction
import com.quickstamp.timerecorder.model.WorkflowPlanner
import com.quickstamp.timerecorder.network.KmbEtaClient
import com.quickstamp.timerecorder.service.EtaForegroundService
import com.quickstamp.timerecorder.widget.TimeRecorderWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.ceil

private data class TapFeedback(val key: String, val text: String, val timestamp: Long)
private enum class HistoryRange(val days: Long?) { DAYS_7(7), DAYS_30(30), DAYS_90(90), ALL(null) }

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
    var feedback by remember { mutableStateOf<TapFeedback?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val io = remember { Executors.newSingleThreadExecutor() }
    val snackbar = remember { SnackbarHostState() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(AppStore.exportJson(context)) }
                    ?: error("Cannot open backup file")
            }.onSuccess {
                scope.launch { snackbar.showSnackbar("Backup 已儲存", duration = SnackbarDuration.Short) }
            }.onFailure {
                scope.launch { snackbar.showSnackbar("Backup 失敗：${it.message}", duration = SnackbarDuration.Short) }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Cannot read backup")
                AppStore.restoreJson(context, raw).getOrThrow()
            }
            result.onSuccess {
                state = AppStore.load(context)
                lastVersion = AppStore.version(context)
                runCatching { TimeRecorderWidgetProvider.updateAll(context) }
                scope.launch { snackbar.showSnackbar("Restore 完成", duration = SnackbarDuration.Short) }
            }.onFailure {
                scope.launch { snackbar.showSnackbar("Restore 失敗：${it.message}", duration = SnackbarDuration.Short) }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { io.shutdownNow() } }

    fun reload() {
        state = AppStore.load(context)
        lastVersion = AppStore.version(context)
        runCatching { TimeRecorderWidgetProvider.updateAll(context) }
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
        if (!manual && (label == "返工" || label == "放工")) ensureNotificationPermission()
        val result = RecorderActions.record(context, label, kind, route, terminal, timestamp, manual)
        reload()
        feedback = TapFeedback(route ?: label, "✓ ${HkTime.formatTime(timestamp, true)}", timestamp)
        scope.launch {
            val snackResult = snackbar.showSnackbar(
                message = "已記錄：${route?.let { "上 $it" } ?: label} · ${HkTime.formatTime(timestamp, true)}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (snackResult == SnackbarResult.ActionPerformed) {
                AppStore.deleteEvent(context, result.event.id)
                result.autoStop?.let { AppStore.deleteEvent(context, it.id) }
                if (kind == EventKind.BUS) {
                    result.event.commute?.let { runCatching { EtaForegroundService.start(context, it) } }
                } else if (label == "返工" || label == "放工") {
                    runCatching { EtaForegroundService.stop(context) }
                }
                reload()
            }
        }
    }

    LaunchedEffect(feedback?.timestamp) {
        if (feedback != null) {
            delay(1_600L)
            feedback = null
        }
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
                reload()
                lastDay = day
            }
            val version = AppStore.version(context)
            if (version != lastVersion) {
                state = AppStore.load(context)
                lastVersion = version
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = historyOpen && drawerState.isClosed) {
        historyOpen = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Keep Android's left-edge gesture exclusively for system Back.
        gesturesEnabled = false,
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
                onBackup = {
                    backupLauncher.launch("time-recorder-backup-${HkTime.today()}.json")
                    scope.launch { drawerState.close() }
                },
                onRestore = {
                    restoreLauncher.launch(arrayOf("application/json", "text/plain"))
                    scope.launch { drawerState.close() }
                },
                onStopTracking = {
                    EtaForegroundService.stop(context)
                    reload()
                },
            )
        }
    ) {
        if (historyOpen) {
            HistoryPage(state = state, onBack = { historyOpen = false })
        } else {
        val today = HkTime.today(now)
        val mode = state.tracking.mode ?: HkTime.modeAt(now, state.settings.morningCutoffHour)
        val snapshot = if (mode == CommuteMode.WORK) state.etaWork else state.etaHome
        val events = state.events.filter { HkTime.date(it.timestamp) == viewedDate }.sortedBy { it.timestamp }
        val summary = RecorderAnalytics.daySummary(events, viewedDate)
        val isToday = viewedDate == today
        // Completion is scoped to the viewed calendar day. A new day always starts with controls visible.
        val completedToday = isToday && events.any { it.terminal }
        val showLiveControls = isToday && !completedToday
        val showSmartBar = showLiveControls || !isToday
        val next = WorkflowPlanner.next(state.events, viewedDate, now)

        Box(
            Modifier
                .fillMaxSize()
                .background(WebBg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = if (showSmartBar) 112.dp else 22.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                item {
                    DateHeader(
                        date = viewedDate,
                        onPrevious = { viewedDate = viewedDate.minusDays(1) },
                        onNext = { if (viewedDate < today) viewedDate = viewedDate.plusDays(1) },
                        onPickDate = { showDatePicker(context, viewedDate) { viewedDate = it } },
                        onHistory = { historyOpen = true },
                        onSettings = { scope.launch { drawerState.open() } },
                    )
                }

                if (summary.complete && events.isNotEmpty()) {
                    item { TodaySummaryCard(events, viewedDate, completedFirst = isToday) }
                }

                if (showLiveControls) {
                    item { EtaPanel(mode = mode, snapshot = snapshot, now = now, tracking = state.tracking.active) }

                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            ActionTile("返工", Modifier.weight(1f), feedback = feedbackFor(feedback, "返工"), onClick = { recordAction("返工") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("返工", timestamp = it, manual = true) }
                            })
                            ActionTile("放工", Modifier.weight(1f), feedback = feedbackFor(feedback, "放工"), onClick = { recordAction("放工") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("放工", timestamp = it, manual = true) }
                            })
                        }
                    }

                    item {
                        StopTile(
                            feedback = feedbackFor(feedback, "到巴士站"),
                            onClick = { recordAction("到巴士站", EventKind.STOP) },
                            onLongClick = { showTimePicker(context, viewedDate, now) { recordAction("到巴士站", EventKind.STOP, timestamp = it, manual = true) } },
                        )
                    }

                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            BusTile("38", Modifier.weight(1f), feedback = feedbackFor(feedback, "38"), onClick = { recordAction("上 38", EventKind.BUS, "38") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("上 38", EventKind.BUS, "38", timestamp = it, manual = true) }
                            })
                            BusTile("42C", Modifier.weight(1f), feedback = feedbackFor(feedback, "42C"), onClick = { recordAction("上 42C", EventKind.BUS, "42C") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("上 42C", EventKind.BUS, "42C", timestamp = it, manual = true) }
                            })
                        }
                    }

                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            ActionTile("到餐廳", Modifier.weight(1f), feedback = feedbackFor(feedback, "到餐廳"), onClick = { recordAction("到餐廳") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("到餐廳", timestamp = it, manual = true) }
                            })
                            ActionTile("到公司", Modifier.weight(1f), feedback = feedbackFor(feedback, "到公司"), onClick = { recordAction("到公司") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("到公司", timestamp = it, manual = true) }
                            })
                        }
                    }

                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            ActionTile("落車", Modifier.weight(1f), feedback = feedbackFor(feedback, "落車"), onClick = { recordAction("落車") }, onLongClick = {
                                showTimePicker(context, viewedDate, now) { recordAction("落車", timestamp = it, manual = true) }
                            })
                            ActionTile("到屋企", Modifier.weight(1f), feedback = feedbackFor(feedback, "到屋企"), onClick = { recordAction("到屋企", terminal = true) }, onLongClick = {
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
                }

                item {
                    TimelineCard(
                        events = events,
                        viewedDate = viewedDate,
                        now = now,
                        showSeconds = state.settings.showSeconds,
                        nextAction = if (isToday) next else NextAction.Done,
                        onLongClick = { editing = it },
                    )
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = if (showSmartBar) 86.dp else 12.dp),
            )

            if (showSmartBar) {
                SmartNextBar(
                    next = next,
                    viewedDate = viewedDate,
                    today = today,
                    onReturnToday = { viewedDate = today },
                    onAction = { a -> recordAction(a.label, a.kind, a.route, a.terminal) },
                    onBus = { route -> recordAction("上 $route", EventKind.BUS, route) },
                    modifier = Modifier.align(Alignment.BottomCenter),
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
            onRoute = { route ->
                AppStore.updateEvent(context, event.copy(route = route, label = "上 $route"))
                editing = null
                reload()
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

}

private fun feedbackFor(feedback: TapFeedback?, key: String): String? = if (feedback?.key == key) feedback.text else null

@Composable
private fun DateHeader(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, modifier = Modifier.size(width = 52.dp, height = 50.dp)) {
                Text("‹", fontSize = 31.sp, color = WebMuted)
            }
            Text(
                HkTime.displayDate(date),
                modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onPickDate),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = WebInk,
            )
            TextButton(onClick = onNext, modifier = Modifier.size(width = 40.dp, height = 50.dp)) {
                Text("›", fontSize = 31.sp, color = WebMuted)
            }
            TextButton(onClick = onHistory, modifier = Modifier.size(width = 58.dp, height = 50.dp)) {
                Text("圖表", fontSize = 11.sp, fontWeight = FontWeight.Black, color = WebAccent2)
            }
            TextButton(onClick = onSettings, modifier = Modifier.size(width = 44.dp, height = 50.dp)) {
                Text("⚙", fontSize = 18.sp, color = WebAccent)
            }
        }
    }
}

@Composable
private fun EtaPanel(mode: CommuteMode, snapshot: EtaSnapshot, now: Long, tracking: Boolean) {
    val station = if (mode == CommuteMode.WORK) "返工 · 德福花園" else "放工 · 屏麗徑南行"
    val age = if (snapshot.updatedAt > 0L) (now - snapshot.updatedAt).coerceAtLeast(0L) else 0L
    val status = when {
        snapshot.refreshingStartedAt > 0L -> "Refreshing… · 顯示上一筆 cache"
        snapshot.errorAt > snapshot.updatedAt -> "Stale · ${ageLabel(age)}"
        snapshot.updatedAt > 0L && tracking && age <= 95_000L -> "Live · updated ${ageLabel(age)}"
        snapshot.updatedAt > 0L -> "Updated ${ageLabel(age)}"
        else -> "未有 ETA"
    }
    val remarks = snapshot.routes.values.flatten().map { it.remark.trim() }.filter { it.isNotBlank() }.distinct().take(2)

    ColorGradientCard(
        colors = listOf(WebCard2, Color(0xFF171D28), WebCard),
        border = WebLine,
        shape = RoundedCornerShape(21.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(station, fontSize = 18.sp, color = WebInk, fontWeight = FontWeight.Black)
                    Text("下次班次預計到達時間", fontSize = 10.sp, color = WebMuted)
                }
                if (tracking) {
                    Surface(shape = RoundedCornerShape(99.dp), color = WebBusSoft, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C5E59))) {
                        Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFFC6FFF5), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            EtaLine("38", snapshot, now, WebBus)
            Spacer(Modifier.height(7.dp))
            EtaLine("42C", snapshot, now, WebAccent)
            if (remarks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                remarks.forEach { Text("• $it", fontSize = 9.sp, lineHeight = 13.sp, color = WebWarm) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                status,
                fontSize = 10.sp,
                color = when {
                    snapshot.refreshingStartedAt > 0L -> WebBus
                    snapshot.errorAt > snapshot.updatedAt -> WebDanger
                    tracking && age <= 95_000L -> WebGood
                    else -> WebMuted
                },
            )
        }
    }
}

@Composable
private fun EtaLine(route: String, snapshot: EtaSnapshot, now: Long, accent: Color) {
    val arrivals = snapshot.routes[route].orEmpty().filter { it.timestamp >= now - 60_000L }.take(3)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFF121721),
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(99.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(route, fontSize = 27.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(66.dp), color = WebInk)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { index ->
                    val arrival = arrivals.getOrNull(index)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            arrival?.let { etaCountdown(it.timestamp, now) } ?: "—",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = if (arrival != null) accent else WebDim,
                            maxLines = 1,
                        )
                        Text(
                            arrival?.let { HkTime.formatTime(it.timestamp, false) } ?: "—",
                            fontSize = 9.sp,
                            color = WebMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun etaCountdown(timestamp: Long, now: Long): String {
    val remaining = timestamp - now
    return when {
        remaining <= 30_000L -> "Due"
        else -> "${ceil(remaining / 60_000.0).toInt()}m"
    }
}

private fun ageLabel(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1000}s ago"
    else -> "${ms / 60_000L}m ago"
}

@Composable
private fun ColorGradientCard(
    modifier: Modifier = Modifier,
    colors: List<Color>,
    border: Color,
    shape: RoundedCornerShape,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .border(1.dp, border, shape)
    ) { content() }
}

@Composable
private fun PressableTile(
    modifier: Modifier,
    shape: RoundedCornerShape,
    colors: List<Color>,
    border: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, label = "press")
    val view = LocalView.current
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (pressed) 2.dp else 8.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .border(1.dp, if (pressed) WebAccent else border, shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                },
            )
    ) { content() }
}

@Composable
private fun ActionTile(text: String, modifier: Modifier, feedback: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    PressableTile(
        modifier = modifier.height(86.dp),
        shape = RoundedCornerShape(20.dp),
        colors = listOf(Color(0xFF1D2230), WebCard),
        border = WebLine,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.Center) {
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.Black, color = WebInk)
            if (feedback != null) Text(feedback, fontSize = 10.sp, color = WebGood, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StopTile(feedback: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    PressableTile(
        modifier = Modifier.fillMaxWidth().height(70.dp),
        shape = RoundedCornerShape(19.dp),
        colors = listOf(Color(0xFF262242), Color(0xFF1E2132), WebCard2),
        border = Color(0xFF4D4784),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("到巴士站", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFFE8E4FF))
                Text("開始計等車時間", fontSize = 10.sp, color = Color(0xFFA9A4D6))
            }
            if (feedback != null) Text(feedback, fontSize = 11.sp, color = WebGood, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BusTile(text: String, modifier: Modifier, feedback: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val purple = text == "42C"
    val accent = if (purple) WebAccent else WebBus
    val soft = if (purple) Color(0xFF24213E) else WebBusSoft
    val border = if (purple) Color(0xFF514A8A) else Color(0xFF35655E)
    val foreground = if (purple) Color(0xFFD9D2FF) else Color(0xFFC6FFF5)
    PressableTile(
        modifier = modifier.height(98.dp),
        shape = RoundedCornerShape(20.dp),
        colors = listOf(if (purple) Color(0xFF2A2450) else Color(0xFF18342F), soft),
        border = border,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            Text(text, fontSize = 32.sp, fontWeight = FontWeight.Black, color = foreground)
            Text(feedback ?: "上車", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (feedback != null) WebGood else accent)
        }
    }
}

private enum class UtilityStyle { Warm, Accent, Neutral }

@Composable
private fun SmallAction(text: String, modifier: Modifier, style: UtilityStyle, onClick: () -> Unit) {
    val (bg, fg, border) = when (style) {
        UtilityStyle.Warm -> Triple(WebWarmSoft, Color(0xFFF9D995), Color(0xFF5B4C29))
        UtilityStyle.Accent -> Triple(WebAccentSoft, Color(0xFFE7E1FF), Color(0xFF514A8A))
        UtilityStyle.Neutral -> Triple(WebCard, WebMuted, WebLine)
    }
    Surface(onClick = onClick, modifier = modifier.height(43.dp), shape = RoundedCornerShape(13.dp), color = bg, border = androidx.compose.foundation.BorderStroke(1.dp, border)) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Black, color = fg) }
    }
}

@Composable
private fun SmartNextBar(
    next: NextAction,
    viewedDate: LocalDate,
    today: LocalDate,
    onReturnToday: () -> Unit,
    onAction: (WorkflowAction) -> Unit,
    onBus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).shadow(12.dp, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF2151923),
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        if (viewedDate != today) {
            SmartButton("返回今日", Modifier.fillMaxWidth().padding(8.dp), onReturnToday)
        } else when (next) {
            is NextAction.Single -> SmartButton("下一步 · ${next.action.label}", Modifier.fillMaxWidth().padding(8.dp)) { onAction(next.action) }
            NextAction.BusChoices -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartButton("38 上車", Modifier.weight(1f)) { onBus("38") }
                SmartButton("42C 上車", Modifier.weight(1f)) { onBus("42C") }
            }
            NextAction.Done -> Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
                Text("✓ 今日完成", color = WebGood, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun SmartButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "smart")
    val view = LocalView.current
    Box(
        modifier
            .height(62.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFFC968), WebWarm)))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
                onLongClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color(0xFF241A08), fontWeight = FontWeight.Black, fontSize = 17.sp) }
}

@Composable
private fun TimelineCard(
    events: List<RecorderEvent>,
    viewedDate: LocalDate,
    now: Long,
    showSeconds: Boolean,
    nextAction: NextAction,
    onLongClick: (RecorderEvent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp), clip = false),
        shape = RoundedCornerShape(20.dp),
        color = WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("▣", color = Color(0xFFAAB7D1), fontSize = 17.sp)
                Spacer(Modifier.width(9.dp))
                Text("今日記錄", fontSize = 18.sp, fontWeight = FontWeight.Black, color = WebInk, modifier = Modifier.weight(1f))
                Text("長按可修改", fontSize = 9.sp, color = WebDim)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = WebLine)

            if (events.isEmpty()) {
                Text("未有記錄", color = WebMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
            } else {
                events.forEachIndexed { index, event ->
                    val following = events.getOrNull(index + 1)
                    val duration = durationLabel(event, following, viewedDate, now)
                    TimelineEventRow(
                        event = event,
                        duration = duration,
                        showSeconds = showSeconds,
                        isFirst = index == 0,
                        isLastEvent = index == events.lastIndex,
                        hasPredicted = nextAction !is NextAction.Done,
                        isLive = following == null && viewedDate == HkTime.today(now) && !event.terminal,
                        onLongClick = { onLongClick(event) },
                    )
                }
            }

            val predicted = nextActionLabel(nextAction)
            if (predicted != null) {
                TimelinePredictedRow(predicted, hasEvents = events.isNotEmpty())
            }
        }
    }
}

@Composable
private fun TimelineEventRow(
    event: RecorderEvent,
    duration: String,
    showSeconds: Boolean,
    isFirst: Boolean,
    isLastEvent: Boolean,
    hasPredicted: Boolean,
    isLive: Boolean,
    onLongClick: () -> Unit,
) {
    val accent = when (event.kind) {
        EventKind.BUS -> if (event.route == "42C") WebAccent else WebBus
        EventKind.STOP -> WebBus
        EventKind.EXTRA -> WebWarm
        else -> WebAccent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp).height(68.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                if (!isFirst) drawLine(WebLine, Offset(cx, 0f), Offset(cx, cy - 9f), strokeWidth = 3f)
                if (!isLastEvent || hasPredicted) drawLine(accent.copy(alpha = 0.72f), Offset(cx, cy + 9f), Offset(cx, size.height), strokeWidth = 3f)
                drawCircle(accent.copy(alpha = 0.18f), radius = 13f, center = Offset(cx, cy))
                drawCircle(accent, radius = 7f, center = Offset(cx, cy))
            }
        }
        Text(
            HkTime.formatTime(event.timestamp, showSeconds),
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(if (showSeconds) 82.dp else 66.dp),
            color = WebInk,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.label, fontSize = 14.sp, fontWeight = FontWeight.Black, color = WebInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (event.auto) {
                    Spacer(Modifier.width(5.dp))
                    Surface(shape = RoundedCornerShape(99.dp), color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, WebLine)) {
                        Text("AUTO", fontSize = 8.sp, color = WebMuted, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
            }
            Text(
                timelineSecondary(event, duration, isLive),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = if (event.kind == EventKind.BUS) accent else WebMuted,
            )
        }
    }
}

private fun timelineSecondary(event: RecorderEvent, duration: String, isLive: Boolean): String = when {
    event.terminal -> "今日行程完成"
    event.kind == EventKind.STOP -> "等車 $duration"
    event.kind == EventKind.BUS && isLive -> "巴士 · 進行中 $duration"
    event.kind == EventKind.BUS -> "巴士 · $duration"
    event.label == "返工" -> "已開始今日嘅行程"
    isLive -> "進行中 $duration"
    else -> "持續 $duration"
}

@Composable
private fun TimelinePredictedRow(label: String, hasEvents: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(34.dp).height(56.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                if (hasEvents) {
                    drawLine(WebDim, Offset(cx, 0f), Offset(cx, cy - 9f), strokeWidth = 2f)
                }
                drawCircle(WebDim, radius = 8f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
            }
        }
        Text("下一步", fontSize = 10.sp, color = WebDim, modifier = Modifier.width(82.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = WebMuted, fontWeight = FontWeight.Bold)
            Text("Smart Next", fontSize = 9.sp, color = WebDim)
        }
    }
}

private fun nextActionLabel(next: NextAction): String? = when (next) {
    is NextAction.Single -> next.action.label
    NextAction.BusChoices -> "上 38 / 42C"
    NextAction.Done -> null
}

private fun durationLabel(event: RecorderEvent, next: RecorderEvent?, viewedDate: LocalDate, now: Long): String {
    if (event.terminal) return "Ending"
    if (next != null) return HkTime.formatDuration(next.timestamp - event.timestamp)
    return if (viewedDate == HkTime.today(now)) HkTime.formatDuration(now - event.timestamp) else "—"
}

@Composable
private fun TodaySummaryCard(events: List<RecorderEvent>, date: LocalDate, completedFirst: Boolean) {
    val summary = RecorderAnalytics.daySummary(events, date)
    ColorGradientCard(
        modifier = Modifier.fillMaxWidth(),
        colors = listOf(Color(0xFF171D28), WebCard2, WebCard),
        border = WebLine,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        completedFirst -> "今日完成"
                        summary.complete -> "Day Summary"
                        else -> "Today Summary"
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = WebInk,
                    modifier = Modifier.weight(1f),
                )
                if (summary.complete) Text("✓ 完成", color = WebGood, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }

            SummaryMainBlock(
                label = "返工路程",
                value = summary.workCommuteMs?.let(HkTime::formatDurationReadable) ?: "—",
                color = WebAccent,
                sub1Label = "等車",
                sub1Value = HkTime.formatDurationReadable(summary.workWaitMs),
                sub2Label = "巴士",
                sub2Value = HkTime.formatDurationReadable(summary.workBusMs),
            )
            SummaryMainBlock(
                label = "工作時間",
                value = summary.workMs?.let(HkTime::formatDurationReadable) ?: "—",
                color = WebAccent2,
            )
            SummaryMainBlock(
                label = "放工返屋企",
                value = summary.homeCommuteMs?.let(HkTime::formatDurationReadable) ?: "—",
                color = WebWarm,
                sub1Label = "等車",
                sub1Value = HkTime.formatDurationReadable(summary.homeWaitMs),
                sub2Label = "巴士",
                sub2Value = HkTime.formatDurationReadable(summary.homeBusMs),
            )

            if (summary.complete) {
                Text(
                    "返工路程 = 返工 → 到公司 · 工作時間 = 到公司 → 放工 · 放工返屋企 = 放工 → 到屋企",
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    color = WebDim,
                )
            }
        }
    }
}

@Composable
private fun SummaryMainBlock(
    label: String,
    value: String,
    color: Color,
    sub1Label: String? = null,
    sub1Value: String? = null,
    sub2Label: String? = null,
    sub2Value: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF10131A),
        contentColor = WebInk,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, fontSize = 10.sp, color = WebMuted, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(value, fontSize = 17.sp, color = color, fontWeight = FontWeight.Black)
            }
            if (sub1Label != null && sub1Value != null && sub2Label != null && sub2Value != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummarySubMetric(sub1Label, sub1Value, Modifier.weight(1f))
                    SummarySubMetric(sub2Label, sub2Value, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummarySubMetric(label: String, value: String, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF171B24))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 9.sp, color = WebMuted, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = WebInk, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SettingsDrawer(
    state: RecorderState,
    onSeconds: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onHistory: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onStopTracking: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.86f),
        drawerContainerColor = Color(0xFF10131A),
        drawerContentColor = WebInk,
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", fontSize = 21.sp, fontWeight = FontWeight.Black, color = WebAccent)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("顯示秒數", fontWeight = FontWeight.Bold, color = WebInk)
                    Text("Timestamp 會一直保存到秒", fontSize = 10.sp, color = WebMuted)
                }
                Switch(checked = state.settings.showSeconds, onCheckedChange = onSeconds)
            }
            HorizontalDivider(color = WebLine)
            Text("ETA", fontSize = 11.sp, color = WebMuted, fontWeight = FontWeight.Black)
            Text("返工：德福花園\n放工：屏麗徑南行\n38 / 42C · 政府 / KMB raw ETA · 60 秒 tracking", fontSize = 12.sp, lineHeight = 19.sp, color = WebInk)
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WebBusSoft, contentColor = Color(0xFFA5F5E9))) {
                Text("立即更新 ETA", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebAccent2)) {
                Text("圖表 / 歷史", fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = WebLine)
            Text("資料", fontSize = 11.sp, color = WebMuted, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBackup, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebGood)) { Text("Backup") }
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebWarm)) { Text("Restore") }
            }
            Text("Backup 係 JSON，可用嚟完整 Restore；暫時唔做 CSV。", fontSize = 10.sp, color = WebMuted)
            HorizontalDivider(color = WebLine)
            Text("Widget", fontSize = 11.sp, color = WebMuted, fontWeight = FontWeight.Black)
            Text("長按 Android 主畫面 → Widgets → Time Recorder。Widget 有 Smart Next Action、38 / 42C 同 cached ETA。", fontSize = 11.sp, lineHeight = 17.sp, color = WebMuted)
            if (state.tracking.active) {
                OutlinedButton(onClick = onStopTracking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebDanger)) {
                    Text("停止 ETA Tracking")
                }
            }
            Text("用右上角 ⚙ 打開 Settings；左邊緣保留畀 Android Back gesture。", fontSize = 10.sp, color = WebMuted)
        }
    }
}

@Composable
private fun EntryActionsDialog(
    event: RecorderEvent,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onTime: () -> Unit,
    onRoute: (String) -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.label, color = WebInk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.kind == EventKind.BUS) {
                    val alternate = if (event.route == "38") "42C" else "38"
                    Button(onClick = { onRoute(alternate) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = WebBusSoft, contentColor = WebBus)) {
                        Text("只改巴士號碼 → $alternate")
                    }
                }
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
        title = { Text("改名稱", color = WebInk) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BackfillDialog(onDismiss: () -> Unit, onSelect: (String, EventKind, String?, Boolean) -> Unit) {
    val entries = listOf(
        Backfill("返工"), Backfill("到巴士站", EventKind.STOP), Backfill("上 38", EventKind.BUS, "38"), Backfill("上 42C", EventKind.BUS, "42C"),
        Backfill("到餐廳"), Backfill("到公司"), Backfill("放工"), Backfill("落車"), Backfill("到屋企", terminal = true), Backfill("其他事", EventKind.EXTRA),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("補記", color = WebInk) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
private fun HistoryPage(state: RecorderState, onBack: () -> Unit) {
    var range by remember { mutableStateOf(HistoryRange.DAYS_30) }
    val threshold = range.days?.let { HkTime.today().minusDays(it - 1L) }
    fun inRange(timestamp: Long): Boolean = threshold == null || HkTime.date(timestamp) >= threshold

    fun buses(mode: CommuteMode, route: String): List<RecorderEvent> = state.events
        .filter { it.kind == EventKind.BUS && it.route == route && RecorderAnalytics.commuteOf(it) == mode && inRange(it.timestamp) }
        .sortedBy { it.timestamp }

    fun waits(mode: CommuteMode, route: String) = RecorderAnalytics.waitJourneySamples(state.events, route, mode)
        .filter { inRange(it.bus.timestamp) }

    fun journeys(mode: CommuteMode, route: String) = RecorderAnalytics.journeySamples(state.events, route, mode)
        .filter { inRange(it.bus.timestamp) }

    Box(
        Modifier
            .fillMaxSize()
            .background(WebBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack, modifier = Modifier.size(width = 52.dp, height = 48.dp)) {
                        Text("‹", fontSize = 32.sp, color = WebInk)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("歷史圖表", fontSize = 22.sp, fontWeight = FontWeight.Black, color = WebInk)
                        Text("得 1 日資料都會照畫", fontSize = 10.sp, color = WebMuted)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectPill("7日", range == HistoryRange.DAYS_7) { range = HistoryRange.DAYS_7 }
                    SelectPill("30日", range == HistoryRange.DAYS_30) { range = HistoryRange.DAYS_30 }
                    SelectPill("3個月", range == HistoryRange.DAYS_90) { range = HistoryRange.DAYS_90 }
                    SelectPill("全部", range == HistoryRange.ALL) { range = HistoryRange.ALL }
                }
            }

            item { HistorySectionTitle("每日上車時間") }
            item {
                HistoryPairCard("返工") {
                    HistoryRoutePlot("38", WebBus) { BoardingHistory(buses(CommuteMode.WORK, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { BoardingHistory(buses(CommuteMode.WORK, "42C")) }
                }
            }
            item {
                HistoryPairCard("放工") {
                    HistoryRoutePlot("38", WebBus) { BoardingHistory(buses(CommuteMode.HOME, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { BoardingHistory(buses(CommuteMode.HOME, "42C")) }
                }
            }

            item { HistorySectionTitle("等車時間") }
            item {
                HistoryPairCard("返工") {
                    HistoryRoutePlot("38", WebBus) { WaitHistory(waits(CommuteMode.WORK, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { WaitHistory(waits(CommuteMode.WORK, "42C")) }
                }
            }
            item {
                HistoryPairCard("放工") {
                    HistoryRoutePlot("38", WebBus) { WaitHistory(waits(CommuteMode.HOME, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { WaitHistory(waits(CommuteMode.HOME, "42C")) }
                }
            }

            item { HistorySectionTitle("車程時間") }
            item {
                HistoryPairCard("返工") {
                    HistoryRoutePlot("38", WebBus) { JourneyHistory(journeys(CommuteMode.WORK, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { JourneyHistory(journeys(CommuteMode.WORK, "42C")) }
                }
            }
            item {
                HistoryPairCard("放工") {
                    HistoryRoutePlot("38", WebBus) { JourneyHistory(journeys(CommuteMode.HOME, "38")) }
                    HistoryRoutePlot("42C", WebAccent) { JourneyHistory(journeys(CommuteMode.HOME, "42C")) }
                }
            }
        }
    }
}

@Composable
private fun HistorySectionTitle(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Black, color = WebInk, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
}

@Composable
private fun HistoryPairCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Black, color = WebAccent2)
            content()
        }
    }
}

@Composable
private fun HistoryRoutePlot(route: String, accent: Color, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(99.dp)).background(accent))
        Spacer(Modifier.width(7.dp))
        Text(route, fontSize = 13.sp, fontWeight = FontWeight.Black, color = WebInk)
    }
    content()
}

@Composable
private fun SelectPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(99.dp),
        color = if (selected) WebAccentSoft else WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) WebAccent else WebLine),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (selected) Color(0xFFE7E1FF) else WebMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun JourneyHistory(samples: List<com.quickstamp.timerecorder.model.JourneySample>) {
    if (samples.isEmpty()) {
        Text("未有車程資料", color = WebMuted, fontSize = 12.sp)
        return
    }
    val durations = samples.map { it.durationMs }
    val median = RecorderAnalytics.medianLong(durations)
    val q1 = RecorderAnalytics.percentileLong(durations, 0.25)
    val q3 = RecorderAnalytics.percentileLong(durations, 0.75)
    Text(
        "Median ${HkTime.formatDurationReadable(median)} · typical ${HkTime.formatDurationReadable(q1)}–${HkTime.formatDurationReadable(q3)} · ${samples.size} trip${if (samples.size == 1) " · Limited data" else "s"}",
        fontSize = 11.sp,
        color = WebInk,
    )
    TrendChart(
        values = samples.map { it.durationMs / 1000f / 60f },
        color = WebBus,
        minLabel = HkTime.formatDuration(durations.minOrNull() ?: 0L),
        maxLabel = HkTime.formatDuration(durations.maxOrNull() ?: 0L),
        startLabel = HkTime.formatDateShort(HkTime.date(samples.first().bus.timestamp)),
        endLabel = HkTime.formatDateShort(HkTime.date(samples.last().bus.timestamp)),
    )
}

@Composable
private fun WaitHistory(samples: List<com.quickstamp.timerecorder.model.WaitSample>) {
    if (samples.isEmpty()) {
        Text("未有等車資料", color = WebMuted, fontSize = 12.sp)
        return
    }
    val durations = samples.map { it.durationMs }
    val median = RecorderAnalytics.medianLong(durations)
    val q1 = RecorderAnalytics.percentileLong(durations, 0.25)
    val q3 = RecorderAnalytics.percentileLong(durations, 0.75)
    Text(
        "Median ${HkTime.formatDurationReadable(median)} · typical ${HkTime.formatDurationReadable(q1)}–${HkTime.formatDurationReadable(q3)} · ${samples.size} sample${if (samples.size == 1) " · Limited data" else "s"}",
        fontSize = 11.sp,
        color = WebInk,
    )
    TrendChart(
        values = samples.map { it.durationMs / 1000f / 60f },
        color = WebAccent2,
        minLabel = HkTime.formatDuration(durations.minOrNull() ?: 0L),
        maxLabel = HkTime.formatDuration(durations.maxOrNull() ?: 0L),
        startLabel = HkTime.formatDateShort(HkTime.date(samples.first().bus.timestamp)),
        endLabel = HkTime.formatDateShort(HkTime.date(samples.last().bus.timestamp)),
    )
}

@Composable
private fun BoardingHistory(events: List<RecorderEvent>) {
    if (events.isEmpty()) {
        Text("未有上車資料", color = WebMuted, fontSize = 12.sp)
        return
    }
    val values = events.map { HkTime.secondOfDay(it.timestamp).toDouble() }
    val median = RecorderAnalytics.medianDouble(values)
    Text(
        "Typical ${HkTime.formatSecondOfDay(median)} · ${events.size} boarding${if (events.size == 1) " · Limited data" else "s"}",
        fontSize = 11.sp,
        color = WebInk,
    )
    TrendChart(
        values = events.map { HkTime.secondOfDay(it.timestamp).toFloat() },
        color = WebAccent,
        minLabel = HkTime.formatSecondOfDay(values.minOrNull() ?: 0.0),
        maxLabel = HkTime.formatSecondOfDay(values.maxOrNull() ?: 0.0),
        startLabel = HkTime.formatDateShort(HkTime.date(events.first().timestamp)),
        endLabel = HkTime.formatDateShort(HkTime.date(events.last().timestamp)),
    )
}

@Composable
private fun TrendChart(values: List<Float>, color: Color, minLabel: String, maxLabel: String, startLabel: String, endLabel: String) {
    if (values.isEmpty()) return
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(maxLabel, fontSize = 8.sp, color = WebMuted, modifier = Modifier.width(66.dp))
            Canvas(Modifier.weight(1f).height(122.dp)) {
                val min = values.minOrNull() ?: 0f
                val max = values.maxOrNull() ?: min + 1f
                val range = (max - min).takeIf { it > 0.001f } ?: 1f
                val left = 5f
                val right = size.width - 5f
                val top = 8f
                val bottom = size.height - 10f
                repeat(4) { i ->
                    val y = top + (bottom - top) * i / 3f
                    drawLine(WebLine, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    val x = if (values.size == 1) size.width / 2f else left + (right - left) * index / (values.size - 1f)
                    val y = bottom - ((value - min) / range).coerceIn(0f, 1f) * (bottom - top)
                    val point = Offset(x, y)
                    previous?.let { drawLine(color, it, point, strokeWidth = 3f, cap = StrokeCap.Round) }
                    drawCircle(color, radius = if (values.size == 1) 6f else 4f, center = point)
                    previous = point
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(minLabel, fontSize = 8.sp, color = WebMuted, modifier = Modifier.width(66.dp))
            if (startLabel == endLabel) {
                Text(startLabel, fontSize = 8.sp, color = WebMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            } else {
                Text(startLabel, fontSize = 8.sp, color = WebMuted, modifier = Modifier.weight(1f))
                Text(endLabel, fontSize = 8.sp, color = WebMuted)
            }
        }
    }
}

@Composable
private fun WeekdayRowsJourney(samples: List<com.quickstamp.timerecorder.model.JourneySample>) {
    val map = RecorderAnalytics.weekdayJourneyMedians(samples)
    WeekdayRows { day -> map[day]?.let(HkTime::formatDuration) ?: "—" }
}

@Composable
private fun WeekdayRowsWait(samples: List<com.quickstamp.timerecorder.model.WaitSample>) {
    val map = RecorderAnalytics.weekdayWaitMedians(samples)
    WeekdayRows { day -> map[day]?.let(HkTime::formatDuration) ?: "—" }
}

@Composable
private fun WeekdayRowsBoarding(events: List<RecorderEvent>) {
    val map = RecorderAnalytics.weekdayBoardingMedians(events)
    WeekdayRows { day -> map[day]?.let(HkTime::formatSecondOfDay) ?: "—" }
}

@Composable
private fun WeekdayRows(value: (DayOfWeek) -> String) {
    val days = listOf(DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu", DayOfWeek.FRIDAY to "Fri")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { (day, label) ->
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = WebCard, border = androidx.compose.foundation.BorderStroke(1.dp, WebLine)) {
                Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 8.sp, color = WebMuted)
                    Text(value(day), fontSize = 9.sp, fontWeight = FontWeight.Black, color = WebInk, maxLines = 1)
                }
            }
        }
    }
}

private fun showTimePicker(context: Context, date: LocalDate, initialTimestamp: Long, onPicked: (Long) -> Unit) {
    val time = Instant.ofEpochMilli(initialTimestamp).atZone(HkTime.zone)
    TimePickerDialog(context, { _, hour, minute -> onPicked(HkTime.at(date, hour, minute, 0)) }, time.hour, time.minute, true).show()
}

private fun showDatePicker(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
