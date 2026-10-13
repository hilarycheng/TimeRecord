package com.quickstamp.timerecorder.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.quickstamp.timerecorder.model.HkTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

data class SystemCalendarEvent(
    val key: String,
    val eventId: Long,
    val title: String,
    val date: LocalDate,
    val allDay: Boolean,
    val calendarName: String,
    val startAt: LocalDateTime? = null,
    val endAt: LocalDateTime? = null,
)

object SystemCalendarReader {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(context: Context, from: LocalDate, toExclusive: LocalDate): List<SystemCalendarEvent> {
        if (!hasPermission(context) || !toExclusive.isAfter(from)) return emptyList()
        return runCatching {
            val begin = from.atStartOfDay(HkTime.zone).toInstant().toEpochMilli()
            val end = toExclusive.atStartOfDay(HkTime.zone).toInstant().toEpochMilli()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
            val out = mutableListOf<SystemCalendarEvent>()
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { c ->
                val eventIdIndex = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIndex = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val allDayIndex = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val beginIndex = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val calendarIndex = c.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                val endIndex = c.getColumnIndex(CalendarContract.Instances.END)
                while (c.moveToNext()) {
                    if (eventIdIndex < 0 || titleIndex < 0 || beginIndex < 0) continue
                    val eventId = c.getLong(eventIdIndex)
                    val title = c.getString(titleIndex)?.trim().orEmpty()
                    if (title.isBlank()) continue
                    val allDay = allDayIndex >= 0 && c.getInt(allDayIndex) == 1
                    val beginMs = c.getLong(beginIndex)
                    val date = if (allDay) {
                        Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
                    } else {
                        Instant.ofEpochMilli(beginMs).atZone(HkTime.zone).toLocalDate()
                    }
                    if (date.isBefore(from) || !date.isBefore(toExclusive)) continue
                    out += SystemCalendarEvent(
                        key = "$eventId:$beginMs",
                        eventId = eventId,
                        title = title,
                        date = date,
                        allDay = allDay,
                        calendarName = if (calendarIndex >= 0) c.getString(calendarIndex).orEmpty() else "Calendar",
                        startAt = if (allDay) null else Instant.ofEpochMilli(beginMs).atZone(HkTime.zone).toLocalDateTime(),
                        endAt = if (endIndex < 0) null else Instant.ofEpochMilli(c.getLong(endIndex))
                            .atZone(if (allDay) ZoneOffset.UTC else HkTime.zone).toLocalDateTime(),
                    )
                }
            }
            out.distinctBy { it.key }.sortedBy { it.date }
        }.getOrDefault(emptyList())
    }
}
