package com.quickstamp.timerecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quickstamp.timerecorder.audio.*
import kotlinx.coroutines.flow.first
import java.time.LocalTime

private val Context.audioProfileDataStore by preferencesDataStore(name = "audio_profiles")

object AudioProfileStore {
    private val ENABLED = booleanPreferencesKey("enabled")
    private val ENFORCE_OPEN = booleanPreferencesKey("enforce_on_open")

    private fun hourKey(id: AudioProfileId) = intPreferencesKey("${id.name.lowercase()}_hour")
    private fun minuteKey(id: AudioProfileId) = intPreferencesKey("${id.name.lowercase()}_minute")
    private fun levelKey(id: AudioProfileId, field: String) = intPreferencesKey("${id.name.lowercase()}_$field")

    suspend fun load(context: Context): AudioScheduleConfig {
        val p = context.audioProfileDataStore.data.first()
        val defaults = AudioScheduleConfig()
        fun levels(id: AudioProfileId, fallback: AudioLevels): AudioLevels = AudioLevels(
            ring = p[levelKey(id, "ring")] ?: fallback.ring,
            notification = p[levelKey(id, "notification")] ?: fallback.notification,
            media = p[levelKey(id, "media")] ?: fallback.media,
            alarm = p[levelKey(id, "alarm")] ?: fallback.alarm,
            system = p[levelKey(id, "system")] ?: fallback.system,
        ).normalized()
        fun scheduled(base: ScheduledAudioProfile): ScheduledAudioProfile = base.copy(
            time = LocalTime.of(
                (p[hourKey(base.id)] ?: base.time.hour).coerceIn(0, 23),
                (p[minuteKey(base.id)] ?: base.time.minute).coerceIn(0, 59),
            ),
            levels = levels(base.id, base.levels),
        )
        return defaults.copy(
            enabled = p[ENABLED] ?: false,
            enforceOnAppOpen = p[ENFORCE_OPEN] ?: true,
            defaultLevels = levels(AudioProfileId.DEFAULT, defaults.defaultLevels),
            morning = scheduled(defaults.morning),
            office = scheduled(defaults.office),
            afterWork = scheduled(defaults.afterWork),
        )
    }

    suspend fun setEnabled(context: Context, value: Boolean) {
        context.audioProfileDataStore.edit { it[ENABLED] = value }
    }

    suspend fun setEnforceOnOpen(context: Context, value: Boolean) {
        context.audioProfileDataStore.edit { it[ENFORCE_OPEN] = value }
    }

    suspend fun setTime(context: Context, id: AudioProfileId, time: LocalTime) {
        require(id != AudioProfileId.DEFAULT)
        context.audioProfileDataStore.edit {
            it[hourKey(id)] = time.hour
            it[minuteKey(id)] = time.minute
        }
    }

    suspend fun setLevels(context: Context, id: AudioProfileId, levels: AudioLevels) {
        val v = levels.normalized()
        context.audioProfileDataStore.edit {
            it[levelKey(id, "ring")] = v.ring
            it[levelKey(id, "notification")] = v.notification
            it[levelKey(id, "media")] = v.media
            it[levelKey(id, "alarm")] = v.alarm
            it[levelKey(id, "system")] = v.system
        }
    }
}
