package com.princeniithompson.rapcha.service.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object OverlayVerticalPositionMath {
    fun resolveY(
        savedY: Int,
        imeTopPx: Int,
        savedOffsetAboveImePx: Int,
        windowHeightPx: Int,
        topY: Int,
        fallbackMaxY: Int,
        minGapAboveImePx: Int
    ): Int {
        val safeMaxY = fallbackMaxY.coerceAtLeast(topY)
        if (imeTopPx > 0) {
            val safeMinGap = minGapAboveImePx.coerceAtLeast(0)
            val effectiveOffset = savedOffsetAboveImePx.coerceAtLeast(safeMinGap)
            val maxAboveKeyboardY = (imeTopPx - safeMinGap - windowHeightPx).coerceAtLeast(topY)
            val desiredY = imeTopPx - effectiveOffset - windowHeightPx
            return desiredY.coerceIn(topY, minOf(safeMaxY, maxAboveKeyboardY))
        }
        return savedY.coerceIn(topY, safeMaxY)
    }
}

/**
 * Handles touch dragging, keyboard-relative vertical docking, and crisp snap-to-edge
 * animations for the floating overlay bubble.
 */
class FloatingDragSnapHandler(private val context: Context) {

    companion object {
        private const val TAG = "FloatingDragSnap"
        private const val DEFAULT_IME_OFFSET_DP = 12f
        private const val MIN_GAP_ABOVE_IME_DP = 4f
        private const val SPRING_SNAP_MS = 240L
        private const val WAKE_TUCK_ANIM_MS = 240L
        private const val SLEEP_TUCK_ANIM_MS = 220L
    }

    enum class SettlePosition { TOP, BOTTOM }

    var isSnappedToRight = true
        private set

    var savedY = 0
        private set

    var savedOffsetAboveImePx = 0
        private set

    var currentImeTopPx = 0
        private set

    var currentSettlePosition = SettlePosition.BOTTOM
        private set

    private var snapAnimator: ValueAnimator? = null

    init {
        loadPreferences()
    }

    fun loadPreferences() {
        val prefs = context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        isSnappedToRight = prefs.getBoolean("bubble_snapped_right", true)
        val posStr = prefs.getString("settle_position", SettlePosition.BOTTOM.name) ?: SettlePosition.BOTTOM.name
        currentSettlePosition = try {
            SettlePosition.valueOf(posStr)
        } catch (_: Exception) {
            SettlePosition.BOTTOM
        }
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val defaultY = (screenHeight * 0.52f).toInt()
        savedY = prefs.getInt("bubble_pos_y", defaultY)
        val defaultImeOffset = (DEFAULT_IME_OFFSET_DP * density).toInt()
        val minImeGap = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        savedOffsetAboveImePx = prefs.getInt("bubble_ime_offset_px", defaultImeOffset).coerceAtLeast(minImeGap)
    }

    fun savePreferences(snappedRight: Boolean, y: Int, offsetAboveImePx: Int? = null) {
        isSnappedToRight = snappedRight
        savedY = y
        val editor = context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("bubble_snapped_right", snappedRight)
            .putInt("bubble_pos_y", y)
        if (offsetAboveImePx != null) {
            val density = context.resources.displayMetrics.density
            val clampedOffset = offsetAboveImePx.coerceAtLeast((MIN_GAP_ABOVE_IME_DP * density).toInt())
            savedOffsetAboveImePx = clampedOffset
            editor.putInt("bubble_ime_offset_px", clampedOffset)
        }
        editor.apply()
    }

    fun updateImeTopPx(imeTopPx: Int) {
        currentImeTopPx = imeTopPx.coerceAtLeast(0)
    }

    fun getCollapsedRestingX(windowSize: Int): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        return if (isSnappedToRight) {
            (screenWidth - windowSize - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
        } else {
            (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
        }
    }

    fun resolveYForWindow(
        windowHeightPx: Int,
        isExpanded: Boolean,
        imeTopPx: Int = currentImeTopPx
    ): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        val topY = (36 * density).toInt()
        if (!isExpanded) {
            // Collapsed lifesaver ring crosses and rests anywhere vertically (including over
            // the keyboard area) so opening/closing the keyboard never causes vertical jumps.
            val maxCollapsedY = (screenHeight - windowHeightPx - (16 * density).toInt())
                .coerceAtLeast(topY + (60 * density).toInt())
            return savedY.coerceIn(topY, maxCollapsedY)
        }
        val fallbackMaxY = (screenHeight - (260 * density).toInt())
            .coerceAtLeast(topY + (60 * density).toInt())
        val minGapPx = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        return OverlayVerticalPositionMath.resolveY(
            savedY = savedY,
            imeTopPx = imeTopPx,
            savedOffsetAboveImePx = savedOffsetAboveImePx,
            windowHeightPx = windowHeightPx,
            topY = topY,
            fallbackMaxY = fallbackMaxY,
            minGapAboveImePx = minGapPx
        )
    }

