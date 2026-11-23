package com.princeniithompson.rapcha.service.floating

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.roundToInt

data class ScreenTextNode(
    val text: String,
    val bounds: Rect,
    val area: Long = bounds.width().toLong() * bounds.height().toLong()
)

data class MagnifierPlacement(
    val windowX: Int,
    val windowY: Int,
    val lensCenterX: Int,
    val lensCenterY: Int
)

/**
 * Pure, single-source-of-truth geometry for the floating magnifier overlay.
 *
 * The magnifier glass sits top-left and its 45-degree handle extends down-right.
 * Locking the overlay to a constant diagonal offset puts the user's finger ~11dp just past
 * the tip of the handle stick with zero lag and zero left/right flipping.
 * Hit-testing is anchored strictly to the center of the top circular glass lens.
 */
object TelescopeMagnifierGeometry {
    const val LENS_RADIUS_DP = 16f
    const val RIM_STROKE_DP = 3.8f
    const val HANDLE_STROKE_DP = 6.2f
    const val LENS_CENTER_SHIFT_DP = -3.5f
    const val HANDLE_EXTRA_LENGTH_DP = 13.5f
    // Stick tip sits at ~+19.55dp from view center; +27.5dp places the finger ~11.2dp just past the tip along 45 deg.
    const val FINGER_OFFSET_FROM_CENTER_DP = 27.5f

    fun computePlacement(
        rawFingerX: Float,
        rawFingerY: Float,
        windowSizePx: Int,
        density: Float,
        screenWidth: Int,
        screenHeight: Int
    ): MagnifierPlacement {
        val halfWin = windowSizePx / 2f
        val lensOffsetInView = halfWin + (LENS_CENTER_SHIFT_DP * density)
        val fingerOffset = FINGER_OFFSET_FROM_CENTER_DP * density

        val unclampedWindowX = (rawFingerX - halfWin - fingerOffset).roundToInt()
        val unclampedWindowY = (rawFingerY - halfWin - fingerOffset).roundToInt()

        // Allow window edges to cross bezels (FLAG_LAYOUT_NO_LIMITS) while keeping the lens center on-screen.
        val minWinX = (-lensOffsetInView).roundToInt()
        val maxWinX = (screenWidth - lensOffsetInView).roundToInt().coerceAtLeast(minWinX)
        val minWinY = (-lensOffsetInView).roundToInt()
        val maxWinY = (screenHeight - lensOffsetInView).roundToInt().coerceAtLeast(minWinY)

        val windowX = unclampedWindowX.coerceIn(minWinX, maxWinX)
        val windowY = unclampedWindowY.coerceIn(minWinY, maxWinY)
        val lensX = (windowX + lensOffsetInView).roundToInt().coerceIn(0, screenWidth.coerceAtLeast(0))
        val lensY = (windowY + lensOffsetInView).roundToInt().coerceIn(0, screenHeight.coerceAtLeast(0))

        return MagnifierPlacement(
            windowX = windowX,
            windowY = windowY,
            lensCenterX = lensX,
            lensCenterY = lensY
        )
    }
}

/**
 * Smart 350ms dwell focus state machine for Telescope text locking.
 *
 * - Fast dragging stays completely silent (zero flashing boxes).
 * - Pausing within [STEADY_RADIUS_DP] for [FOCUS_DWELL_MS] locks onto the valid text node under the lens.
 * - Moving away from a locked node clears the lock cleanly until pausing over a new target.
 * - Releasing with [lockedNode] == null means a clean bubble move (do NOT open action bar).
 */
internal class TelescopeFocusTracker {
    companion object {
        const val FOCUS_DWELL_MS = 350L
        const val STEADY_RADIUS_DP = 15f
    }

    enum class MoveEffect {
        /** Continue waiting on the already-running dwell timer (hand steady within radius). */
        KEEP_DWELLING,
        /** Keep current locked highlight intact (lens is still over the locked text node). */
        KEEP_LOCKED,
        /** Clear any highlight and restart the 350ms dwell timer at the latest lens center. */
        RESTART_DWELL
    }

    var lockedNode: ScreenTextNode? = null
        private set
    var lastLensX: Int = 0
        private set
    var lastLensY: Int = 0
        private set

    private var hasAnchor = false
    private var anchorX = 0f
    private var anchorY = 0f

    fun reset() {
        lockedNode = null
        hasAnchor = false
        anchorX = 0f
        anchorY = 0f
        lastLensX = 0
        lastLensY = 0
    }

