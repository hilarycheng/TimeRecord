package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.quickstamp.timerecorder.audio.*
import com.quickstamp.timerecorder.data.AudioProfileStore
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.model.HkTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AudioProfilePage(
    now: Long,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var config by remember { mutableStateOf<AudioScheduleConfig?>(null) }
    var selected by remember { mutableStateOf(AudioProfileId.OFFICE) }
    var refreshingHoliday by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun reloadConfig() {
        scope.launch { config = AudioProfileStore.load(context) }
    }

    LaunchedEffect(Unit) {
        config = AudioProfileStore.load(context)
        val cache = HolidayCalendarStore.load(context)
        if (HolidayCalendarStore.shouldRefresh(cache)) {
            refreshingHoliday = true
            withContext(Dispatchers.IO) { HolidayCalendarStore.refresh(context) }
            refreshingHoliday = false
            AudioProfileManager.reconcileOnAppOpen(context)
        }
    }

    val cfg = config
    if (cfg == null) {
        Box(Modifier.fillMaxSize().background(WebBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WebAccent)
        }
        return
    }

    val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
    val resolution = AudioProfileEngine.resolve(cfg, now, holidays)
    val exactAllowed = AudioProfileScheduler.canScheduleExact(context)
    val notificationAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val selectedLevels = cfg.levels(selected)
    val selectedProfile = cfg.profile(selected)

    Box(
        Modifier
            .fillMaxSize()
            .background(WebBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹", fontSize = 30.sp, color = WebInk) }
                Text("Audio Profiles", fontSize = 22.sp, fontWeight = FontWeight.Black, color = WebInk, modifier = Modifier.weight(1f))
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141C29),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27364D)),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Audio Schedule", fontSize = 18.sp, fontWeight = FontWeight.Black, color = WebInk)
                            Text("今日：${resolution.dayLabel}", fontSize = 11.sp, color = WebMuted)
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { enabled ->
                                config = cfg.copy(enabled = enabled)
                                scope.launch {
                                    AudioProfileStore.setEnabled(context, enabled)
                                    if (enabled) {
                                        if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed) {
                                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else if (!AudioProfileScheduler.canScheduleExact(context)) {
                                            context.startActivity(AudioProfileScheduler.exactAlarmSettingsIntent(context))
                                        }
                                        AudioProfileManager.reconcileOnAppOpen(context)
                                    } else {
                                        AudioProfileManager.disable(context)
                                    }
                                    reloadConfig()
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = WebLine)
                    StatusRow("目前 Profile", resolution.label, WebAccent2)
                    StatusRow("下一次", formatNext(resolution.nextTransitionAt, resolution.nextProfileLabel), WebBus)
                    if (refreshingHoliday) Text("正在更新香港公眾假期…", fontSize = 10.sp, color = WebWarm)
                }
            }

            if (cfg.enabled && !exactAllowed) {
                PermissionCard(
                    title = "需要 Alarms & reminders 權限",
                    text = "未授權時唔會安排準時 Profile 切換。",
                    button = "Grant",
                    onClick = { context.startActivity(AudioProfileScheduler.exactAlarmSettingsIntent(context)) },
                )
            }
            if (cfg.enabled && !notificationAllowed) {
                PermissionCard(
                    title = "需要 Notification 權限",
                    text = "用嚟長期顯示目前 Audio Profile；通知本身係靜音。",
                    button = "Grant",
                    onClick = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            Text("Profiles", fontSize = 12.sp, color = WebMuted, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ProfileChip("Default", selected == AudioProfileId.DEFAULT, Modifier.weight(1f)) { selected = AudioProfileId.DEFAULT }
                ProfileChip("Office", selected == AudioProfileId.OFFICE, Modifier.weight(1f)) { selected = AudioProfileId.OFFICE }
                ProfileChip("After", selected == AudioProfileId.AFTER_WORK, Modifier.weight(1f)) { selected = AudioProfileId.AFTER_WORK }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = WebCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cfg.label(selected), fontSize = 20.sp, fontWeight = FontWeight.Black, color = WebInk)
                            Text(if (selected == AudioProfileId.DEFAULT) "Weekend / 公眾假期 / Working Day 第一個時間前" else "Working Day Profile", fontSize = 10.sp, color = WebMuted)
                        }
                        if (selectedProfile != null) {
                            OutlinedButton(onClick = {
                                val t = selectedProfile.time
                                TimePickerDialog(context, { _, h, m ->
                                    val time = LocalTime.of(h, m)
                                    config = updateTime(cfg, selected, time)
                                    scope.launch {
                                        AudioProfileStore.setTime(context, selected, time)
                                        AudioProfileManager.reconcileOnAppOpen(context)
                                        reloadConfig()
                                    }
                                }, t.hour, t.minute, true).show()
                            }) {
                                Text(selectedProfile.time.format(DateTimeFormatter.ofPattern("HH:mm")), fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    VolumeSlider(
                        label = "Ring", icon = "☎", value = selectedLevels.ring, accent = WebAccent,
                        onValue = { value -> config = updateLevels(config!!, selected, config!!.levels(selected).copy(ring = value)) },
                        onFinished = { scope.launch { AudioProfileStore.setLevels(context, selected, config!!.levels(selected)) } },
                    )
                    VolumeSlider(
                        label = "Notification", icon = "●", value = selectedLevels.notification, accent = WebAccent2,
                        onValue = { value -> config = updateLevels(config!!, selected, config!!.levels(selected).copy(notification = value)) },
                        onFinished = { scope.launch { AudioProfileStore.setLevels(context, selected, config!!.levels(selected)) } },
                    )
                    VolumeSlider(
                        label = "Media", icon = "▶", value = selectedLevels.media, accent = WebBus,
                        onValue = { value -> config = updateLevels(config!!, selected, config!!.levels(selected).copy(media = value)) },
                        onFinished = { scope.launch { AudioProfileStore.setLevels(context, selected, config!!.levels(selected)) } },
                    )
                    VolumeSlider(
                        label = "Alarm", icon = "◷", value = selectedLevels.alarm, accent = WebWarm,
                        onValue = { value -> config = updateLevels(config!!, selected, config!!.levels(selected).copy(alarm = value)) },
                        onFinished = { scope.launch { AudioProfileStore.setLevels(context, selected, config!!.levels(selected)) } },
                    )
                    VolumeSlider(
                        label = "System", icon = "◆", value = selectedLevels.system, accent = Color(0xFF91A7CF),
                        onValue = { value -> config = updateLevels(config!!, selected, config!!.levels(selected).copy(system = value)) },
                        onFinished = { scope.launch { AudioProfileStore.setLevels(context, selected, config!!.levels(selected)) } },
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val failed = AudioProfileManager.applyLevels(context, config!!.levels(selected))
                                scope.launch { snackbar.showSnackbar(if (failed.isEmpty()) "已試用 ${config!!.label(selected)}" else "部分音量未能套用：${failed.joinToString()}") }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Test Profile") }
                        Button(
                            onClick = {
                                val levels = config!!.levels(selected)
                                val failed = AudioProfileManager.applyLevels(context, levels)
                                scope.launch {
                                    AudioProfileManager.showManualProfile(context, config!!.label(selected), levels)
                                    snackbar.showSnackbar(if (failed.isEmpty()) "已套用 ${config!!.label(selected)}" else "部分音量未能套用：${failed.joinToString()}")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = WebAccent, contentColor = Color.White),
                        ) { Text("Apply Now", fontWeight = FontWeight.Black) }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111722),
                border = androidx.compose.foundation.BorderStroke(1.dp, WebLine),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Schedule rule", fontSize = 13.sp, fontWeight = FontWeight.Black, color = WebInk)
                    Text("Mon–Fri + 非香港公眾假期 → Office / After Work\nOffice 時間前 → Default\nSat / Sun / 香港公眾假期 → Default", fontSize = 11.sp, lineHeight = 18.sp, color = WebMuted)
                    Text("部分 Android 手機會由系統將 Ring / Notification 音量綁定。", fontSize = 9.sp, color = WebDim)
                    HorizontalDivider(color = WebLine)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Enforce profile on app open", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WebInk)
                            Text("開 App 時發現實際音量唔啱，就重新套用目前應有 Profile。", fontSize = 9.sp, color = WebMuted)
                        }
                        Switch(
                            checked = cfg.enforceOnAppOpen,
                            onCheckedChange = { enabled ->
                                config = cfg.copy(enforceOnAppOpen = enabled)
                                scope.launch { AudioProfileStore.setEnforceOnOpen(context, enabled); reloadConfig() }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(70.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = WebMuted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = accent, fontWeight = FontWeight.Black, textAlign = TextAlign.End)
    }
}

@Composable
private fun PermissionCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = WebDangerSoft, border = androidx.compose.foundation.BorderStroke(1.dp, WebDanger.copy(alpha = .4f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = WebDanger, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(text, color = WebMuted, fontSize = 9.sp)
            }
            TextButton(onClick = onClick) { Text(button, color = WebDanger, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun ProfileChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) WebAccentSoft else WebCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) WebAccent else WebLine),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) Color(0xFFE8E2FF) else WebMuted, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun VolumeSlider(
    label: String,
    icon: String,
    value: Int,
    accent: Color,
    onValue: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(9.dp), color = accent.copy(alpha = .14f), modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(icon, color = accent, fontWeight = FontWeight.Black, fontSize = 14.sp) }
            }
            Spacer(Modifier.width(9.dp))
            Text(label, color = WebInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$value%", color = accent, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt().coerceIn(0, 100)) },
            onValueChangeFinished = onFinished,
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = WebLine),
        )
    }
}

private fun updateLevels(config: AudioScheduleConfig, id: AudioProfileId, levels: AudioLevels): AudioScheduleConfig = when (id) {
    AudioProfileId.DEFAULT -> config.copy(defaultLevels = levels)
    AudioProfileId.OFFICE -> config.copy(office = config.office.copy(levels = levels))
    AudioProfileId.AFTER_WORK -> config.copy(afterWork = config.afterWork.copy(levels = levels))
}

private fun updateTime(config: AudioScheduleConfig, id: AudioProfileId, time: LocalTime): AudioScheduleConfig = when (id) {
    AudioProfileId.DEFAULT -> config
    AudioProfileId.OFFICE -> config.copy(office = config.office.copy(time = time))
    AudioProfileId.AFTER_WORK -> config.copy(afterWork = config.afterWork.copy(time = time))
}

private fun formatNext(timestamp: Long?, label: String?): String {
    if (timestamp == null) return "—"
    val z = Instant.ofEpochMilli(timestamp).atZone(HkTime.zone)
    return "${z.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))} → ${label ?: "Default"}"
}
