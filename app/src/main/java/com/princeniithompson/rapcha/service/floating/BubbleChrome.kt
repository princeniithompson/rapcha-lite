package com.princeniithompson.rapcha.service.floating

/**
 * v26: two bubbles again. Words is the original v24 bubble (FloatingDictationPopup). Mini is the
 * compact pill. The saved value lives in the v24 bubble_chrome_style preference
 * (activeContainer "words" or "mini"); older "original" and "compact" values still load.
 */
enum class BubbleChromeStyle {
    ORIGINAL,
    COMPACT;

    /** Persisted activeContainer. words is original, mini is compact. */
    fun stored(): String = if (this == COMPACT) MINI else WORDS

    companion object {
        const val WORDS = "words"
        const val MINI = "mini"

        fun fromStored(value: String?): BubbleChromeStyle {
            return when (value?.lowercase()) {
                MINI, "compact" -> COMPACT
                else -> ORIGINAL
            }
        }
    }
}

/**
 * Home top carousel: page 0 is the Words bubble, page 1 the Mini pill, then the stats pages.
 * It auto-advances after 19 s idle. Moving the carousel never changes the saved bubble;
 * only an explicit tap on a bubble page's bubble or its Use button does.
 */
object HomeCarouselPolicy {
    const val IDLE_PAGE_MS = 19_000L
    const val PAGE_WORDS_BUBBLE = 0
    const val PAGE_MINI_BUBBLE = 1
    const val PAGE_WORDS_SPOKEN = 2
    const val PAGE_WPM = 3
    const val PAGE_COUNT = 4

    fun nextPage(settledPage: Int): Int = (settledPage + 1) % PAGE_COUNT

    fun shouldAutoAdvance(idle: Boolean, userInteracting: Boolean): Boolean = idle && !userInteracting

    fun styleForPage(page: Int): BubbleChromeStyle? = when (page) {
        PAGE_WORDS_BUBBLE -> BubbleChromeStyle.ORIGINAL
        PAGE_MINI_BUBBLE -> BubbleChromeStyle.COMPACT
        else -> null
    }

    fun pageFor(style: BubbleChromeStyle): Int =
        if (style == BubbleChromeStyle.COMPACT) PAGE_MINI_BUBBLE else PAGE_WORDS_BUBBLE

    /** The saved bubble after the carousel moves. Only an explicit choice changes it. */
    fun styleAfter(current: BubbleChromeStyle, page: Int, explicitChoice: Boolean): BubbleChromeStyle {
        if (!explicitChoice) return current
        return styleForPage(page) ?: current
    }
}

/**
 * The compact pill.
 * Compact sizes are taken from the phone screenshot at about 2.6 px/dp.
 * Cancel 48dp, pill 90x48dp, Complete 48dp, gaps 5dp.
 */
object BubbleChromeGeometry {
    const val EDGE_MARGIN_DP = 20
    const val WINDOW_PADDING_DP = 12
    const val CIRCLE_SIZE_DP = 48
    const val CANCEL_DP = 48
    const val PILL_WIDTH_DP = 90
    const val PILL_HEIGHT_DP = 48
    const val COMPLETE_DP = 48
    const val GAP_DP = 5
    const val TOTAL_COMPACT_WIDTH_DP = 48 + 5 + 90 + 5 + 48 // 196
    const val IDLE_WINDOW_SIZE_DP = 48 + 2 * WINDOW_PADDING_DP // 72
    const val RECORDING_WINDOW_WIDTH_DP = 196 + 2 * WINDOW_PADDING_DP // 220
    const val RECORDING_WINDOW_HEIGHT_DP = 48 + 2 * WINDOW_PADDING_DP // 72
    const val SESSION_CAP_SECONDS = 600
}

/**
 * Expanded full-width chrome stays pinned to x = 0.
 * A compact window, even while the session is expanded, can move on both axes.
 */
object OverlayDragMath {
    fun nextX(
        currentX: Int,
        dx: Int,
        screenWidth: Int,
        windowWidth: Int,
        lockHorizontal: Boolean
    ): Int {
        if (lockHorizontal) return 0
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        return (currentX + dx).coerceIn(0, maxX)
    }

    fun locksHorizontal(expanded: Boolean, matchParentWidth: Boolean): Boolean {
        return expanded && matchParentWidth
    }
}

object SessionClockFormat {
    fun format(elapsedSeconds: Int): String {
        val capped = elapsedSeconds.coerceIn(0, BubbleChromeGeometry.SESSION_CAP_SECONDS)
        val minutes = capped / 60
        val seconds = capped % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

