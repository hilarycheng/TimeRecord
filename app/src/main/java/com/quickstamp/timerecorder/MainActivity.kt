package com.quickstamp.timerecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.quickstamp.timerecorder.data.AppStore
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep cold start deliberately boring: no legacy migration, JobScheduler or network
        // work before the first Compose frame is installed.
        setContent {
            TimeRecorderTheme {
                TimeRecorderApp()
            }
        }

        lifecycleScope.launch {
            delay(1_500L)
            runCatching { AppStore.autoClosePastDays(applicationContext) }
            runCatching { EtaServiceController.scheduleBackgroundRefresh(applicationContext) }
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
