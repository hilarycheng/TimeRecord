package com.quickstamp.timerecorder.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.network.KmbEtaClient
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.time.Instant
import java.time.LocalTime

class EtaBackgroundJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (AppStore.load(applicationContext).tracking.active) return false
        task = executor.submit {
            val now = HkTime.now()
            val time = Instant.ofEpochMilli(now).atZone(HkTime.zone).toLocalTime()
            val mode = when {
                !time.isBefore(LocalTime.of(6, 0)) && time.isBefore(LocalTime.of(10, 0)) -> com.quickstamp.timerecorder.model.CommuteMode.WORK
                !time.isBefore(LocalTime.of(16, 50)) && time.isBefore(LocalTime.of(18, 0)) -> com.quickstamp.timerecorder.model.CommuteMode.HOME
                else -> null
            }
            if (mode != null) runCatching { KmbEtaClient.refresh(applicationContext, mode) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel(true)
        task = null
        return false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
