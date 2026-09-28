package com.quickstamp.timerecorder.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object EtaServiceController {
    private const val BACKGROUND_JOB_ID = 3804215

    fun scheduleBackgroundRefresh(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(BACKGROUND_JOB_ID) != null) return
        val info = JobInfo.Builder(
            BACKGROUND_JOB_ID,
            ComponentName(context, EtaBackgroundJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15L * 60L * 1000L)
            .setPersisted(false)
            .build()
        scheduler.schedule(info)
    }
}
