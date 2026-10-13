package com.quickstamp.timerecorder.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

// COUNTDOWN / REQUIRED_WINDOW remain readable for existing v2.17 records.
enum class ReminderRuleKind { COUNTDOWN, REQUIRED_WINDOW, COMBINED }
// DAY: amount > 0 = before, amount == 0 = on day, amount < 0 = after.
// DAY_RANGE: signed offsets from anchor date, e.g. -2..+2 inclusive.
enum class NoticeUnit { DAY, DAY_RANGE, HOUR, WINDOW_DAILY }
enum class NoticeAnchor { EVENT, TASK, WINDOW_START, WINDOW_END }
enum class EligibleDays { EVERY_DAY, MON_FRI, MON_SAT_MORNING }
enum class PrepUnit { DAY, HOUR }

data class ReminderNotice(
    val id: String = UUID.randomUUID().toString(),
    val unit: NoticeUnit = NoticeUnit.DAY,
    val amount: Int = 0,
    val hour: Int = 9,
    val minute: Int = 0,
    val anchor: NoticeAnchor = NoticeAnchor.EVENT,
    val rangeStartOffsetDays: Int = -2,
    val rangeEndOffsetDays: Int = 2,
)

data class RelatedTaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startDaysBefore: Int = 18,
    val endDaysBefore: Int = 14,
    val eligibleDays: EligibleDays = EligibleDays.MON_FRI,
    val excludePublicHolidays: Boolean = true,
    val displayDaysBeforeWindow: Int = 0,
    val notices: List<ReminderNotice> = emptyList(),
)

data class PreparationStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val unit: PrepUnit = PrepUnit.DAY,
    val amount: Int = 1,
    val hour: Int = 9,
    val minute: Int = 0,
    val displayDaysBefore: Int = 0,
    val notices: List<ReminderNotice> = emptyList(),
)

data class ReminderRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keywords: List<String> = emptyList(),
    val kind: ReminderRuleKind = ReminderRuleKind.COUNTDOWN,
    val countdownDaysBefore: List<Int> = listOf(14, 3, 1, 0),
    val windowStartDaysBefore: Int = 18,
    val windowEndDaysBefore: Int = 14,
    val notifyHour: Int = 9,
    val notifyMinute: Int = 0,
    val enabled: Boolean = true,
    val notices: List<ReminderNotice> = listOf(28, 14, 7, 3, 1, 0).map { ReminderNotice(unit = NoticeUnit.DAY, amount = it) },
    val relatedTasks: List<RelatedTaskTemplate> = emptyList(),
    val preparationSteps: List<PreparationStep> = emptyList(),
    val persistentDaysBefore: Int = 0,
)

data class PrivateReminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val anchorDate: LocalDate,
    val ruleId: String,
    val enabled: Boolean = true,
    // All day if startTime == null. A timed event must have an end time.
    val startTime: LocalTime? = null,
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
)

enum class ReminderSource { PRIVATE, CALENDAR }
enum class ReminderTaskStatus { COUNTDOWN, WINDOW_ACTIVE, LAST_DAY, OVERDUE, UPCOMING }
enum class ReminderTaskKind { EVENT, RELATED, PREPARATION }

data class ReminderTask(
    val taskKey: String,
    val title: String,
    val source: ReminderSource,
    val sourceLabel: String,
    val anchorDate: LocalDate,
    val ruleId: String,
    val ruleName: String,
    val notifyHour: Int,
    val notifyMinute: Int,
    val status: ReminderTaskStatus,
    val daysUntilAnchor: Long,
    val windowStart: LocalDate? = null,
    val windowEnd: LocalDate? = null,
    val taskDate: LocalDate = anchorDate.minusDays(daysUntilAnchor),
    val taskKind: ReminderTaskKind = ReminderTaskKind.EVENT,
    val eligibleDates: List<LocalDate> = emptyList(),
    val saturdayMorningOnly: Boolean = false,
    val holidayDataMissing: Boolean = false,
    val canCompleteFrom: Instant? = null,
    val taskExpiresAt: Instant? = null,
)

