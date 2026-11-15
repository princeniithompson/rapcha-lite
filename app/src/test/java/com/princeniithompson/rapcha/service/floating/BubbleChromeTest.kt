package com.princeniithompson.rapcha.service.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleChromeTest {

    @Test
    fun `the clock caps at ten minutes and the compact pill keeps its geometry`() {
        assertEquals("00:00", SessionClockFormat.format(0))
        assertEquals("01:24", SessionClockFormat.format(84))
        assertEquals("10:00", SessionClockFormat.format(600))
        assertEquals("10:00", SessionClockFormat.format(601))
        assertEquals(48, BubbleChromeGeometry.CIRCLE_SIZE_DP)
        assertEquals(48, BubbleChromeGeometry.CANCEL_DP)
        assertEquals(90, BubbleChromeGeometry.PILL_WIDTH_DP)
        assertEquals(48, BubbleChromeGeometry.PILL_HEIGHT_DP)
        assertEquals(48, BubbleChromeGeometry.COMPLETE_DP)
        assertEquals(5, BubbleChromeGeometry.GAP_DP)
        assertEquals(20, BubbleChromeGeometry.EDGE_MARGIN_DP)
    }

    @Test
    fun `expanded full-width chrome locks x and a compact window can move sideways`() {
        assertTrue(OverlayDragMath.locksHorizontal(expanded = true, matchParentWidth = true))
        assertFalse(OverlayDragMath.locksHorizontal(expanded = true, matchParentWidth = false))
        assertEquals(0, OverlayDragMath.nextX(40, 30, 1080, 1080, lockHorizontal = true))
        assertEquals(70, OverlayDragMath.nextX(40, 30, 1080, 200, lockHorizontal = false))
        assertEquals(880, OverlayDragMath.nextX(860, 40, 1080, 200, lockHorizontal = false))
        assertEquals(0, OverlayDragMath.nextX(10, -40, 1080, 200, lockHorizontal = false))
    }

    @Test
    fun `compact pill label follows the session state`() {
        assertEquals("Listening", compactPillLabel(finishing = false, networkProblem = false))
        assertEquals("Offline", compactPillLabel(finishing = false, networkProblem = true))
        assertEquals("Finishing", compactPillLabel(finishing = true, networkProblem = true))
    }

    @Test
    fun `active container is stored as words or mini`() {
        assertEquals("words", BubbleChromeStyle.ORIGINAL.stored())
        assertEquals("mini", BubbleChromeStyle.COMPACT.stored())
        assertEquals(BubbleChromeStyle.COMPACT, BubbleChromeStyle.fromStored("mini"))
        assertEquals(BubbleChromeStyle.COMPACT, BubbleChromeStyle.fromStored("compact"))
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored("words"))
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored(null))
        assertEquals(BubbleChromeStyle.ORIGINAL, BubbleChromeStyle.fromStored("garbage"))
    }

    @Test
    fun `swiping or auto scrolling never changes the chosen bubble`() {
        for (current in BubbleChromeStyle.values()) {
            for (page in 0 until HomeCarouselPolicy.PAGE_COUNT) {
                assertEquals(current, HomeCarouselPolicy.styleAfter(current, page, explicitChoice = false))
            }
        }
        assertEquals(BubbleChromeStyle.COMPACT,
            HomeCarouselPolicy.styleAfter(BubbleChromeStyle.ORIGINAL, HomeCarouselPolicy.PAGE_MINI_BUBBLE, true))
        assertEquals(BubbleChromeStyle.ORIGINAL,
            HomeCarouselPolicy.styleAfter(BubbleChromeStyle.COMPACT, HomeCarouselPolicy.PAGE_WORDS_BUBBLE, true))
        // Choosing on a stats page keeps the current bubble.
        assertEquals(BubbleChromeStyle.COMPACT,
            HomeCarouselPolicy.styleAfter(BubbleChromeStyle.COMPACT, HomeCarouselPolicy.PAGE_WPM, true))
    }

    @Test
    fun `carousel has two bubble pages then the stats and wraps around`() {
        assertEquals(4, HomeCarouselPolicy.PAGE_COUNT)
        assertEquals(BubbleChromeStyle.ORIGINAL, HomeCarouselPolicy.styleForPage(0))
        assertEquals(BubbleChromeStyle.COMPACT, HomeCarouselPolicy.styleForPage(1))
        assertEquals(null, HomeCarouselPolicy.styleForPage(2))
        assertEquals(0, HomeCarouselPolicy.nextPage(3))
        assertEquals(1, HomeCarouselPolicy.pageFor(BubbleChromeStyle.COMPACT))
        assertTrue(HomeCarouselPolicy.shouldAutoAdvance(idle = true, userInteracting = false))
        assertFalse(HomeCarouselPolicy.shouldAutoAdvance(idle = true, userInteracting = true))
    }
}
