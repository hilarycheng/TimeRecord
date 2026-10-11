package com.quickstamp.timerecorder.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.UUID

enum class ReminderRuleKind { COUNTDOWN, REQUIRED_WINDOW }

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
)

data class PrivateReminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val anchorDate: LocalDate,
    val ruleId: String,
    val enabled: Boolean = true,
)

enum class ReminderSource { PRIVATE, CALENDAR }
enum class ReminderTaskStatus { COUNTDOWN, WINDOW_ACTIVE, LAST_DAY, OVERDUE }

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
)

object ReminderStore {
    private const val DB_NAME = "private_reminders.db"
    private const val DB_VERSION = 1
    private const val PREFS = "reminder_store_meta"
    private const val KEY_VERSION = "version"

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE rules(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    keywords TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    countdown_days TEXT NOT NULL,
                    window_start INTEGER NOT NULL,
                    window_end INTEGER NOT NULL,
                    notify_hour INTEGER NOT NULL,
                    notify_minute INTEGER NOT NULL,
                    enabled INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE private_reminders(
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    anchor_date TEXT NOT NULL,
                    rule_id TEXT NOT NULL,
                    enabled INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE completions(
                    task_key TEXT PRIMARY KEY,
                    completed_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
            val id = c.getColumnIndexOrThrow("id")
            val name = c.getColumnIndexOrThrow("name")
            val keywords = c.getColumnIndexOrThrow("keywords")
            val kind = c.getColumnIndexOrThrow("kind")
            val countdown = c.getColumnIndexOrThrow("countdown_days")
            val ws = c.getColumnIndexOrThrow("window_start")
            val we = c.getColumnIndexOrThrow("window_end")
            val h = c.getColumnIndexOrThrow("notify_hour")
            val m = c.getColumnIndexOrThrow("notify_minute")
            val enabled = c.getColumnIndexOrThrow("enabled")
            while (c.moveToNext()) {
                out += ReminderRule(
                    id = c.getString(id),
                    name = c.getString(name),
                    keywords = decodeKeywords(c.getString(keywords)),
                    kind = runCatching { ReminderRuleKind.valueOf(c.getString(kind)) }.getOrDefault(ReminderRuleKind.COUNTDOWN),
                    countdownDaysBefore = decodeDays(c.getString(countdown)),
                    windowStartDaysBefore = c.getInt(ws),
                    windowEndDaysBefore = c.getInt(we),
                    notifyHour = c.getInt(h).coerceIn(0, 23),
                    notifyMinute = c.getInt(m).coerceIn(0, 59),
                    enabled = c.getInt(enabled) != 0,
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
            val id = c.getColumnIndexOrThrow("id")
            val title = c.getColumnIndexOrThrow("title")
            val date = c.getColumnIndexOrThrow("anchor_date")
            val ruleId = c.getColumnIndexOrThrow("rule_id")
            val enabled = c.getColumnIndexOrThrow("enabled")
            while (c.moveToNext()) {
                val parsed = runCatching { LocalDate.parse(c.getString(date)) }.getOrNull() ?: continue
                out += PrivateReminder(c.getString(id), c.getString(title), parsed, c.getString(ruleId), c.getInt(enabled) != 0)
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

    private fun encodeKeywords(values: List<String>): String = values.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\u001F")
    private fun decodeKeywords(raw: String): List<String> = raw.split('\u001F').map { it.trim() }.filter { it.isNotBlank() }
    private fun decodeDays(raw: String): List<Int> = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it.coerceAtLeast(0) }.distinct().sortedDescending().ifEmpty { listOf(14,3,1,0) }
}