    fun onLensMoved(
        lensX: Int,
        lensY: Int,
        density: Float,
        sortedNodes: List<ScreenTextNode>
    ): MoveEffect {
        lastLensX = lensX
        lastLensY = lensY

        val currentLock = lockedNode
        if (currentLock != null) {
            val hit = ScreenTextSnapshot.findNodeAt(sortedNodes, lensX, lensY)
            if (hit === currentLock) {
                return MoveEffect.KEEP_LOCKED
            }
            // Moved away from locked node: clear lock and start fresh dwell at new position.
            lockedNode = null
            anchorX = lensX.toFloat()
            anchorY = lensY.toFloat()
            hasAnchor = true
            return MoveEffect.RESTART_DWELL
        }

        val steadyRadiusPx = STEADY_RADIUS_DP * density
        if (!hasAnchor || hypot((lensX - anchorX).toDouble(), (lensY - anchorY).toDouble()) > steadyRadiusPx) {
            anchorX = lensX.toFloat()
            anchorY = lensY.toFloat()
            hasAnchor = true
            return MoveEffect.RESTART_DWELL
        }
        return MoveEffect.KEEP_DWELLING
    }

    /**
     * Called when the 350ms dwell timer fires. Locks onto the valid text node under the lens, or null.
     */
    fun onDwellElapsed(sortedNodes: List<ScreenTextNode>): ScreenTextNode? {
        val hit = ScreenTextSnapshot.findNodeAt(sortedNodes, lastLensX, lastLensY)
        lockedNode = hit
        return hit
    }
}

/**
 * Lightweight, single-shot screen text node snapshot & hit-testing utility for Telescope mode.
 *
 * Designed for low-end devices (Infinix Hot 11):
 * - Captured ONCE on ACTION_DOWN (never traversed on ACTION_MOVE).
 * - Hard-capped at [MAX_VISIBLE_TEXT_NODES] visible text nodes and [MAX_VISITED_NODES] tree nodes.
 * - Filters out blank containers, decorative images, chat timestamps (e.g. "10:34 AM"), and badges.
 * - Pre-sorted ascending by area (width * height) so [findNodeAt] runs in < 0.1ms with zero allocations.
 */
object ScreenTextSnapshot {
    const val MAX_VISIBLE_TEXT_NODES = 150
    private const val MAX_VISITED_NODES = 900
    private const val MIN_BOX_SIDE_PX = 16

    private val TIMESTAMP_ONLY_REGEX = Regex(
        pattern = """(?i)^(?:edited\s+)?\d{1,2}:\d{2}(?:\s?[ap]\.?m\.?)?$"""
    )
    private val DATE_OR_RELATIVE_TIME_REGEX = Regex(
        pattern = """(?i)^(?:today|yesterday|just now|\d+\s?(?:s|m|h|d|min|mins|hr|hrs)\s?(?:ago)?|read|delivered|sent|seen|typing\.\.\.|online)$"""
    )
    private val CHECKMARKS_OR_SYMBOLS_REGEX = Regex(
        pattern = """^[\p{Punct}\p{So}\p{Sk}\s✓✔•·]+$"""
    )

