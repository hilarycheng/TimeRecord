package com.quickstamp.timerecorder.audio

import java.time.LocalTime

enum class AudioProfileId { DEFAULT, MORNING, OFFICE, AFTER_WORK }

data class AudioLevels(
    val ring: Int,
    val notification: Int,
    val media: Int,
    val alarm: Int,
    val system: Int,
) {
    fun normalized() = copy(
        ring = ring.coerceIn(0, 100),
        notification = notification.coerceIn(0, 100),
        media = media.coerceIn(0, 100),
        alarm = alarm.coerceIn(0, 100),
        system = system.coerceIn(0, 100),
    )
}

data class ScheduledAudioProfile(
    val id: AudioProfileId,
    val label: String,
    val time: LocalTime,
    val levels: AudioLevels,
)

data class AudioScheduleConfig(
    val enabled: Boolean = false,
    val enforceOnAppOpen: Boolean = true,
    val defaultLevels: AudioLevels = AudioLevels(70, 70, 50, 80, 50),
    val morning: ScheduledAudioProfile = ScheduledAudioProfile(
        AudioProfileId.MORNING, "Morning", LocalTime.of(7, 0), AudioLevels(70, 70, 30, 80, 50)
    ),
    val office: ScheduledAudioProfile = ScheduledAudioProfile(
        AudioProfileId.OFFICE, "Office", LocalTime.of(9, 0), AudioLevels(30, 20, 10, 80, 20)
    ),
    val afterWork: ScheduledAudioProfile = ScheduledAudioProfile(
        AudioProfileId.AFTER_WORK, "After Work", LocalTime.of(18, 0), AudioLevels(70, 70, 50, 80, 50)
    ),
) {
    fun scheduled(): List<ScheduledAudioProfile> = listOf(morning, office, afterWork).sortedBy { it.time }
    fun profile(id: AudioProfileId): ScheduledAudioProfile? = when (id) {
        AudioProfileId.MORNING -> morning
        AudioProfileId.OFFICE -> office
        AudioProfileId.AFTER_WORK -> afterWork
        AudioProfileId.DEFAULT -> null
    }
    fun levels(id: AudioProfileId): AudioLevels = profile(id)?.levels ?: defaultLevels
    fun label(id: AudioProfileId): String = profile(id)?.label ?: "Default"
}

data class AudioProfileResolution(
    val profileId: AudioProfileId,
    val label: String,
    val levels: AudioLevels,
    val dayLabel: String,
    val nextTransitionAt: Long?,
    val nextProfileLabel: String?,
)
