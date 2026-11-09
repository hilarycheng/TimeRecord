import com.quickstamp.timerecorder.audio.AudioProfileEngine
import com.quickstamp.timerecorder.audio.AudioProfileId
import com.quickstamp.timerecorder.audio.AudioScheduleConfig
import com.quickstamp.timerecorder.model.HkTime
import java.time.LocalDate
import java.time.LocalTime

// Compile with Models.kt, AudioProfileModels.kt, and AudioProfileEngine.kt; run this file's main function.
fun main() {
    val monday = LocalDate.of(2026, 11, 9)
    fun at(date: LocalDate, time: String) = date.atTime(LocalTime.parse(time))
        .atZone(HkTime.zone).toInstant().toEpochMilli()
    val config = AudioScheduleConfig(enabled = true)
    val override = config.copy(afterWorkOverrideEpochDay = monday.toEpochDay())
    val midnight = at(monday.plusDays(1), "00:00")

    check(AudioProfileEngine.resolve(config, at(monday, "16:00"), emptySet()).profileId == AudioProfileId.OFFICE)
    for (time in listOf("08:00", "16:00", "18:00", "23:59:59.900")) {
        val result = AudioProfileEngine.resolve(override, at(monday, time), emptySet())
        check(result.profileId == AudioProfileId.AFTER_WORK) { "Override lost at $time" }
        check(result.levels == config.afterWork.levels)
        check(result.label == "After Work")
        check(result.nextTransitionAt == midnight) { "Override must expire at Hong Kong midnight" }
        check(result.nextProfileLabel == "Default")
    }

    // Later Office transitions cannot undo an explicit After Work action.
    val lateOffice = override.copy(office = config.office.copy(time = LocalTime.of(20, 0)))
    check(AudioProfileEngine.resolve(lateOffice, at(monday, "21:00"), emptySet()).profileId == AudioProfileId.AFTER_WORK)
    check(AudioProfileEngine.resolve(lateOffice, at(monday, "16:00"), emptySet()).nextTransitionAt == midnight)

    val midnightOffice = override.copy(office = config.office.copy(time = LocalTime.MIDNIGHT))
    check(AudioProfileEngine.resolve(midnightOffice, at(monday, "16:00"), emptySet()).nextProfileLabel == "Office")
    check(AudioProfileEngine.resolve(midnightOffice, midnight, emptySet()).profileId == AudioProfileId.OFFICE)

    val nextDay = monday.plusDays(1)
    check(AudioProfileEngine.resolve(override, midnight, emptySet()).profileId == AudioProfileId.DEFAULT)
    check(AudioProfileEngine.resolve(override, midnight, emptySet()).nextTransitionAt == at(nextDay, "09:00"))
    check(AudioProfileEngine.resolve(override, at(nextDay, "10:00"), emptySet()).profileId == AudioProfileId.OFFICE)
    check(AudioProfileEngine.resolve(override, at(nextDay, "19:00"), emptySet()).profileId == AudioProfileId.AFTER_WORK)

    // Expired overrides preserve holiday, leave-day, and weekend behaviour.
    check(AudioProfileEngine.resolve(override, at(nextDay, "10:00"), setOf(nextDay)).profileId == AudioProfileId.DEFAULT)
    check(AudioProfileEngine.resolve(override, at(nextDay, "10:00"), emptySet(), setOf(nextDay))
        .profileId == AudioProfileId.DEFAULT)
    check(AudioProfileEngine.resolve(override, at(monday.plusDays(5), "10:00"), emptySet())
        .profileId == AudioProfileId.DEFAULT)
    check(AudioProfileEngine.resolve(config, at(monday, "08:00"), emptySet()).profileId == AudioProfileId.DEFAULT)
    check(config.afterWorkOverrideEpochDay == null)
    println("Audio profile override checks passed")
}
