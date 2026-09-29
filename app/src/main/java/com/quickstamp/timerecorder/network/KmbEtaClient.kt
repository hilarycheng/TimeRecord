package com.quickstamp.timerecorder.network

import android.content.Context
import com.quickstamp.timerecorder.data.AppStore
import com.quickstamp.timerecorder.model.CommuteMode
import com.quickstamp.timerecorder.model.EtaArrival
import com.quickstamp.timerecorder.model.EtaStops
import com.quickstamp.timerecorder.model.HkTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.Locale
import com.quickstamp.timerecorder.widget.TimeRecorderWidgetProvider

object KmbEtaClient {
    private const val BASE = "https://data.etabus.gov.hk/v1/transport/kmb"
    private const val STOP_CACHE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val TIMEOUT_MS = 10_000
    private val routes = listOf("38", "42C")

    data class RefreshResult(
        val mode: CommuteMode,
        val routes: Map<String, List<EtaArrival>>,
        val updatedAt: Long,
    )

    fun refresh(context: Context, mode: CommuteMode): Result<RefreshResult> {
        val app = context.applicationContext
        val startedAt = HkTime.now()
        AppStore.markEtaRefreshing(app, mode, startedAt)
        return runCatching {
            val stopId = resolveStopId(app, mode)
            val now = HkTime.now()
            val eta = routes.associateWith { route -> fetchEta(stopId, route, mode, now) }
            AppStore.updateEtaSuccess(app, mode, eta, HkTime.now())
            runCatching { TimeRecorderWidgetProvider.updateAll(app) }
            RefreshResult(mode, eta, HkTime.now())
        }.onFailure { error ->
            AppStore.updateEtaFailure(app, mode, error.message ?: "ETA refresh failed", HkTime.now())
            runCatching { TimeRecorderWidgetProvider.updateAll(app) }
        }
    }

    private fun resolveStopId(context: Context, mode: CommuteMode): String {
        val state = AppStore.load(context)
        val cached = if (mode == CommuteMode.WORK) state.etaStops.work else state.etaStops.home
        if (!cached.isNullOrBlank() && HkTime.now() - state.etaStops.resolvedAt < STOP_CACHE_MS) return cached

        val stopsById = fetchStopNames()
        val direction = resolveDirectionFor38(mode)
        val routeStops = getJson("$BASE/route-stop/38/$direction/1").optJSONArray("data") ?: JSONArray()
        val keywordTc = if (mode == CommuteMode.WORK) "德福花園" else "屏麗徑"
        val keywordEn = if (mode == CommuteMode.WORK) "TELFORD" else "PING LAI"

        var match: String? = null
        for (i in 0 until routeStops.length()) {
            val item = routeStops.optJSONObject(i) ?: continue
            val stopId = item.optString("stop")
            val names = stopsById[stopId] ?: continue
            if (names.tc.contains(keywordTc) || names.en.uppercase(Locale.ROOT).contains(keywordEn)) {
                match = stopId
                break
            }
        }
        val stopId = match ?: error("找不到${if (mode == CommuteMode.WORK) "德福花園" else "屏麗徑南行"}巴士站")
        val old = AppStore.load(context).etaStops
        AppStore.setEtaStops(
            context,
            EtaStops(
                work = if (mode == CommuteMode.WORK) stopId else old.work,
                home = if (mode == CommuteMode.HOME) stopId else old.home,
                resolvedAt = HkTime.now(),
            )
        )
        return stopId
    }

    private data class StopName(val tc: String, val en: String)

    private fun fetchStopNames(): Map<String, StopName> {
        val array = getJson("$BASE/stop").optJSONArray("data") ?: JSONArray()
        val result = HashMap<String, StopName>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("stop")
            if (id.isBlank()) continue
            result[id] = StopName(item.optString("name_tc"), item.optString("name_en"))
        }
        return result
    }

    private fun resolveDirectionFor38(mode: CommuteMode): String {
        val wantedTc = if (mode == CommuteMode.WORK) "葵盛" else "平田"
        val wantedEn = if (mode == CommuteMode.WORK) "KWAI SHING" else "PING TIN"
        for (direction in listOf("inbound", "outbound")) {
            val root = getJson("$BASE/route/38/$direction/1")
            val data = root.opt("data")
            val item = when (data) {
                is JSONObject -> data
                is JSONArray -> data.optJSONObject(0)
                else -> null
            } ?: continue
            val tc = item.optString("dest_tc")
            val en = item.optString("dest_en").uppercase(Locale.ROOT)
            if (tc.contains(wantedTc) || en.contains(wantedEn)) return direction
        }
        error("找不到 38 對應方向")
    }

    private fun fetchEta(stopId: String, route: String, mode: CommuteMode, now: Long): List<EtaArrival> {
        val array = getJson("$BASE/eta/$stopId/$route/1").optJSONArray("data") ?: JSONArray()
        val result = mutableListOf<EtaArrival>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!destinationMatches(route, mode, item.optString("dest_tc"), item.optString("dest_en"))) continue
            val rawEta = item.optString("eta")
            if (rawEta.isBlank() || rawEta == "null") continue
            val timestamp = runCatching { OffsetDateTime.parse(rawEta).toInstant().toEpochMilli() }.getOrNull() ?: continue
            if (timestamp < now - 120_000L) continue
            result += EtaArrival(
                timestamp = timestamp,
                destination = item.optString("dest_tc").ifBlank { item.optString("dest_en") },
                remark = item.optString("rmk_tc").ifBlank { item.optString("rmk_en") },
            )
        }
        return result.distinctBy { it.timestamp }.sortedBy { it.timestamp }.take(3)
    }

    private fun destinationMatches(route: String, mode: CommuteMode, tc: String, enRaw: String): Boolean {
        val en = enRaw.uppercase(Locale.ROOT)
        return when (mode) {
            CommuteMode.WORK -> when (route) {
                "38" -> tc.contains("葵盛") || en.contains("KWAI SHING")
                "42C" -> tc.contains("長亨") || en.contains("CHEUNG HANG")
                else -> true
            }
            CommuteMode.HOME -> when (route) {
                "38" -> tc.contains("平田") || en.contains("PING TIN")
                "42C" -> tc.contains("藍田") || en.contains("LAM TIN")
                else -> true
            }
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TimeRecorder/2.7 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) error("ETA HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
