package com.quickstamp.timerecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.network.KmbEtaClient
import com.quickstamp.timerecorder.service.EtaForegroundService
import com.quickstamp.timerecorder.service.EtaServiceController
import com.quickstamp.timerecorder.ui.TimeRecorderApp
import com.quickstamp.timerecorder.ui.TimeRecorderTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.autoClosePastDays(applicationContext)
        EtaServiceController.scheduleBackgroundRefresh(applicationContext)
        setContent {
            TimeRecorderTheme {
                TimeRecorderApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppStore.autoClosePastDays(applicationContext)
        val state = AppStore.load(applicationContext)
        if (state.tracking.active && state.tracking.mode != null) {
            runCatching { EtaForegroundService.start(applicationContext, state.tracking.mode) }
        } else {
            val mode = HkTime.modeAt(HkTime.now(), state.settings.morningCutoffHour)
            io.execute { KmbEtaClient.refresh(applicationContext, mode) }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
