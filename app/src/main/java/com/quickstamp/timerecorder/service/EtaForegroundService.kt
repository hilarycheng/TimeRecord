package com.quickstamp.timerecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EtaSnapshot
import com.quickstamp.timerecorder.model.HkTime
import com.quickstamp.timerecorder.network.KmbEtaClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class EtaForegroundService : Service() {
    private var scheduler: ScheduledExecutorService? = null
    private var activeMode: CommuteMode? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppStore.setTracking(applicationContext, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val mode = intent?.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { CommuteMode.valueOf(it) }.getOrNull() }
                    ?: AppStore.load(applicationContext).tracking.mode
                    ?: HkTime.modeAt(HkTime.now())
                startTracking(mode)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTracking(mode: CommuteMode) {
        if (activeMode == mode && scheduler?.isShutdown == false) return
        scheduler?.shutdownNow()
        activeMode = mode
        AppStore.setTracking(applicationContext, true, mode)
        startForeground(NOTIFICATION_ID, buildNotification(mode))

        scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleAtFixedRate({
                val current = activeMode ?: return@scheduleAtFixedRate
                runCatching { KmbEtaClient.refresh(applicationContext, current) }
                AppStore.updateTrackingHeartbeat(applicationContext)
                notifyUpdate(current)
            }, 0L, 60L, TimeUnit.SECONDS)
        }
    }

    private fun notifyUpdate(mode: CommuteMode) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: CommuteMode): Notification {
        val state = AppStore.load(applicationContext)
        val snapshot = if (mode == CommuteMode.WORK) state.etaWork else state.etaHome
        val title = "Time Recorder · ${if (mode == CommuteMode.WORK) "返工" else "放工"}"
        val text = notificationEtaText(snapshot)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun notificationEtaText(snapshot: EtaSnapshot): String {
        val now = HkTime.now()
        fun routeText(route: String): String {
            val values = snapshot.routes[route].orEmpty()
                .filter { it.timestamp >= now - 60_000L }
                .take(2)
                .map { etaLabel(it.timestamp, now) }
            return "$route: ${if (values.isEmpty()) "—" else values.joinToString(" · ")}"
        }
        val suffix = when {
            snapshot.refreshingStartedAt > 0L -> " · Refreshing…"
            snapshot.errorAt > snapshot.updatedAt -> " · Stale"
            snapshot.updatedAt > 0L -> " · ${HkTime.formatTime(snapshot.updatedAt, false)}"
            else -> ""
        }
        return "${routeText("38")}   ${routeText("42C")}$suffix"
    }

    private fun etaLabel(timestamp: Long, now: Long): String {
        val seconds = timestamp - now
        return when {
            seconds <= 30_000L -> "Due"
            else -> "${ceil(seconds / 60_000.0).toInt()}m"
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bus ETA tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Refreshes bus ETA while a commute is active"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        activeMode = null
        AppStore.setTracking(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.quickstamp.timerecorder.action.START_ETA"
        const val ACTION_STOP = "com.quickstamp.timerecorder.action.STOP_ETA"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "eta_tracking"
        private const val NOTIFICATION_ID = 38042

        fun start(context: Context, mode: CommuteMode) {
            val intent = Intent(context, EtaForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            AppStore.setTracking(context.applicationContext, false)
            context.stopService(Intent(context, EtaForegroundService::class.java))
        }
    }
}
