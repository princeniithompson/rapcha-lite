package com.princeniithompson.rapcha.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v26 Home stats: two plain counters in SharedPreferences (total words typed and total
 * seconds spoken). No transcripts, no history and no logs are stored.
 */
object DictationStats {
    private const val PREFS = "rapcha_stats"
    private const val KEY_WORDS = "total_words"
    private const val KEY_SECONDS = "total_seconds"

    data class Totals(val words: Long = 0L, val seconds: Long = 0L) {
        /** Average words per minute, 0 until there is at least one second of speech. */
        val wpm: Int
            get() = if (seconds <= 0L) 0 else ((words * 60.0) / seconds).toInt()
    }

    private val _totals = MutableStateFlow(Totals())
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _totals.value = Totals(p.getLong(KEY_WORDS, 0L), p.getLong(KEY_SECONDS, 0L))
    }

    fun countWords(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /** Called once per finished dictation that typed text. */
    fun record(context: Context, text: String, spokenSeconds: Int) {
        val words = countWords(text)
        if (words == 0) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = Totals(
            words = p.getLong(KEY_WORDS, 0L) + words,
            seconds = p.getLong(KEY_SECONDS, 0L) + spokenSeconds.coerceAtLeast(1)
        )
        p.edit().putLong(KEY_WORDS, next.words).putLong(KEY_SECONDS, next.seconds).apply()
        _totals.value = next
    }
}
