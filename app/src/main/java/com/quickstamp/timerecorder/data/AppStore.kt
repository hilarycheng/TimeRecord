package com.quickstamp.timerecorder.data

import android.content.Context
import com.quickstamp.timerecorder.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

object AppStore {
    private const val PREFS = "time_recorder_native_v3_clean"
    private const val KEY_STATE = "state_json"
    private const val KEY_VERSION = "version"
    private val lock = Any()

    fun version(context: Context): Int = prefs(context).getInt(KEY_VERSION, 0)

    fun load(context: Context): RecorderState = synchronized(lock) {
        parseState(prefs(context).getString(KEY_STATE, null))
    }

    fun mutate(context: Context, block: (RecorderState) -> RecorderState): RecorderState = synchronized(lock) {
        val old = parseState(prefs(context).getString(KEY_STATE, null))
        val next = block(old)
        if (next != old) persist(context, next)
        next
    }

    fun record(
        context: Context,
        label: String,
        kind: EventKind = EventKind.DEFAULT,
        timestamp: Long = HkTime.now(),
        route: String? = null,
        commute: CommuteMode? = null,
        terminal: Boolean = false,
        auto: Boolean = false,
    ): RecorderEvent {
        val event = RecorderEvent(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            label = label,
            kind = kind,
            route = route,
            commute = commute,
            terminal = terminal,
            auto = auto,
        )
        mutate(context) { state -> state.copy(events = (state.events + event).sortedBy { it.timestamp }) }
        return event
    }

    fun updateEvent(context: Context, event: RecorderEvent) {
        mutate(context) { state ->
            state.copy(events = state.events.map { if (it.id == event.id) event else it }.sortedBy { it.timestamp })
        }
    }

    fun deleteEvent(context: Context, id: String) {
        mutate(context) { it.copy(events = it.events.filterNot { e -> e.id == id }) }
    }

    fun undo(context: Context, date: LocalDate): RecorderEvent? {
        var removed: RecorderEvent? = null
        mutate(context) { state ->
            removed = state.events.filter { HkTime.date(it.timestamp) == date }.maxByOrNull { it.timestamp }
            if (removed == null) state else state.copy(events = state.events.filterNot { it.id == removed!!.id })
        }
        return removed
    }

    fun setShowSeconds(context: Context, enabled: Boolean) {
        mutate(context) { it.copy(settings = it.settings.copy(showSeconds = enabled)) }
    }

    fun setTracking(context: Context, active: Boolean, mode: CommuteMode? = null) {
        val now = HkTime.now()
        mutate(context) { state ->
            state.copy(
                tracking = if (active) TrackingState(
                    active = true,
                    mode = mode ?: state.tracking.mode ?: HkTime.modeAt(now, state.settings.morningCutoffHour),
                    startedAt = if (state.tracking.active) state.tracking.startedAt else now,
                    heartbeatAt = now,
                ) else TrackingState()
            )
        }
    }

    fun updateTrackingHeartbeat(context: Context, timestamp: Long = HkTime.now()) {
        mutate(context) { state ->
            if (!state.tracking.active) state
            else state.copy(tracking = state.tracking.copy(heartbeatAt = timestamp))
        }
    }

    fun setEtaStops(context: Context, stops: EtaStops) {
        mutate(context) { it.copy(etaStops = stops) }
    }

    fun markEtaRefreshing(context: Context, mode: CommuteMode, startedAt: Long) {
        mutate(context) { state ->
            val old = snapshotFor(state, mode)
            state.withSnapshot(mode, old.copy(refreshingStartedAt = startedAt))
        }
    }

    fun updateEtaSuccess(context: Context, mode: CommuteMode, routes: Map<String, List<EtaArrival>>, updatedAt: Long) {
        mutate(context) { state ->
            state.withSnapshot(
                mode,
                EtaSnapshot(
                    updatedAt = updatedAt,
                    routes = routes,
                    errorAt = 0L,
                    errorMessage = null,
                    refreshingStartedAt = 0L,
                )
            )
        }
    }

    fun updateEtaFailure(context: Context, mode: CommuteMode, message: String, timestamp: Long) {
        mutate(context) { state ->
            val old = snapshotFor(state, mode)
            state.withSnapshot(
                mode,
                old.copy(
                    errorAt = timestamp,
                    errorMessage = message.take(160),
                    refreshingStartedAt = 0L,
                )
            )
        }
    }


    fun exportJson(context: Context): String = synchronized(lock) {
        stateToJson(parseState(prefs(context).getString(KEY_STATE, null))).toString(2)
    }

    fun restoreJson(context: Context, raw: String): Result<RecorderState> = synchronized(lock) {
        runCatching {
            val parsed = parseStateStrict(raw)
            persist(context, parsed)
            parsed
        }
    }

    private fun parseStateStrict(raw: String): RecorderState {
        val root = JSONObject(raw)
        require(root.has("events")) { "Backup missing events" }
        return parseState(root.toString())
    }

