package com.quickstamp.timerecorder.alarm

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstamp.timerecorder.model.HkTime
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dedicated full-screen alarm UI. It intentionally does not reuse the Time Recorder dashboard
 * chrome: ringing alarms use the same simple interaction language as a normal clock app.
 */
class AlarmRingingActivity : ComponentActivity() {
    private var alarmId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent?.getStringExtra(AlarmClockReceiver.EXTRA_ALARM_ID).orEmpty()

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        // Back must never silently dismiss a ringing alarm.
        onBackPressedDispatcher.addCallback(this) { }

        val alarm = AlarmClockStore.find(this, alarmId)
        if (alarm == null) {
            finish()
            return
        }

        setContent {
            AlarmRingingTheme {
                AlarmRingingScreen(
                    alarm = alarm,
                    onSnooze = {
                        AlarmClockScheduler.scheduleSnooze(this, alarm.id)
                        stopService(android.content.Intent(this, AlarmPlaybackService::class.java))
                        finishAndRemoveTask()
                    },
                    onStop = {
                        stopService(android.content.Intent(this, AlarmPlaybackService::class.java))
                        finishAndRemoveTask()
                    },
                    onPlaybackStopped = { finishAndRemoveTask() },
                )
            }
        }
    }
}

private val ClockBg = Color(0xFF101114)
private val ClockInk = Color(0xFFF1F1F5)
private val ClockMuted = Color(0xFFB8BAC3)
private val ClockSnooze = Color(0xFF303137)
private val ClockStop = Color(0xFFC5C9FF)
private val ClockStopInk = Color(0xFF20203A)

@Composable
private fun AlarmRingingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ClockBg,
            onBackground = ClockInk,
            surface = ClockBg,
            onSurface = ClockInk,
            primary = ClockStop,
            onPrimary = ClockStopInk,
        ),
        content = content,
    )
}

@Composable
private fun AlarmRingingScreen(
    alarm: UserAlarm,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    onPlaybackStopped: () -> Unit,
) {
    LaunchedEffect(alarm.id) {
        while (true) {
            delay(500L)
            if (!AlarmPlaybackService.isRunning || AlarmPlaybackService.activeAlarmId != alarm.id) {
                onPlaybackStopped()
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClockBg)
            .padding(horizontal = 24.dp, vertical = 34.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Text(
                text = alarm.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = ClockInk,
                fontSize = 88.sp,
                lineHeight = 92.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = alarm.label.ifBlank { "Alarm" },
                color = ClockInk,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = HkTime.today().format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)),
                color = ClockMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AlarmPill(
                    text = "Snooze",
                    background = ClockSnooze,
                    foreground = ClockInk,
                    modifier = Modifier.weight(1f),
                    onClick = onSnooze,
                )
                AlarmPill(
                    text = "Stop",
                    background = ClockStop,
                    foreground = ClockStopInk,
                    modifier = Modifier.weight(1f),
                    onClick = onStop,
                )
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun AlarmPill(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(78.dp),
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = foreground)
        }
    }
}
