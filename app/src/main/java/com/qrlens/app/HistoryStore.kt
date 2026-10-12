package com.qrlens.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val text: String, val label: String, val title: String, val time: Long)

/**
 * On-device scan history. Stored in SharedPreferences as a small JSON array, capped at [MAX].
 * Never leaves the device. The user can clear it from the History screen.
 */
object HistoryStore {
    private const val FILE = "qrlens_history"
    private const val KEY = "entries"
    private const val MAX = 100

    fun add(ctx: Context, c: QrContent) {
        if (c.raw.isBlank()) return
        val list = load(ctx).toMutableList()
        // De-dupe: if the most recent entry is the same content, just refresh its time.
        list.removeAll { it.text == c.raw }
        list.add(0, HistoryEntry(c.raw, c.label, c.title, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
    }

    fun load(ctx: Context): List<HistoryEntry> {
        val raw = p(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(o.getString("t"), o.optString("l"), o.optString("n"), o.optLong("ts"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) = p(ctx).edit().remove(KEY).apply()

    fun remove(ctx: Context, entry: HistoryEntry) {
        save(ctx, load(ctx).filterNot { it.text == entry.text && it.time == entry.time })
    }

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("t", e.text).put("l", e.label).put("n", e.title).put("ts", e.time))
        }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
