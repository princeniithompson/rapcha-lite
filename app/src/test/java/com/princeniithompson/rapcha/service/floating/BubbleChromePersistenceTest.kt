package com.princeniithompson.rapcha.service.floating

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BubbleChromePersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun choice_persists_and_drives_the_live_flow() {
        FloatingBubbleManager.setBubbleChrome(ctx, BubbleChromeStyle.COMPACT)
        assertEquals(BubbleChromeStyle.COMPACT, FloatingBubbleManager.bubbleChrome.value)
        val stored = ctx.getSharedPreferences("voxstream_bubble_prefs", Context.MODE_PRIVATE)
            .getString(FloatingBubbleManager.KEY_BUBBLE_CHROME, null)
        assertEquals("mini", stored)
        FloatingBubbleManager.setBubbleChrome(ctx, BubbleChromeStyle.ORIGINAL)
        FloatingBubbleManager.loadBubbleChrome(ctx)
        assertEquals(BubbleChromeStyle.ORIGINAL, FloatingBubbleManager.bubbleChrome.value)
    }

    @Test
    fun default_is_the_original_words_bubble() {
        ctx.getSharedPreferences("voxstream_bubble_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FloatingBubbleManager.loadBubbleChrome(ctx)
        assertEquals(BubbleChromeStyle.ORIGINAL, FloatingBubbleManager.bubbleChrome.value)
    }
}