    fun isIgnoredTimestampOrBadge(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length < 2) return true
        if (TIMESTAMP_ONLY_REGEX.matches(trimmed)) return true
        if (DATE_OR_RELATIVE_TIME_REGEX.matches(trimmed)) return true
        if (CHECKMARKS_OR_SYMBOLS_REGEX.matches(trimmed)) return true
        return false
    }

    fun isDecorativeNonTextClass(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return className == "android.widget.ImageView" ||
            className == "android.widget.ImageButton" ||
            className == "android.widget.ProgressBar" ||
            className == "android.widget.SeekBar"
    }

    /**
     * Allows normal application windows (including Rapcha's own Activity screens) while
     * skipping floating overlays, system chrome, and soft keyboard windows.
     */
    fun isAllowedWindowType(windowType: Int): Boolean {
        return windowType == AccessibilityWindowInfo.TYPE_APPLICATION
    }

    /**
     * Pure helper for sorting and capping pre-extracted nodes (used by both live extraction and tests).
     */
    fun normalizeAndSort(nodes: List<ScreenTextNode>, maxCount: Int = MAX_VISIBLE_TEXT_NODES): List<ScreenTextNode> {
        if (nodes.isEmpty()) return emptyList()
        val seen = HashSet<String>(nodes.size)
        val filtered = ArrayList<ScreenTextNode>(minOf(nodes.size, maxCount))
        for (node in nodes) {
            val cleanText = node.text.trim()
            if (cleanText.isEmpty() || isIgnoredTimestampOrBadge(cleanText)) continue
            if (node.bounds.width() < MIN_BOX_SIDE_PX || node.bounds.height() < MIN_BOX_SIDE_PX) continue
            val key = "${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}:$cleanText"
            if (seen.add(key)) {
                filtered.add(if (cleanText == node.text) node else node.copy(text = cleanText))
                if (filtered.size >= maxCount) break
            }
        }
        filtered.sortBy { it.area }
        return filtered
    }

    /**
     * Finds the tightest visible text node containing screen point ([rawX], [rawY]).
     * Assumes [sortedNodes] is already sorted ascending by area. Zero allocations.
     */
    fun findNodeAt(sortedNodes: List<ScreenTextNode>, rawX: Int, rawY: Int): ScreenTextNode? {
        for (i in 0 until sortedNodes.size) {
            val node = sortedNodes[i]
            if (node.bounds.contains(rawX, rawY)) {
                return node
            }
        }
        return null
    }

    /**
     * Traverses active application window roots once and extracts up to [MAX_VISIBLE_TEXT_NODES]
     * visible text nodes. Recycles all intermediate child nodes (callers own the passed [roots]).
     */
    fun extractFromRoots(
        roots: List<AccessibilityNodeInfo>,
        ignoredPackage: String? = null,
        screenWidth: Int,
        screenHeight: Int
    ): List<ScreenTextNode> {
        if (roots.isEmpty()) return emptyList()
        val collected = ArrayList<ScreenTextNode>(64)
        val maxScreenArea = (screenWidth.toLong() * screenHeight.toLong() * 82L) / 100L
        var visited = 0

        for (root in roots) {
            if (!ignoredPackage.isNullOrEmpty()) {
                val rootPkg = try { root.packageName?.toString() } catch (_: Throwable) { null }
                if (rootPkg == ignoredPackage) continue
            }

            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
            stack.addLast(root to false)

            while (stack.isNotEmpty() && visited < MAX_VISITED_NODES && collected.size < MAX_VISIBLE_TEXT_NODES) {
                val (node, shouldRecycle) = stack.removeLast()
                visited++
                try {
                    val visible = try { node.isVisibleToUser } catch (_: Throwable) { true }
                    if (!visible) continue

                    val childCount = try { node.childCount } catch (_: Throwable) { 0 }
                    val className = try { node.className?.toString() } catch (_: Throwable) { null }
                    val rawText = try { node.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
                    val candidateText = if (rawText.isNotEmpty()) {
                        rawText
                    } else if (childCount == 0 && !isDecorativeNonTextClass(className)) {
                        // Leaf-only fallback for custom text views exposing contentDescription
                        try { node.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
                    } else {
                        ""
                    }

                    if (candidateText.isNotEmpty() && !isIgnoredTimestampOrBadge(candidateText)) {
                        val rect = Rect()
                        try {
                            node.getBoundsInScreen(rect)
                        } catch (_: Throwable) {
                            rect.setEmpty()
                        }
                        val validBox = rect.width() >= MIN_BOX_SIDE_PX &&
                            rect.height() >= MIN_BOX_SIDE_PX &&
                            rect.right > 0 &&
                            rect.bottom > 0 &&
                            rect.left < screenWidth &&
                            rect.top < screenHeight
                        if (validBox) {
                            val clamped = Rect(
                                rect.left.coerceIn(0, screenWidth),
                                rect.top.coerceIn(0, screenHeight),
                                rect.right.coerceIn(0, screenWidth),
                                rect.bottom.coerceIn(0, screenHeight)
                            )
                            val area = clamped.width().toLong() * clamped.height().toLong()
                            if (area > 0L && (maxScreenArea <= 0L || area <= maxScreenArea)) {
                                collected.add(ScreenTextNode(candidateText, clamped, area))
                            }
                        }
                    }

                    for (i in childCount - 1 downTo 0) {
                        val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                        stack.addLast(child to true)
                    }
                } finally {
                    if (shouldRecycle) {
                        try {
                            @Suppress("DEPRECATION")
                            node.recycle()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            // Recycle any remaining stacked children if caps were hit early
            while (stack.isNotEmpty()) {
                val (remaining, shouldRecycle) = stack.removeLast()
                if (shouldRecycle) {
                    try {
                        @Suppress("DEPRECATION")
                        remaining.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        return normalizeAndSort(collected, MAX_VISIBLE_TEXT_NODES)
    }
}
