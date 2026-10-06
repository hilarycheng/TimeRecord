package com.quickstamp.timerecorder.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.alarm.AlarmClockScheduler
import com.quickstamp.timerecorder.alarm.AlarmClockStore
import com.quickstamp.timerecorder.alarm.AlarmPlaybackService
import com.quickstamp.timerecorder.alarm.UserAlarm
import com.quickstamp.timerecorder.audio.AudioProfileScheduler
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AlarmClockPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(AlarmClockStore.load(context)) }
    var notificationGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }

    fun refresh() { alarms = AlarmClockStore.load(context) }
    fun save(alarm: UserAlarm) {
        AlarmClockStore.upsert(context, alarm)
        if (alarm.enabled) AlarmClockScheduler.schedule(context, alarm) else AlarmClockScheduler.cancel(context, alarm.id)
        refresh()
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back", color = WebAccent, fontWeight = FontWeight.Black) }
                Text("Alarms", color = WebInk, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        val now = LocalTime.now(HkTime.zone)
                        TimePickerDialog(context, { _, h, m ->
                            val alarm = UserAlarm(time = LocalTime.of(h, m), enabled = true, workingDayOnly = true, ringVolume = 100)
                            save(alarm)
                        }, now.hour, now.minute, true).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WebAccent, contentColor = Color.White),
                ) { Text("+ Add", fontWeight = FontWeight.Black) }
            }

            if (AlarmPlaybackService.isRunning) {
                val ringingId = AlarmPlaybackService.activeAlarmId
                Surface(shape = RoundedCornerShape(18.dp), color = WebDangerSoft, border = BorderStroke(1.dp, WebDanger.copy(alpha = .55f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Alarm ringing", color = WebDanger, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    ringingId?.let { AlarmClockScheduler.scheduleSnooze(context, it) }
                                    context.stopService(android.content.Intent(context, AlarmPlaybackService::class.java))
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = WebWarm),
                            ) { Text("Snooze 10m", fontWeight = FontWeight.Black) }
                            Button(
                                onClick = { context.stopService(android.content.Intent(context, AlarmPlaybackService::class.java)) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = WebDanger, contentColor = Color.White),
                            ) { Text("Dismiss", fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = WebCard, border = BorderStroke(1.dp, WebLine)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Explicit alarms only", color = WebInk, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("只有你喺呢頁新增並 Enable 嘅 Alarm 先會響。唔會讀、改或取消 Android Clock / 其他 App 嘅 alarm。", color = WebMuted, fontSize = 10.sp, lineHeight = 16.sp)
                    Text("響起時會暫時提高 Alarm volume；Dismiss / Snooze / timeout 後恢復。", color = WebWarm, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (!AlarmClockScheduler.canScheduleExact(context)) {
                AlarmPermissionCard(
                    title = "需要 Alarms & reminders 權限",
                    text = "用真正 Alarm Clock 排程，確保指定時間觸發。",
                    button = "Grant",
                    onClick = { context.startActivity(AudioProfileScheduler.exactAlarmSettingsIntent(context)) },
                )
            }
            if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
                AlarmPermissionCard(
                    title = "需要 Notifications 權限",
                    text = "鬧鐘響時會用持續 Alarm notification 提供 Snooze / Dismiss。",
                    button = "Grant",
                    onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            if (alarms.isEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF111722), border = BorderStroke(1.dp, WebLine)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("未設定 Alarm", color = WebInk, fontWeight = FontWeight.Black)
                        Text("撳 + Add 先會建立。", color = WebMuted, fontSize = 10.sp)
                    }
                }
            } else {
                alarms.forEach { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        nextAt = AlarmClockScheduler.nextTrigger(context, alarm),
                        onTime = {
                            TimePickerDialog(context, { _, h, m -> save(alarm.copy(time = LocalTime.of(h, m))) }, alarm.time.hour, alarm.time.minute, true).show()
                        },
                        onEnabled = { save(alarm.copy(enabled = it)) },
                        onWorkingDay = { save(alarm.copy(workingDayOnly = it)) },
                        onVolume = { value -> alarms = alarms.map { if (it.id == alarm.id) alarm.copy(ringVolume = value) else it } },
                        onVolumeFinished = {
                            val updated = alarms.firstOrNull { it.id == alarm.id } ?: alarm
                            save(updated)
                        },
                        onDelete = {
                            AlarmClockScheduler.cancel(context, alarm.id)
                            AlarmClockStore.delete(context, alarm.id)
                            refresh()
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: UserAlarm,
    nextAt: Long?,
    onTime: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onWorkingDay: (Boolean) -> Unit,
    onVolume: (Int) -> Unit,
    onVolumeFinished: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = WebCard, border = BorderStroke(1.dp, if (alarm.enabled) WebAccent.copy(alpha = .55f) else WebLine)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTime) {
                    Text(alarm.time.format(DateTimeFormatter.ofPattern("HH:mm")), color = if (alarm.enabled) WebInk else WebMuted, fontSize = 31.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (alarm.workingDayOnly) "Working Day Only" else "Every Day", color = WebMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(nextAlarmText(nextAt), color = if (alarm.enabled) WebBus else WebDim, fontSize = 10.sp)
                }
                Switch(checked = alarm.enabled, onCheckedChange = onEnabled)
            }
            HorizontalDivider(color = WebLine)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("香港工作日", color = WebInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Mon–Fri；Sat / Sun / 香港公眾假期會 Skip。", color = WebMuted, fontSize = 9.sp)
                }
                Switch(checked = alarm.workingDayOnly, onCheckedChange = onWorkingDay)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("響時音量", color = WebInk, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${alarm.ringVolume}%", color = WebWarm, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Slider(
                value = alarm.ringVolume.toFloat(),
                onValueChange = { onVolume(it.toInt().coerceIn(1, 100)) },
                onValueChangeFinished = onVolumeFinished,
                valueRange = 1f..100f,
                colors = SliderDefaults.colors(thumbColor = WebWarm, activeTrackColor = WebWarm, inactiveTrackColor = WebLine),
            )
            Text("響前暫時 boost；停止後恢復正常 Alarm level。", color = WebDim, fontSize = 9.sp)
            TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) { Text("Delete", color = WebDanger, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AlarmPermissionCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = WebDangerSoft, border = BorderStroke(1.dp, WebDanger.copy(alpha = .45f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = WebDanger, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(text, color = WebMuted, fontSize = 9.sp)
            }
            TextButton(onClick = onClick) { Text(button, color = WebDanger, fontWeight = FontWeight.Black) }
        }
    }
}

private fun nextAlarmText(timestamp: Long?): String {
    if (timestamp == null) return "未排程"
    val z = Instant.ofEpochMilli(timestamp).atZone(HkTime.zone)
    return "下一次：${z.format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.ENGLISH))}"
}