    fun autoClosePastDays(context: Context, now: Long = HkTime.now()) {
        val today = HkTime.today(now)
        mutate(context) { state ->
            val grouped = state.events.groupBy { HkTime.date(it.timestamp) }
            val additions = grouped.entries
                .filter { (date, events) -> date < today && events.isNotEmpty() && events.none { it.terminal } }
                .map { (date, _) ->
                    RecorderEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = HkTime.at(date, 18, 0, 0),
                        label = "到屋企",
                        kind = EventKind.DEFAULT,
                        commute = CommuteMode.HOME,
                        terminal = true,
                        auto = true,
                    )
                }
            if (additions.isEmpty()) state else state.copy(events = (state.events + additions).sortedBy { it.timestamp })
        }
    }

    private fun RecorderState.withSnapshot(mode: CommuteMode, snapshot: EtaSnapshot): RecorderState =
        if (mode == CommuteMode.WORK) copy(etaWork = snapshot) else copy(etaHome = snapshot)

    private fun snapshotFor(state: RecorderState, mode: CommuteMode): EtaSnapshot =
        if (mode == CommuteMode.WORK) state.etaWork else state.etaHome

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persist(context: Context, state: RecorderState) {
        val p = prefs(context)
        p.edit()
            .putString(KEY_STATE, stateToJson(state).toString())
            .putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1)
            .apply()
    }

    private fun parseState(raw: String?): RecorderState {
        if (raw.isNullOrBlank()) return RecorderState()
        return runCatching {
            val root = JSONObject(raw)
            val events = mutableListOf<RecorderEvent>()
            val array = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                events += RecorderEvent(
                    id = e.getString("id"),
                    timestamp = e.getLong("timestamp"),
                    label = e.getString("label"),
                    kind = runCatching { EventKind.valueOf(e.optString("kind", "DEFAULT")) }.getOrDefault(EventKind.DEFAULT),
                    route = e.optString("route").ifBlank { null },
                    commute = e.optString("commute").takeIf { it.isNotBlank() }?.let { runCatching { CommuteMode.valueOf(it) }.getOrNull() },
                    terminal = e.optBoolean("terminal", false),
                    auto = e.optBoolean("auto", false),
                )
            }
            val settingsJson = root.optJSONObject("settings") ?: JSONObject()
            RecorderState(
                events = events.sortedBy { it.timestamp },
                settings = RecorderSettings(
                    showSeconds = settingsJson.optBoolean("showSeconds", true),
                    morningCutoffHour = settingsJson.optInt("morningCutoffHour", 10).coerceIn(0, 23),
                ),
                etaWork = parseSnapshot(root.optJSONObject("etaWork")),
                etaHome = parseSnapshot(root.optJSONObject("etaHome")),
                tracking = parseTracking(root.optJSONObject("tracking")),
                etaStops = parseStops(root.optJSONObject("etaStops")),
            )
        }.getOrDefault(RecorderState())
    }

    private fun stateToJson(state: RecorderState): JSONObject = JSONObject().apply {
        put("events", JSONArray().apply {
            state.events.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("timestamp", e.timestamp)
                    put("label", e.label)
                    put("kind", e.kind.name)
                    put("route", e.route ?: "")
                    put("commute", e.commute?.name ?: "")
                    put("terminal", e.terminal)
                    put("auto", e.auto)
                })
            }
        })
        put("settings", JSONObject().apply {
            put("showSeconds", state.settings.showSeconds)
            put("morningCutoffHour", state.settings.morningCutoffHour)
        })
        put("etaWork", snapshotToJson(state.etaWork))
        put("etaHome", snapshotToJson(state.etaHome))
        put("tracking", JSONObject().apply {
            put("active", state.tracking.active)
            put("mode", state.tracking.mode?.name ?: "")
            put("startedAt", state.tracking.startedAt)
            put("heartbeatAt", state.tracking.heartbeatAt)
        })
        put("etaStops", JSONObject().apply {
            put("work", state.etaStops.work ?: "")
            put("home", state.etaStops.home ?: "")
            put("resolvedAt", state.etaStops.resolvedAt)
        })
    }

    private fun snapshotToJson(s: EtaSnapshot) = JSONObject().apply {
        put("updatedAt", s.updatedAt)
        put("errorAt", s.errorAt)
        put("errorMessage", s.errorMessage ?: "")
        put("refreshingStartedAt", s.refreshingStartedAt)
        put("routes", JSONObject().apply {
            s.routes.forEach { (route, arrivals) ->
                put(route, JSONArray().apply {
                    arrivals.forEach { a ->
                        put(JSONObject().apply {
                            put("timestamp", a.timestamp)
                            put("destination", a.destination)
                            put("remark", a.remark)
                        })
                    }
                })
            }
        })
    }

    private fun parseSnapshot(o: JSONObject?): EtaSnapshot {
        if (o == null) return EtaSnapshot()
        val routes = mutableMapOf<String, List<EtaArrival>>()
        val r = o.optJSONObject("routes") ?: JSONObject()
        r.keys().forEach { route ->
            val arr = r.optJSONArray(route) ?: JSONArray()
            routes[route] = buildList {
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    add(EtaArrival(x.optLong("timestamp"), x.optString("destination"), x.optString("remark")))
                }
            }
        }
        return EtaSnapshot(
            updatedAt = o.optLong("updatedAt", 0L),
            routes = routes,
            errorAt = o.optLong("errorAt", 0L),
            errorMessage = o.optString("errorMessage").ifBlank { null },
            refreshingStartedAt = o.optLong("refreshingStartedAt", 0L),
        )
    }

    private fun parseTracking(o: JSONObject?): TrackingState {
        if (o == null) return TrackingState()
        return TrackingState(
            active = o.optBoolean("active", false),
            mode = o.optString("mode").takeIf { it.isNotBlank() }?.let { runCatching { CommuteMode.valueOf(it) }.getOrNull() },
            startedAt = o.optLong("startedAt", 0L),
            heartbeatAt = o.optLong("heartbeatAt", 0L),
        )
    }

    private fun parseStops(o: JSONObject?): EtaStops {
        if (o == null) return EtaStops()
        return EtaStops(
            work = o.optString("work").ifBlank { null },
            home = o.optString("home").ifBlank { null },
            resolvedAt = o.optLong("resolvedAt", 0L),
        )
    }
}
