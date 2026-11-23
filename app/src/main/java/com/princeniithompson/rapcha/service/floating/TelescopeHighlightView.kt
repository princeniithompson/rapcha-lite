package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.ui.theme.getAppDynamicColorScheme

/**
 * Single hardware-accelerated, non-touchable full-screen Canvas overlay that draws
 * a 10dp rounded highlight box around the currently targeted text node in Telescope mode.
 *
 * Pre-allocates all Paint/RectF objects so [onDraw] performs zero allocations at 60fps.
 */
internal class TelescopeHighlightOverlay(private val context: Context) {

    companion object {
        private const val TAG = "TelescopeHighlight"
        private const val CORNER_RADIUS_DP = 10f
        private const val STROKE_WIDTH_DP = 2.5f
        private const val PADDING_DP = 3f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var canvasView: HighlightCanvasView? = null
    private var attached = false

    fun showHighlight(screenBounds: Rect) {
        val view = ensureAttached() ?: return
        view.refreshDynamicColors()
        view.setHighlightRect(screenBounds)
    }

    fun clearHighlight() {
        canvasView?.clearHighlightRect()
    }

    fun hideAndDetach() {
        val view = canvasView ?: return
        view.clearHighlightRect()
        if (attached) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Throwable) {
                try {
                    windowManager?.removeView(view)
                } catch (_: Throwable) {
                }
            }
            attached = false
        }
        canvasView = null
    }

    private fun ensureAttached(): HighlightCanvasView? {
        val wm = windowManager ?: return null
        val existing = canvasView
        if (existing != null && attached) {
            if (existing.visibility != View.VISIBLE) {
                existing.visibility = View.VISIBLE
            }
            return existing
        }

        val view = existing ?: HighlightCanvasView(context).also { canvasView = it }
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            wm.addView(view, lp)
            attached = true
            view
        } catch (t: Throwable) {
            Log.w(TAG, "Failed attaching highlight overlay: ${t.javaClass.simpleName}")
            null
        }
    }

    private class HighlightCanvasView(context: Context) : View(context) {
        private val liveThemeContext: Context = context.applicationContext ?: context
        private val density = resources.displayMetrics.density
        private val cornerRadiusPx = CORNER_RADIUS_DP * density
        private val padPx = PADDING_DP * density

        private var accentRgb: Int = 0

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH_DP * density
        }

        private val drawRect = RectF()
        private val screenLoc = IntArray(2)
        private var hasRect = false

        init {
            isFocusable = false
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            refreshDynamicColors()
        }

        fun refreshDynamicColors() {
            val nextRgb = resolveDynamicAccentRgb(liveThemeContext) and 0x00FFFFFF
            if (nextRgb == accentRgb) return
            accentRgb = nextRgb
            fillPaint.color = (0x29 shl 24) or accentRgb
            strokePaint.color = (0xF2 shl 24) or accentRgb
            if (hasRect) invalidate()
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            refreshDynamicColors()
        }

        fun setHighlightRect(screenBounds: Rect) {
            getLocationOnScreen(screenLoc)
            val offsetX = screenLoc[0].toFloat()
            val offsetY = screenLoc[1].toFloat()
            val left = screenBounds.left - offsetX - padPx
            val top = screenBounds.top - offsetY - padPx
            val right = screenBounds.right - offsetX + padPx
            val bottom = screenBounds.bottom - offsetY + padPx
            if (hasRect &&
                drawRect.left == left &&
                drawRect.top == top &&
                drawRect.right == right &&
                drawRect.bottom == bottom
            ) {
                return
            }
            drawRect.set(left, top, right, bottom)
            hasRect = true
            invalidate()
        }

        fun clearHighlightRect() {
            if (!hasRect) return
            hasRect = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!hasRect) return
            canvas.drawRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, fillPaint)
            canvas.drawRoundRect(drawRect, cornerRadiusPx, cornerRadiusPx, strokePaint)
        }

        private fun resolveDynamicAccentRgb(ctx: Context): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    return ContextCompat.getColor(ctx, android.R.color.system_accent1_200)
                } catch (_: Throwable) {
                }
            }
            return getAppDynamicColorScheme(ctx, darkTheme = true, toneId = "luminous").primary.toArgb()
        }
    }
}
