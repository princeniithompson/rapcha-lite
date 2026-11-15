package com.princeniithompson.rapcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StatsAndRecentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() { RecentDictations.clear() }

    @Test
    fun counts_words_and_wpm() {
        assertEquals(0, DictationStats.countWords("   "))
        assertEquals(4, DictationStats.countWords(" one  two\nthree four "))
        assertEquals(120, DictationStats.Totals(words = 120, seconds = 60).wpm)
        assertEquals(0, DictationStats.Totals(words = 5, seconds = 0).wpm)
    }

    @Test
    fun record_adds_counters_only() {
        DictationStats.record(ctx, "hello there friend", 3)
        DictationStats.record(ctx, "again", 0)
        assertEquals(DictationStats.Totals(4, 4), DictationStats.totals.value)
        // Only numbers are stored, never the text.
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val stored = prefsDir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertTrue(!stored.contains("hello there friend"))
    }

    @Test
    fun recent_list_is_capped_newest_first_and_deletable() {
        repeat(RecentDictations.MAX_ITEMS + 5) { RecentDictations.add("item $it") }
        val items = RecentDictations.items.value
        assertEquals(RecentDictations.MAX_ITEMS, items.size)
        assertEquals("item ${RecentDictations.MAX_ITEMS + 4}", items.first().text)
        RecentDictations.delete(items.first().id)
        assertEquals(RecentDictations.MAX_ITEMS - 1, RecentDictations.items.value.size)
        RecentDictations.add("   ")
        assertEquals(RecentDictations.MAX_ITEMS - 1, RecentDictations.items.value.size)
    }
}
