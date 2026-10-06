package com.quickstamp.timerecorder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import com.quickstamp.timerecorder.audio.AudioProfileManager
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.model.EventKind
import com.quickstamp.timerecorder.network.KmbEtaClient
import com.quickstamp.timerecorder.service.EtaForegroundService
import com.quickstamp.timerecorder.service.EtaServiceController
import com.quickstamp.timerecorder.ui.TimeRecorderApp
import com.quickstamp.timerecorder.ui.TimeRecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val audioProfileRequestSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNavigationIntent(intent)

        setContent {
            TimeRecorderTheme {
                TimeRecorderApp(audioProfileRequestSignal = audioProfileRequestSignal.intValue)
            }
        }

        lifecycleScope.launch {
            delay(1_500L)
            runCatching { AppStore.autoClosePastDays(applicationContext) }
            runCatching { EtaServiceController.scheduleBackgroundRefresh(applicationContext) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationIntent(intent)
    }

    private fun consumeNavigationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AudioProfileManager.EXTRA_OPEN_AUDIO_PROFILE, false) == true) {
            audioProfileRequestSignal.intValue += 1
            intent.removeExtra(AudioProfileManager.EXTRA_OPEN_AUDIO_PROFILE)
        }
    }

    private fun isRidingToday(events: List<com.quickstamp.timerecorder.model.RecorderEvent>): Boolean {
        val today = HkTime.today()
        val day = events.filter { HkTime.date(it.timestamp) == today }.sortedBy { it.timestamp }
        val lastBus = day.lastOrNull { it.kind == EventKind.BUS } ?: return false
        val lastAlight = day.lastOrNull { it.label == "落車" }
        return lastAlight == null || lastBus.timestamp > lastAlight.timestamp
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            runCatching { AudioProfileManager.reconcileOnAppOpen(applicationContext) }
            val holidayCache = HolidayCalendarStore.load(applicationContext)
            if (HolidayCalendarStore.shouldRefresh(holidayCache)) {
                withContext(Dispatchers.IO) {
                    runCatching { HolidayCalendarStore.refresh(applicationContext) }
                }
                runCatching { AudioProfileManager.reconcileOnAppOpen(applicationContext) }
            }
        }

        lifecycleScope.launch {
            val state = runCatching { AppStore.load(applicationContext) }.getOrNull() ?: return@launch
            if (state.tracking.active && state.tracking.mode != null) {
                runCatching { EtaForegroundService.start(applicationContext, state.tracking.mode) }
            } else if (!isRidingToday(state.events)) {
                val mode = HkTime.modeAt(HkTime.now(), state.settings.morningCutoffHour)
                withContext(Dispatchers.IO) {
                    runCatching { KmbEtaClient.refresh(applicationContext, mode) }
                }
            }
        }
    }
}
