package com.princeniithompson.rapcha.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What the user dictated this session, for the v24-style list on Home.
 * Memory only: nothing is written to disk, and the list is empty again after the app
 * process ends. Holds at most [MAX_ITEMS] entries.
 */
object RecentDictations {
    const val MAX_ITEMS = 30

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

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private var nextId = 1L

    @Synchronized
    fun add(text: String, nowMs: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _items.value = (listOf(Item(nextId++, clean, nowMs)) + _items.value).take(MAX_ITEMS)
    }

    @Synchronized
    fun delete(id: Long) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }
}
