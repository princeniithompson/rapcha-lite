package com.princeniithompson.rapcha.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Persisted list of recent dictations shown on the Home screen.
 * Saved to SharedPreferences so completed voice-typing sessions survive app restarts
 * and fresh updates. Holds at most [MAX_ITEMS] entries (newest first).
 */
object RecentDictations {
    private const val TAG = "RecentDictations"
    private const val PREFS_NAME = "rapcha_recent_dictations"
    private const val KEY_ITEMS = "items_v1"
    const val MAX_ITEMS = 100

    data class Item(val id: Long, val text: String, val timestampMs: Long) {
        fun formattedTime(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

        fun dateHeader(nowMs: Long = System.currentTimeMillis()): String {
            val now = Calendar.getInstance().apply { timeInMillis = nowMs }
            val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
            return when {
                sameYear && dayDiff == 0 -> "Today"
                sameYear && dayDiff == 1 -> "Yesterday"
                else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
            }
        }
    }

    private var prefs: SharedPreferences? = null
    private var loaded = false

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private var nextId = 1L

    /**
     * Loads persisted dictations from disk once per process. Safe to call repeatedly
     * from both MainActivity and FloatingBubbleService.
     */
    @Synchronized
    fun load(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (loaded) return
        loaded = true
        val raw = prefs?.getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) return
        try {
            val arr = JSONArray(raw)
            val restored = ArrayList<Item>(arr.length())
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val text = obj.optString("text", "").trim()
                if (text.isEmpty()) continue
                val id = obj.optLong("id", (i + 1).toLong())
                val ts = obj.optLong("ts", System.currentTimeMillis())
                if (id > maxId) maxId = id
                restored.add(Item(id = id, text = text, timestampMs = ts))
            }
            if (restored.isNotEmpty()) {
                // Merge any in-memory items added before load() with restored disk items.
                val merged = (_items.value + restored)
                    .distinctBy { "${it.timestampMs}|${it.text}" }
                    .take(MAX_ITEMS)
                _items.value = merged
                nextId = (maxOf(maxId, merged.maxOfOrNull { it.id } ?: 0L) + 1L).coerceAtLeast(1L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse saved recent dictations: ${t.message}")
        }
    }

    @Synchronized
    fun add(text: String, nowMs: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _items.value = (listOf(Item(nextId++, clean, nowMs)) + _items.value).take(MAX_ITEMS)
        persistLocked()
    }

    @Synchronized
    fun delete(id: Long) {
        _items.value = _items.value.filterNot { it.id == id }
        persistLocked()
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        persistLocked()
    }

    private fun persistLocked() {
        val sp = prefs ?: return
        try {
            val arr = JSONArray()
            _items.value.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("text", item.text)
                        put("ts", item.timestampMs)
                    }
                )
            }
            sp.edit().putString(KEY_ITEMS, arr.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save recent dictations: ${t.message}")
        }
    }
}