object ReminderStore {
    private const val DB_NAME = "private_reminders.db"
    private const val DB_VERSION = 2
    private const val PREFS = "reminder_store_meta"
    private const val KEY_VERSION = "version"

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE rules(
                id TEXT PRIMARY KEY, name TEXT NOT NULL, keywords TEXT NOT NULL,
                kind TEXT NOT NULL, countdown_days TEXT NOT NULL,
                window_start INTEGER NOT NULL, window_end INTEGER NOT NULL,
                notify_hour INTEGER NOT NULL, notify_minute INTEGER NOT NULL,
                enabled INTEGER NOT NULL, notices TEXT NOT NULL DEFAULT '[]',
                related_tasks TEXT NOT NULL DEFAULT '[]',
                preparation_steps TEXT NOT NULL DEFAULT '[]', persistent_days INTEGER NOT NULL DEFAULT 0
            )""".trimIndent())
            db.execSQL("""CREATE TABLE private_reminders(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, anchor_date TEXT NOT NULL,
                rule_id TEXT NOT NULL, enabled INTEGER NOT NULL,
                start_time TEXT, end_date TEXT, end_time TEXT
            )""".trimIndent())
            db.execSQL("CREATE TABLE completions(task_key TEXT PRIMARY KEY, completed_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Non-destructive: preserve all v2.17 rules, private reminders and completions.
                db.execSQL("ALTER TABLE rules ADD COLUMN notices TEXT")
                db.execSQL("ALTER TABLE rules ADD COLUMN related_tasks TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE rules ADD COLUMN preparation_steps TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE rules ADD COLUMN persistent_days INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE private_reminders ADD COLUMN start_time TEXT")
                db.execSQL("ALTER TABLE private_reminders ADD COLUMN end_date TEXT")
                db.execSQL("ALTER TABLE private_reminders ADD COLUMN end_time TEXT")
                // notices=NULL identifies legacy rules; their prior settings are restored when read.
            }
        }
    }

    private fun db(context: Context) = Db(context.applicationContext)
    fun version(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0)
    private fun bump(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1).apply()
    }

    fun rules(context: Context): List<ReminderRule> {
        val out = mutableListOf<ReminderRule>()
        db(context).readableDatabase.query("rules", null, null, null, null, null, "name COLLATE NOCASE").use { c ->
            while (c.moveToNext()) {
                fun string(field: String): String = c.getString(c.getColumnIndexOrThrow(field)).orEmpty()
                fun number(field: String): Int = c.getInt(c.getColumnIndexOrThrow(field))
                val kind = runCatching { ReminderRuleKind.valueOf(string("kind")) }.getOrDefault(ReminderRuleKind.COUNTDOWN)
                val days = decodeDays(string("countdown_days"))
                val h = number("notify_hour").coerceIn(0, 23)
                val m = number("notify_minute").coerceIn(0, 59)
                val noticesRaw = c.getString(c.getColumnIndexOrThrow("notices"))
                val notices = if (noticesRaw == null) {
                    if (kind == ReminderRuleKind.REQUIRED_WINDOW) listOf(ReminderNotice(unit = NoticeUnit.WINDOW_DAILY, hour = h, minute = m))
                    else days.map { ReminderNotice(unit = NoticeUnit.DAY, amount = it, hour = h, minute = m) }
                } else decodeNotices(noticesRaw)
                out += ReminderRule(
                    id = string("id"), name = string("name"), keywords = decodeKeywords(string("keywords")),
                    kind = kind, countdownDaysBefore = days,
                    windowStartDaysBefore = number("window_start"), windowEndDaysBefore = number("window_end"),
                    notifyHour = h, notifyMinute = m,
                    enabled = number("enabled") != 0,
                    notices = notices,
                    relatedTasks = decodeRelated(string("related_tasks")),
                    preparationSteps = decodePreparation(string("preparation_steps")),
                    persistentDaysBefore = number("persistent_days").coerceAtLeast(0),
                )
            }
        }
        return out
    }

    fun upsertRule(context: Context, rule: ReminderRule) {
        val v = ContentValues().apply {
            put("id", rule.id)
            put("name", rule.name.trim())
            put("keywords", encodeKeywords(rule.keywords))
            put("kind", rule.kind.name)
            put("countdown_days", rule.countdownDaysBefore.distinct().sortedDescending().joinToString(","))
            put("window_start", rule.windowStartDaysBefore)
            put("window_end", rule.windowEndDaysBefore)
            put("notify_hour", rule.notifyHour)
            put("notify_minute", rule.notifyMinute)
            put("enabled", if (rule.enabled) 1 else 0)
            put("notices", encodeNotices(rule.notices))
            put("related_tasks", encodeRelated(rule.relatedTasks))
            put("preparation_steps", encodePreparation(rule.preparationSteps))
            put("persistent_days", rule.persistentDaysBefore.coerceAtLeast(0))
        }
        db(context).writableDatabase.insertWithOnConflict("rules", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        bump(context)
    }

    fun deleteRule(context: Context, id: String) {
        val database = db(context).writableDatabase
        database.beginTransaction()
        try {
            database.delete("private_reminders", "rule_id=?", arrayOf(id))
            database.delete("rules", "id=?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        bump(context)
    }

    fun privateReminders(context: Context): List<PrivateReminder> {
        val out = mutableListOf<PrivateReminder>()
        db(context).readableDatabase.query("private_reminders", null, null, null, null, null, "anchor_date ASC").use { c ->
            while (c.moveToNext()) {
                fun string(field: String): String? = c.getString(c.getColumnIndexOrThrow(field))
                val date = runCatching { LocalDate.parse(string("anchor_date")) }.getOrNull() ?: continue
                out += PrivateReminder(
                    id = string("id").orEmpty(), title = string("title").orEmpty(), anchorDate = date,
                    ruleId = string("rule_id").orEmpty(), enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0,
                    startTime = parseTime(string("start_time")),
                    endDate = string("end_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    endTime = parseTime(string("end_time")),
                )
            }
        }
        return out
    }

    fun upsertPrivateReminder(context: Context, reminder: PrivateReminder) {
        val v = ContentValues().apply {
            put("id", reminder.id)
            put("title", reminder.title.trim())
            put("anchor_date", reminder.anchorDate.toString())
            put("rule_id", reminder.ruleId)
            put("enabled", if (reminder.enabled) 1 else 0)
            put("start_time", reminder.startTime?.toString())
            put("end_date", reminder.endDate?.toString())
            put("end_time", reminder.endTime?.toString())
        }
        db(context).writableDatabase.insertWithOnConflict("private_reminders", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        bump(context)
    }

    fun deletePrivateReminder(context: Context, id: String) {
        db(context).writableDatabase.delete("private_reminders", "id=?", arrayOf(id))
        bump(context)
    }

    fun completedKeys(context: Context): Set<String> {
        val out = linkedSetOf<String>()
        db(context).readableDatabase.query("completions", arrayOf("task_key"), null, null, null, null, null).use { c ->
            val index = c.getColumnIndexOrThrow("task_key")
            while (c.moveToNext()) out += c.getString(index)
        }
        return out
    }

    fun isCompleted(context: Context, taskKey: String): Boolean {
        db(context).readableDatabase.query("completions", arrayOf("task_key"), "task_key=?", arrayOf(taskKey), null, null, null, "1").use {
            return it.moveToFirst()
        }
    }

    fun markCompleted(context: Context, taskKey: String, completedAt: Long = System.currentTimeMillis()) {
        val v = ContentValues().apply { put("task_key", taskKey); put("completed_at", completedAt) }
        db(context).writableDatabase.insertWithOnConflict("completions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        bump(context)
    }

    fun clearCompletion(context: Context, taskKey: String) {
        db(context).writableDatabase.delete("completions", "task_key=?", arrayOf(taskKey))
        bump(context)
    }

    private fun parseTime(raw: String?): LocalTime? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    private fun encodeKeywords(values: List<String>) = values.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\u001F")
    private fun decodeKeywords(raw: String) = raw.split('\u001F').map { it.trim() }.filter { it.isNotBlank() }
    private fun decodeDays(raw: String) = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it.coerceAtLeast(0) }.distinct().sortedDescending().ifEmpty { listOf(14,3,1,0) }

    private fun encodeNotices(items: List<ReminderNotice>): String = JSONArray().apply {
        items.forEach { n -> put(JSONObject().apply {
            put("id", n.id); put("unit", n.unit.name)
            put("amount", if (n.unit == NoticeUnit.DAY) n.amount.coerceIn(-1825, 1825) else n.amount.coerceIn(0, 1825))
            put("hour", n.hour.coerceIn(0, 23)); put("minute", n.minute.coerceIn(0, 59)); put("anchor", n.anchor.name)
            put("rangeFromDays", n.rangeStartOffsetDays.coerceIn(-1825, 1825))
            put("rangeToDays", n.rangeEndOffsetDays.coerceIn(-1825, 1825))
        }) }
    }.toString()

    private fun decodeNotices(raw: String): List<ReminderNotice> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                add(ReminderNotice(
                    id = x.optString("id").ifBlank { UUID.randomUUID().toString() },
                    unit = runCatching { NoticeUnit.valueOf(x.optString("unit")) }.getOrDefault(NoticeUnit.DAY),
                    amount = x.optInt("amount").coerceIn(-1825, 1825).let { a ->
                        if (x.optString("unit") == NoticeUnit.DAY.name) a else a.coerceAtLeast(0)
                    },
                    hour = x.optInt("hour", 9).coerceIn(0, 23), minute = x.optInt("minute").coerceIn(0, 59),
                    anchor = runCatching { NoticeAnchor.valueOf(x.optString("anchor")) }.getOrDefault(NoticeAnchor.EVENT),
                    rangeStartOffsetDays = x.optInt("rangeFromDays", -2).coerceIn(-1825, 1825),
                    rangeEndOffsetDays = x.optInt("rangeToDays", 2).coerceIn(-1825, 1825),
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeRelated(items: List<RelatedTaskTemplate>) = JSONArray().apply {
        items.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("start", t.startDaysBefore); put("end", t.endDaysBefore)
            put("days", t.eligibleDays.name); put("excludePH", t.excludePublicHolidays)
            put("displayLead", t.displayDaysBeforeWindow); put("notices", JSONArray(encodeNotices(t.notices)))
        }) }
    }.toString()
    private fun decodeRelated(raw: String): List<RelatedTaskTemplate> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                if (x.optString("title").isBlank()) continue
                add(RelatedTaskTemplate(
                    id = x.optString("id").ifBlank { UUID.randomUUID().toString() }, title = x.optString("title"),
                    startDaysBefore = x.optInt("start", 18).coerceAtLeast(0), endDaysBefore = x.optInt("end", 14).coerceAtLeast(0),
                    eligibleDays = runCatching { EligibleDays.valueOf(x.optString("days")) }.getOrDefault(EligibleDays.MON_FRI),
                    excludePublicHolidays = x.optBoolean("excludePH", true), displayDaysBeforeWindow = x.optInt("displayLead").coerceAtLeast(0),
                    notices = decodeNotices(x.optJSONArray("notices")?.toString() ?: "[]"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodePreparation(items: List<PreparationStep>) = JSONArray().apply {
        items.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("title", s.title); put("unit", s.unit.name); put("amount", s.amount)
            put("hour", s.hour); put("minute", s.minute); put("displayLead", s.displayDaysBefore)
            put("notices", JSONArray(encodeNotices(s.notices)))
        }) }
    }.toString()
    private fun decodePreparation(raw: String): List<PreparationStep> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                if (x.optString("title").isBlank()) continue
                add(PreparationStep(
                    id = x.optString("id").ifBlank { UUID.randomUUID().toString() }, title = x.optString("title"),
                    unit = runCatching { PrepUnit.valueOf(x.optString("unit")) }.getOrDefault(PrepUnit.DAY),
                    amount = x.optInt("amount").coerceAtLeast(0), hour = x.optInt("hour", 9).coerceIn(0, 23),
                    minute = x.optInt("minute").coerceIn(0, 59), displayDaysBefore = x.optInt("displayLead").coerceAtLeast(0),
                    notices = decodeNotices(x.optJSONArray("notices")?.toString() ?: "[]"),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