    fun handleOverlayDrag(
        dx: Float,
        dy: Float,
        isExpanded: Boolean,
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        freeVerticalDrag: Boolean = false
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val topY = (32 * density).toInt()
        val estWindowHeightPx = if (lp.height > 0) {
            lp.height
        } else {
            (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        }
        val allowAcrossKeyboard = !isExpanded || freeVerticalDrag
        val fallbackMaxY = if (isExpanded) {
            (screenHeight - (260 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        } else {
            (screenHeight - estWindowHeightPx - (16 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        }
        val minGapPx = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        val maxY = if (!allowAcrossKeyboard && currentImeTopPx > 0) {
            minOf(fallbackMaxY, (currentImeTopPx - minGapPx - estWindowHeightPx).coerceAtLeast(topY))
        } else {
            fallbackMaxY
        }

        snapAnimator?.cancel()
        val oldX = lp.x
        val oldY = lp.y

        lp.y = (lp.y + dy.toInt()).coerceIn(topY, maxY)

        val matchParent = lp.width == WindowManager.LayoutParams.MATCH_PARENT
        val lockX = OverlayDragMath.locksHorizontal(isExpanded, matchParent)
        val windowWidth = if (matchParent) screenWidth else lp.width.coerceAtLeast(1)
        lp.x = OverlayDragMath.nextX(lp.x, dx.toInt(), screenWidth, windowWidth, lockX)

        if (lp.x == oldX && lp.y == oldY) return

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout during drag", e)
        }
    }

    fun handleOverlayDragEnd(
        isExpanded: Boolean,
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        releaseRawX: Float? = null,
        releaseRawY: Float? = null,
        onComplete: () -> Unit
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        val minGapPx = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        val estWindowHeightPx = if (lp.height > 0) {
            lp.height
        } else {
            (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        }

        val computedOffsetAboveIme = if (isExpanded && currentImeTopPx > 0) {
            (currentImeTopPx - estWindowHeightPx - lp.y).coerceAtLeast(minGapPx)
        } else {
            null
        }

        if (!isExpanded) {
            val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
            val topY = (36 * density).toInt()
            val maxCollapsedY = (screenHeight - windowSize - (16 * density).toInt())
                .coerceAtLeast(topY + (60 * density).toInt())
            val snapRight = if (releaseRawX != null) {
                releaseRawX >= screenWidth / 2f
            } else {
                (lp.x + windowSize / 2) >= screenWidth / 2
            }
            val rawTargetY = if (releaseRawY != null) {
                (releaseRawY - windowSize / 2f).toInt()
            } else {
                lp.y
            }
            val targetY = rawTargetY.coerceIn(topY, maxCollapsedY)
            savePreferences(snapRight, targetY, null)
            val targetX = getCollapsedRestingX(windowSize)

            animateWindowTo(
                lp = lp,
                windowManager = windowManager,
                overlayView = overlayView,
                targetX = targetX,
                targetY = targetY,
                allowOffscreenX = true,
                durationMs = SPRING_SNAP_MS,
                interpolator = OvershootInterpolator(1.1f),
                onComplete = onComplete
            )
            return
        } else if (lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
            savedY = lp.y
            val windowWidth = lp.width.coerceAtLeast(1)
            val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
            val snapRight = (lp.x + windowWidth / 2) >= screenWidth / 2
            val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
            val targetX = if (snapRight) {
                (screenWidth - windowWidth - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
            } else {
                (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
            }
            savePreferences(snapRight, lp.y, computedOffsetAboveIme)
            if (lp.x != targetX) {
                lp.x = targetX
                try {
                    windowManager?.updateViewLayout(overlayView, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating compact layout after drag end", e)
                }
            }
        } else {
            val prevX = lp.x
            lp.x = 0
            savePreferences(isSnappedToRight, lp.y, computedOffsetAboveIme)
            if (prevX != 0) {
                try {
                    windowManager?.updateViewLayout(overlayView, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating expanded layout after drag end", e)
                }
            }
        }

        onComplete()
    }

    /**
     * Smoothly returns the collapsed lifesaver ring to its saved non-edge resting position
     * without overwriting saved coordinates (used when Telescope drag releases on highlighted text).
     */
    fun animateReturnToRestingPosition(
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        onComplete: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val targetX = getCollapsedRestingX(windowSize)
        val targetY = resolveYForWindow(windowHeightPx = windowSize, isExpanded = false)
        animateWindowTo(
            lp = lp,
            windowManager = windowManager,
            overlayView = overlayView,
            targetX = targetX,
            targetY = targetY,
            allowOffscreenX = false,
            durationMs = SPRING_SNAP_MS,
            interpolator = OvershootInterpolator(1.1f),
            onComplete = onComplete
        )
    }

    /**
     * Idle sleeping half-ring transition: slides 50% of the circular lifesaver ring off the
     * nearest left/right screen bezel when sleeping, or springs smoothly back out to its
     * non-bezel resting position (20dp off edge) when waking — always keeping Y unchanged.
     */
    fun animateHalfEdgeTuck(
        tucked: Boolean,
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        onProgress: (Float) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()

        val targetX = if (tucked) {
            if (isSnappedToRight) {
                screenWidth - (windowSize / 2)
            } else {
                -(windowSize / 2)
            }
        } else {
            getCollapsedRestingX(windowSize)
        }
        val animDuration = if (tucked) SLEEP_TUCK_ANIM_MS else WAKE_TUCK_ANIM_MS
        val animInterpolator: TimeInterpolator = if (tucked) {
            DecelerateInterpolator(1.6f)
        } else {
            OvershootInterpolator(1.25f)
        }
        // allowOffscreenX = true in both directions so waking glides smoothly out of the bezel
        // instead of clamping/teleporting onto the screen edge on frame 1.
        animateWindowTo(
            lp = lp,
            windowManager = windowManager,
            overlayView = overlayView,
            targetX = targetX,
            targetY = lp.y,
            allowOffscreenX = true,
            durationMs = animDuration,
            interpolator = animInterpolator,
            onFrameFraction = { f ->
                val tuckProgress = if (tucked) f else (1f - f)
                onProgress(tuckProgress.coerceIn(0f, 1f))
            },
            onComplete = onComplete
        )
    }

    private fun animateWindowTo(
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        targetX: Int,
        targetY: Int,
        allowOffscreenX: Boolean,
        durationMs: Long = SPRING_SNAP_MS,
        interpolator: TimeInterpolator = DecelerateInterpolator(1.6f),
        onFrameFraction: ((Float) -> Unit)? = null,
        onComplete: () -> Unit
    ) {
        val startX = lp.x
        val startY = lp.y
        snapAnimator?.cancel()
        if (startX == targetX && startY == targetY) {
            onFrameFraction?.invoke(1f)
            onComplete()
            return
        }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val totalDistX = (targetX - startX).toFloat()
        val totalDistY = (targetY - startY).toFloat()

        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            var cancelled = false
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                onFrameFraction?.invoke(f)
                val rawNextX = (startX + totalDistX * f).toInt()
                val nextX = if (allowOffscreenX || lp.width == WindowManager.LayoutParams.MATCH_PARENT) {
                    if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) 0 else rawNextX
                } else {
                    val w = if (lp.width > 0) lp.width else 0
                    rawNextX.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
                }
                val nextY = (startY + totalDistY * f).toInt()
                if (nextX == lp.x && nextY == lp.y) return@addUpdateListener
                lp.x = nextX
                lp.y = nextY
                if (overlayView?.isAttachedToWindow == true) {
                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (_: Throwable) {
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    val finalX = if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) 0 else targetX
                    onFrameFraction?.invoke(1f)
                    if (lp.x != finalX || lp.y != targetY) {
                        lp.x = finalX
                        lp.y = targetY
                        if (overlayView?.isAttachedToWindow == true) {
                            try {
                                windowManager?.updateViewLayout(overlayView, lp)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    onComplete()
                }
            })
            start()
        }
    }

    fun cancel() {
        snapAnimator?.cancel()
        snapAnimator = null
    }
}

