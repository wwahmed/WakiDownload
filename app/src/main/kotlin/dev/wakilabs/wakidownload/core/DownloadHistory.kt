package dev.wakilabs.wakidownload.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val name: String,
    val uri: String,
    val mime: String,
    val source: String,
    val kind: String,
    val bytes: Long,
    val time: Long,
    val target: String = "Downloads / WakiDownload",
    val nsfw: Boolean = false,
)

/** The "recent downloads" list: a small JSON file, newest first, capped so it never grows unbounded. */
class DownloadHistory(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()

    fun all(): List<HistoryEntry> = synchronized(lock) { read() }

    fun add(entries: List<HistoryEntry>) = synchronized(lock) {
        val merged = (entries.sortedByDescending { it.time } + read()).take(MAX)
        val arr = JSONArray()
        merged.forEach { e ->
            arr.put(JSONObject().apply {
                put("name", e.name); put("uri", e.uri); put("mime", e.mime); put("source", e.source)
                put("kind", e.kind); put("bytes", e.bytes); put("time", e.time); put("target", e.target); put("nsfw", e.nsfw)
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }

    private fun read(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.optString("name"), o.optString("uri"), o.optString("mime"), o.optString("source"), o.optString("kind"), o.optLong("bytes"), o.optLong("time"), o.optString("target", "Downloads / WakiDownload"), o.optBoolean("nsfw", false))
            }
        }.getOrDefault(emptyList())
    }

    companion object { const val MAX = 100 }
}
