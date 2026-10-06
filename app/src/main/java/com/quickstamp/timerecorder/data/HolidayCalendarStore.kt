package com.quickstamp.timerecorder.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val HOLIDAY_PREFS = "hk_public_holidays_v1"
private const val HOLIDAY_KEY = "cache"
private const val HOLIDAY_URL = "https://www.1823.gov.hk/common/ical/tc.ics"

data class PublicHoliday(
    val date: LocalDate,
    val name: String,
)

data class HolidayCalendarCache(
    val holidays: List<PublicHoliday> = emptyList(),
    val checkedAt: Long = 0L,
    val successfulAt: Long = 0L,
    val dataUpdatedAt: Long = 0L,
    val sourceUpdatedAt: Long = 0L,
    val contentHash: String = "",
    val etag: String = "",
    val lastModifiedHeader: String = "",
    val errorMessage: String? = null,
) {
    val displayUpdatedAt: Long get() = sourceUpdatedAt.takeIf { it > 0L } ?: dataUpdatedAt
}

object HolidayCalendarStore {
    const val sourceName = "香港政府 1823 公眾假期 iCal"
    const val sourceUrl = HOLIDAY_URL

    fun load(context: Context): HolidayCalendarCache {
        val raw = context.getSharedPreferences(HOLIDAY_PREFS, Context.MODE_PRIVATE)
            .getString(HOLIDAY_KEY, null)
            ?: return HolidayCalendarCache()
        return parseCache(raw)
    }

    fun shouldRefresh(cache: HolidayCalendarCache, now: Long = System.currentTimeMillis()): Boolean =
        cache.checkedAt == 0L || now - cache.checkedAt >= 7L * 24L * 60L * 60L * 1000L

