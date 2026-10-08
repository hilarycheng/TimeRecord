package com.quickstamp.timerecorder.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Optional personal-calendar override for Working Day alarms.
 *
 * Only an all-day event whose title is exactly "放假" suppresses a Working Day alarm.
 * Any permission/query failure deliberately returns no leave dates so alarms fail safe by ringing.
 */
object PersonalDayOffCalendar {
    const val LEAVE_TITLE = "放假"
    private const val CACHE_MS = 30_000L

    private data class Cache(
        val from: LocalDate,
        val toExclusive: LocalDate,
        val loadedAt: Long,
        val dates: Set<LocalDate>,
    )

    @Volatile private var cache: Cache? = null

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun leaveDates(context: Context, from: LocalDate, toExclusive: LocalDate): Set<LocalDate> {
        if (!hasPermission(context) || !toExclusive.isAfter(from)) return emptySet()
        val now = System.currentTimeMillis()
        cache?.takeIf {
            it.from == from && it.toExclusive == toExclusive && now - it.loadedAt < CACHE_MS
        }?.let { return it.dates }

        val dates = runCatching {
            // Android stores all-day event boundaries as UTC calendar dates.
            val begin = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = toExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val projection = arrayOf("title", "allDay", "begin", "end")
            val out = linkedSetOf<LocalDate>()
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { cursor ->
                val titleIndex = cursor.getColumnIndex("title")
                val allDayIndex = cursor.getColumnIndex("allDay")
                val beginIndex = cursor.getColumnIndex("begin")
                val endIndex = cursor.getColumnIndex("end")
                while (cursor.moveToNext()) {
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex)?.trim().orEmpty() else ""
                    val allDay = allDayIndex >= 0 && cursor.getInt(allDayIndex) == 1
                    if (!allDay || title != LEAVE_TITLE || beginIndex < 0 || endIndex < 0) continue
                    val eventStart = Instant.ofEpochMilli(cursor.getLong(beginIndex)).atZone(ZoneOffset.UTC).toLocalDate()
                    val rawEnd = Instant.ofEpochMilli(cursor.getLong(endIndex)).atZone(ZoneOffset.UTC).toLocalDate()
                    val eventEndExclusive = if (rawEnd.isAfter(eventStart)) rawEnd else eventStart.plusDays(1)
                    var date = eventStart
                    while (date.isBefore(eventEndExclusive)) {
                        if (!date.isBefore(from) && date.isBefore(toExclusive)) out += date
                        date = date.plusDays(1)
                    }
                }
            }
            out.toSet()
        }.getOrDefault(emptySet())

        cache = Cache(from, toExclusive, now, dates)
        return dates
    }

    fun isLeaveDay(context: Context, date: LocalDate): Boolean =
        date in leaveDates(context, date, date.plusDays(1))
}
