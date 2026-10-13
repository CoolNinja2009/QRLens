package com.qrlens.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val text: String,
    val label: String,
    val title: String,
    val time: Long,
    /** File name (in filesDir/sources) of the cropped source image, or null if none was saved. */
    val source: String? = null
)

/**
 * On-device scan history. Stored in SharedPreferences as a small JSON array, capped at [MAX].
 * Source-image crops live in filesDir/sources and are pruned when their entry leaves. Nothing
 * ever leaves the device; the user can clear it from the History screen.
 */
object HistoryStore {
    private const val FILE = "qrlens_history"
    private const val KEY = "entries"
    private const val MAX = 100

    fun add(ctx: Context, c: QrContent, source: String? = null) {
        if (c.raw.isBlank()) return
        val list = load(ctx).toMutableList()
        // De-dupe: drop any existing copy of this content (and its now-orphaned source crop).
        list.filter { it.text == c.raw }.forEach { deleteSource(ctx, it.source) }
        list.removeAll { it.text == c.raw }
        list.add(0, HistoryEntry(c.raw, c.label, c.title, System.currentTimeMillis(), source))
        while (list.size > MAX) {
            val evicted = list.removeAt(list.size - 1)
            deleteSource(ctx, evicted.source)
        }
        save(ctx, list)
    }

    fun load(ctx: Context): List<HistoryEntry> {
        val raw = p(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(
                    o.getString("t"), o.optString("l"), o.optString("n"), o.optLong("ts"),
                    o.optString("s").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        load(ctx).forEach { deleteSource(ctx, it.source) }
        p(ctx).edit().remove(KEY).apply()
    }

    fun remove(ctx: Context, entry: HistoryEntry) {
        deleteSource(ctx, entry.source)
        save(ctx, load(ctx).filterNot { it.text == entry.text && it.time == entry.time })
    }

    fun sourceFile(ctx: Context, entry: HistoryEntry): File? {
        val name = entry.source ?: return null
        val f = File(File(ctx.filesDir, ShareProvider.SOURCES), name)
        return if (f.exists()) f else null
    }

    private fun deleteSource(ctx: Context, name: String?) {
        if (name == null) return
        try {
            File(File(ctx.filesDir, ShareProvider.SOURCES), name).delete()
        } catch (_: Exception) {
        }
    }

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject().put("t", e.text).put("l", e.label).put("n", e.title).put("ts", e.time)
            if (e.source != null) o.put("s", e.source)
            arr.put(o)
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
