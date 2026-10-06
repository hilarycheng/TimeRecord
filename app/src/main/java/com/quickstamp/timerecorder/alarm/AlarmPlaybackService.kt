package com.quickstamp.timerecorder.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.quickstamp.timerecorder.MainActivity

class AlarmPlaybackService : Service() {
    companion object {
        const val ACTION_START = "com.quickstamp.timerecorder.alarm.START_PLAYBACK"
        const val ACTION_STOP = "com.quickstamp.timerecorder.alarm.STOP_PLAYBACK"
        private const val CHANNEL_ID = "explicit_alarm_ring"
        private const val NOTIFICATION_ID = 5521
        private const val MAX_RING_MS = 30L * 60L * 1000L
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var activeAlarmId: String? = null
            private set
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val timeout = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAlarm()
            ACTION_START -> {
                val id = intent.getStringExtra(AlarmClockReceiver.EXTRA_ALARM_ID) ?: return START_NOT_STICKY
                startAlarm(id)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAlarm(alarmId: String) {
        val alarm = AlarmClockStore.find(this, alarmId) ?: run { stopSelf(); return }
        if (isRunning) stopAlarm(stopSelfAfter = false)
        activeAlarmId = alarmId
        isRunning = true
        AlarmVolumeGuard.boost(this, alarmId, alarm.ringVolume)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(alarm))

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeRecorder:Alarm").apply { acquire(MAX_RING_MS + 60_000L) }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        val started = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmPlaybackService, uri)
                isLooping = true
                prepare()
                start()
            }.also { player = it }
        }.isSuccess
        if (!started) {
            stopAlarm()
            return
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_RING_MS)
    }

    private fun stopAlarm(stopSelfAfter: Boolean = true) {
        handler.removeCallbacks(timeout)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        AlarmVolumeGuard.restore(this)
        isRunning = false
        activeAlarmId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopSelfAfter) stopSelf()
    }

    private fun buildNotification(alarm: UserAlarm): Notification {
        val open = PendingIntent.getActivity(
            this,
            900,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ALARMS, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun actionPending(action: String, salt: Int) = PendingIntent.getBroadcast(
            this,
            (alarm.id.hashCode() * 31 + salt) and 0x7fffffff,
            Intent(this, AlarmActionReceiver::class.java).setAction(action).putExtra(AlarmClockReceiver.EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarm.label.ifBlank { "Alarm" })
            .setContentText("${alarm.time} · Alarm volume ${alarm.ringVolume}%")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .addAction(0, "Snooze 10m", actionPending(AlarmActionReceiver.ACTION_SNOOZE, 11))
            .addAction(0, "Dismiss", actionPending(AlarmActionReceiver.ACTION_DISMISS, 12))
            .build()
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Explicit alarms created in Time Recorder"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    override fun onDestroy() {
        if (isRunning) stopAlarm(stopSelfAfter = false)
        super.onDestroy()
    }
}
