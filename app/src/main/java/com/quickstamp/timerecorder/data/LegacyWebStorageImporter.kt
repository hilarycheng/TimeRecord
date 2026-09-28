package com.quickstamp.timerecorder.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * Best-effort importer for v1.x, which stored one JSON object in WebView localStorage.
 * We deliberately do not instantiate WebView in the Compose build. Chromium LevelDB log
 * files usually retain the most recent value in an uncompressed log record; this scanner
 * looks for a valid recorder JSON object in UTF-8/UTF-16 encodings.
 */
object LegacyWebStorageImporter {
    private const val LEGACY_KEY = "quick_time_recorder_v1"

    fun findLegacyJson(context: Context): JSONObject? {
        val root = File(context.applicationInfo.dataDir, "app_webview")
        if (!root.exists()) return null

        val files = root.walkTopDown()
            .filter { it.isFile && (it.extension in setOf("log", "ldb", "sst") || it.name == "LOG") }
            .sortedByDescending { it.lastModified() }
            .take(40)

        for (file in files) {
            val bytes = runCatching {
                if (file.length() > 16L * 1024L * 1024L) return@runCatching null
                file.readBytes()
            }.getOrNull() ?: continue

            val decoded = listOf(
                runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull(),
                runCatching { bytes.toString(Charset.forName("UTF-16LE")) }.getOrNull(),
                runCatching { bytes.toString(Charset.forName("UTF-16BE")) }.getOrNull(),
            ).filterNotNull()

            decoded.forEach { text ->
                extractCandidates(text).forEach { raw ->
                    val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
                    if (parsed.optJSONArray("events") != null && parsed.optJSONObject("settings") != null) {
                        return parsed
                    }
                }
            }
        }
        return null
    }

    private fun extractCandidates(text: String): Sequence<String> = sequence {
        val anchors = buildList {
            var from = 0
            while (true) {
                val i = text.indexOf(LEGACY_KEY, from)
                if (i < 0) break
                add(i)
                from = i + LEGACY_KEY.length
            }
            var j = 0
            while (true) {
                val i = text.indexOf("\"events\"", j)
                if (i < 0) break
                add(i)
                j = i + 8
            }
        }.distinct().sortedDescending()

        for (anchor in anchors) {
            val start = text.lastIndexOf('{', anchor.coerceAtMost(text.lastIndex))
                .takeIf { it >= 0 } ?: text.indexOf('{', anchor).takeIf { it >= 0 } ?: continue
            extractJsonObject(text, start)?.let { yield(it) }
        }

        // Fallback: scan a limited number of object starts near the end of the file.
        var pos = text.length - 1
        var attempts = 0
        while (pos >= 0 && attempts < 24) {
            pos = text.lastIndexOf('{', pos)
            if (pos < 0) break
            extractJsonObject(text, pos)?.let { candidate ->
                if (candidate.contains("\"events\"") && candidate.contains("\"settings\"")) yield(candidate)
            }
            pos--
            attempts++
        }
    }

    private fun extractJsonObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }
}