    /** Network call. Invoke from a background dispatcher/thread. */
    fun refresh(context: Context): HolidayCalendarCache {
        val old = load(context)
        val now = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(HOLIDAY_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                setRequestProperty("User-Agent", "TimeRecorder-Android/2.10")
                if (old.etag.isNotBlank()) setRequestProperty("If-None-Match", old.etag)
                if (old.lastModifiedHeader.isNotBlank()) setRequestProperty("If-Modified-Since", old.lastModifiedHeader)
            }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    old.copy(
                        checkedAt = now,
                        successfulAt = now,
                        errorMessage = null,
                    ).also { save(context, it) }
                }
                in 200..299 -> {
                    val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = parseIcs(raw)
                    require(parsed.holidays.isNotEmpty()) { "官方 iCal 沒有可用假期資料" }
                    val hash = sha256(raw)
                    val changed = hash != old.contentHash || old.holidays.isEmpty()
                    val httpModified = parseHttpDate(connection.getHeaderField("Last-Modified"))
                    val sourceModified = maxOf(httpModified, parsed.latestModified)
                    HolidayCalendarCache(
                        holidays = parsed.holidays,
                        checkedAt = now,
                        successfulAt = now,
                        dataUpdatedAt = if (changed) now else old.dataUpdatedAt,
                        sourceUpdatedAt = sourceModified.takeIf { it > 0L } ?: old.sourceUpdatedAt,
                        contentHash = hash,
                        etag = connection.getHeaderField("ETag").orEmpty(),
                        lastModifiedHeader = connection.getHeaderField("Last-Modified").orEmpty(),
                        errorMessage = null,
                    ).also { save(context, it) }
                }
                else -> error("HTTP ${connection.responseCode}")
            }
        } catch (t: Throwable) {
            old.copy(
                checkedAt = now,
                errorMessage = t.message?.take(160) ?: t.javaClass.simpleName,
            ).also { save(context, it) }
        } finally {
            connection?.disconnect()
        }
    }

    private fun save(context: Context, cache: HolidayCalendarCache) {
        val json = JSONObject().apply {
            put("checkedAt", cache.checkedAt)
            put("successfulAt", cache.successfulAt)
            put("dataUpdatedAt", cache.dataUpdatedAt)
            put("sourceUpdatedAt", cache.sourceUpdatedAt)
            put("contentHash", cache.contentHash)
            put("etag", cache.etag)
            put("lastModifiedHeader", cache.lastModifiedHeader)
            put("errorMessage", cache.errorMessage ?: "")
            put("holidays", JSONArray().apply {
                cache.holidays.forEach { holiday ->
                    put(JSONObject().apply {
                        put("date", holiday.date.toString())
                        put("name", holiday.name)
                    })
                }
            })
        }
        context.getSharedPreferences(HOLIDAY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(HOLIDAY_KEY, json.toString()).apply()
    }

    private fun parseCache(raw: String): HolidayCalendarCache = runCatching {
        val root = JSONObject(raw)
        val list = buildList {
            val array = root.optJSONArray("holidays") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val date = runCatching { LocalDate.parse(item.optString("date")) }.getOrNull() ?: continue
                val name = item.optString("name").trim()
                if (name.isNotBlank()) add(PublicHoliday(date, name))
            }
        }
        HolidayCalendarCache(
            holidays = list.sortedBy { it.date },
            checkedAt = root.optLong("checkedAt"),
            successfulAt = root.optLong("successfulAt"),
            dataUpdatedAt = root.optLong("dataUpdatedAt"),
            sourceUpdatedAt = root.optLong("sourceUpdatedAt"),
            contentHash = root.optString("contentHash"),
            etag = root.optString("etag"),
            lastModifiedHeader = root.optString("lastModifiedHeader"),
            errorMessage = root.optString("errorMessage").ifBlank { null },
        )
    }.getOrDefault(HolidayCalendarCache())

    private data class IcsParseResult(val holidays: List<PublicHoliday>, val latestModified: Long)

    private fun parseIcs(raw: String): IcsParseResult {
        val unfolded = mutableListOf<String>()
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + line.drop(1)
            } else {
                unfolded += line
            }
        }

        val holidays = mutableListOf<PublicHoliday>()
        var inEvent = false
        var date: LocalDate? = null
        var summary: String? = null
        var modified = 0L
        var latestModified = 0L

        fun finishEvent() {
            val d = date
            val s = summary?.let(::unescapeIcs)?.trim()
            if (d != null && !s.isNullOrBlank()) holidays += PublicHoliday(d, s)
            if (modified > latestModified) latestModified = modified
            date = null
            summary = null
            modified = 0L
        }

        unfolded.forEach { rawLine ->
            val line = rawLine.trimEnd()
            when (line) {
                "BEGIN:VEVENT" -> {
                    inEvent = true
                    date = null
                    summary = null
                    modified = 0L
                }
                "END:VEVENT" -> {
                    if (inEvent) finishEvent()
                    inEvent = false
                }
                else -> if (inEvent) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val property = line.substring(0, colon).substringBefore(';').uppercase()
                    val value = line.substring(colon + 1).trim()
                    when (property) {
                        "DTSTART" -> date = parseIcsDate(value)
                        "SUMMARY" -> summary = value
                        "LAST-MODIFIED" -> modified = parseIcsInstant(value)
                    }
                }
            }
        }
        return IcsParseResult(
            holidays = holidays.distinctBy { it.date to it.name }.sortedBy { it.date },
            latestModified = latestModified,
        )
    }

    private fun parseIcsDate(value: String): LocalDate? {
        val digits = value.filter(Char::isDigit)
        if (digits.length < 8) return null
        return runCatching {
            LocalDate.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt(),
            )
        }.getOrNull()
    }

    private fun parseIcsInstant(value: String): Long = runCatching {
        when {
            value.endsWith("Z") -> Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX").parse(value)).toEpochMilli()
            else -> 0L
        }
    }.getOrDefault(0L)

    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)).toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun unescapeIcs(value: String): String = value
        .replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}
