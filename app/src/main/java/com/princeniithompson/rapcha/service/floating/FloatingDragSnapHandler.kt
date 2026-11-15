package com.princeniithompson.rapcha.service.floating

import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.addListener

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
        private const val SNAP_ANIMATION_MS = 180L
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

    fun resolveYForWindow(
        windowHeightPx: Int,
        isExpanded: Boolean,
        imeTopPx: Int = currentImeTopPx
    ): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        val topY = (36 * density).toInt()
        val fallbackMaxY = if (isExpanded) {
            (screenHeight - (260 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        } else {
            (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        }
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
        overlayView: View?
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val topY = (32 * density).toInt()
        val fallbackMaxY = if (isExpanded) {
            (screenHeight - (260 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        } else {
            (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        }
        val estWindowHeightPx = if (lp.height > 0) {
            lp.height
        } else {
            (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        }
        val minGapPx = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        val maxY = if (currentImeTopPx > 0) {
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
        onComplete: () -> Unit
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val minGapPx = (MIN_GAP_ABOVE_IME_DP * density).toInt()
        val estWindowHeightPx = if (lp.height > 0) {
            lp.height
        } else {
            (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        }

        savedY = lp.y
        val computedOffsetAboveIme = if (currentImeTopPx > 0) {
            (currentImeTopPx - estWindowHeightPx - lp.y).coerceAtLeast(minGapPx)
        } else {
            null
        }

        if (!isExpanded) {
            val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
            val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
            val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
            val snapRight = (lp.x + windowSize / 2) >= screenWidth / 2
            val targetX = if (snapRight) {
                (screenWidth - windowSize - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
            } else {
                (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
            }
            val targetY = lp.y

            val startX = lp.x
            val startY = lp.y
            savePreferences(snapRight, targetY, computedOffsetAboveIme)

            val totalDistX = (targetX - startX).toFloat()
            val totalDistY = (targetY - startY).toFloat()

            snapAnimator?.cancel()
            if (startX == targetX && startY == targetY) {
                onComplete()
                return
            }

            snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SNAP_ANIMATION_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    val prevX = lp.x
                    val prevY = lp.y
                    val nextX = if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) {
                        0
                    } else {
                        val currentWindowWidth = if (lp.width > 0) lp.width else windowSize
                        (startX + totalDistX * fraction).toInt().coerceIn(0, (screenWidth - currentWindowWidth).coerceAtLeast(0))
                    }
                    val nextY = (startY + totalDistY * fraction).toInt()
                    if (nextX == prevX && nextY == prevY) return@addUpdateListener
                    lp.x = nextX
                    lp.y = nextY
                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed updating layout during snap", e)
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val finalX = if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) 0 else targetX
                        if (lp.x == finalX && lp.y == targetY) return
                        lp.x = finalX
                        lp.y = targetY
                        try {
                            windowManager?.updateViewLayout(overlayView, lp)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed final layout update", e)
                        }
                    }
                })
                start()
            }
        } else if (lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
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

    fun cancel() {
        snapAnimator?.cancel()
        snapAnimator = null
    }
}

