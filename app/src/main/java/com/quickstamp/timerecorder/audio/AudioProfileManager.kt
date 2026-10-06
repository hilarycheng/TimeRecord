package com.quickstamp.timerecorder.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quickstamp.timerecorder.MainActivity
import com.quickstamp.timerecorder.data.AudioProfileStore
import com.quickstamp.timerecorder.data.HolidayCalendarStore
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object AudioProfileManager {
    const val EXTRA_OPEN_AUDIO_PROFILE = "open_audio_profile"
    private const val CHANNEL_ID = "audio_profile_status"
    private const val NOTIFICATION_ID = 3401

    suspend fun reconcileOnAppOpen(context: Context) {
        val config = AudioProfileStore.load(context)
        if (!config.enabled) {
            AudioProfileScheduler.cancel(context)
            cancelNotification(context)
            return
        }
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        val resolution = AudioProfileEngine.resolve(config, HkTime.now(), holidays)
        if (config.enforceOnAppOpen && !matches(context, resolution.levels)) {
            applyLevels(context, resolution.levels)
        }
        updateNotification(context, config, resolution)
        AudioProfileScheduler.schedule(context, resolution.nextTransitionAt)
    }

    suspend fun onScheduledTrigger(context: Context) {
        val config = AudioProfileStore.load(context)
        if (!config.enabled) {
            AudioProfileScheduler.cancel(context)
            cancelNotification(context)
            return
        }
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        val resolution = AudioProfileEngine.resolve(config, HkTime.now(), holidays)
        applyLevels(context, resolution.levels)
        updateNotification(context, config, resolution)
        AudioProfileScheduler.schedule(context, resolution.nextTransitionAt)
    }

    suspend fun refreshHolidayAndReconcile(context: Context) {
        val cache = HolidayCalendarStore.load(context)
        if (HolidayCalendarStore.shouldRefresh(cache)) {
            HolidayCalendarStore.refresh(context)
        }
        reconcileOnAppOpen(context)
    }

    suspend fun disable(context: Context) {
        AudioProfileStore.setEnabled(context, false)
        AudioProfileScheduler.cancel(context)
        cancelNotification(context)
    }

    fun applyLevels(context: Context, levels: AudioLevels): List<String> {
        val audio = context.getSystemService(AudioManager::class.java)
        val failed = mutableListOf<String>()
        fun set(stream: Int, value: Int, name: String) {
            runCatching {
                val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0
                val index = (min + (max - min) * (value.coerceIn(0, 100) / 100.0)).roundToInt().coerceIn(min, max)
                audio.setStreamVolume(stream, index, 0)
            }.onFailure { failed += name }
        }
        set(AudioManager.STREAM_RING, levels.ring, "Ring")
        set(AudioManager.STREAM_NOTIFICATION, levels.notification, "Notification")
        set(AudioManager.STREAM_MUSIC, levels.media, "Media")
        set(AudioManager.STREAM_ALARM, levels.alarm, "Alarm")
        set(AudioManager.STREAM_SYSTEM, levels.system, "System")
        return failed
    }

    fun matches(context: Context, levels: AudioLevels): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        fun expectedIndex(stream: Int, percent: Int): Int {
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0
            return (min + (max - min) * (percent.coerceIn(0, 100) / 100.0)).roundToInt().coerceIn(min, max)
        }
        fun matchesStream(stream: Int, percent: Int): Boolean = audio.getStreamVolume(stream) == expectedIndex(stream, percent)
        return matchesStream(AudioManager.STREAM_RING, levels.ring) &&
            matchesStream(AudioManager.STREAM_NOTIFICATION, levels.notification) &&
            matchesStream(AudioManager.STREAM_MUSIC, levels.media) &&
            matchesStream(AudioManager.STREAM_ALARM, levels.alarm) &&
            matchesStream(AudioManager.STREAM_SYSTEM, levels.system)
    }

    fun currentSystemLevels(context: Context): AudioLevels {
        val audio = context.getSystemService(AudioManager::class.java)
        fun pct(stream: Int): Int {
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0
            val current = audio.getStreamVolume(stream)
            return (((current - min).coerceAtLeast(0) * 100.0) / (max - min).coerceAtLeast(1)).roundToInt().coerceIn(0, 100)
        }
        return AudioLevels(
            pct(AudioManager.STREAM_RING),
            pct(AudioManager.STREAM_NOTIFICATION),
            pct(AudioManager.STREAM_MUSIC),
            pct(AudioManager.STREAM_ALARM),
            pct(AudioManager.STREAM_SYSTEM),
        )
    }

    suspend fun updateStatusNotification(context: Context) {
        val config = AudioProfileStore.load(context)
        if (!config.enabled) return cancelNotification(context)
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        updateNotification(context, config, AudioProfileEngine.resolve(config, HkTime.now(), holidays))
    }

    suspend fun showManualProfile(context: Context, label: String, levels: AudioLevels) {
        val config = AudioProfileStore.load(context)
        if (!config.enabled) return
        val holidays = HolidayCalendarStore.load(context).holidays.map { it.date }.toSet()
        val base = AudioProfileEngine.resolve(config, HkTime.now(), holidays)
        updateNotification(context, config, base.copy(label = "Manual · $label", levels = levels))
        AudioProfileScheduler.schedule(context, base.nextTransitionAt)
    }

    private fun updateNotification(context: Context, config: AudioScheduleConfig, r: AudioProfileResolution) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Audio profile status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "目前 Audio Profile 同下一次排程"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_AUDIO_PROFILE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val l = r.levels
        val next = r.nextTransitionAt?.let { at ->
            val z = Instant.ofEpochMilli(at).atZone(HkTime.zone)
            val whenText = z.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))
            "下一次：$whenText → ${r.nextProfileLabel ?: "Default"}"
        } ?: "暫時沒有下一次變更"
        val text = "Ring ${l.ring}% · Notification ${l.notification}% · Media ${l.media}%\n$next"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Audio Profile · ${r.label}")
            .setContentText(next)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
