package com.princeniithompson.rapcha.service.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.ui.theme.getAppDynamicColorScheme
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lightweight native View rendering the collapsed lifebuoy bubble.
 *
 * Replaces a permanently attached ComposeView while collapsed so the idle overlay uses
 * negligible memory and zero Compose slots/recomposer frames on 3 GB RAM devices.
 */
@SuppressLint("ViewConstructor")
internal class IdleBubbleNativeView(
    context: Context,
    private val liveThemeContext: Context = context.applicationContext ?: context,
    private val onRingClick: () -> Unit,
    private val onRepairClick: () -> Unit,
    private val onLongPressBubble: () -> Unit,
    private val onDragStart: (rawX: Float, rawY: Float) -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: (lastRawX: Float, lastRawY: Float) -> Unit,
    private val onTouchDown: () -> Unit = {},
    private val onDragMoveRaw: (rawX: Float, rawY: Float) -> Unit = { _, _ -> }
) : View(context) {

    private val density = resources.displayMetrics.density
    private val windowPaddingPx = BubbleChromeGeometry.WINDOW_PADDING_DP * density
    private val fullSizePx = BubbleChromeGeometry.CIRCLE_SIZE_DP * density
    private val shrunkContainerPx = 38f * density
    private val shrunkImagePx = 34f * density
    private val warningBadgeSizePx = 18f * density
    private val touchSlopPx = 12f.coerceAtLeast(ViewConfiguration.get(context).scaledTouchSlop * 0.5f)

    private var iconBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dstRect = RectF()
    private val reflectionRect = RectF()
    private val magnifierShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x42_000000
    }
    private val magnifierLensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val magnifierReflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x6B_FFFFFF
    }
    private val magnifierRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val magnifierHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var magnifierShaderWidth = -1
    private var magnifierShaderHeight = -1
    private var magnifierPrimaryArgb: Int = 0
    private var magnifierSecondaryArgb: Int = 0
    private var magnifierLensTintArgb: Int = 0

    var isRecording: Boolean = false
        private set
    var isShrunk: Boolean = false
        private set
    var isSnappedToRight: Boolean = true
        private set
    var isNetworkProblem: Boolean = false
        private set
    var isClipboardFallback: Boolean = false
        private set
    var repairKind: A11yRepairKind = A11yRepairKind.NONE
        private set
    var isHalfTucked: Boolean = false
        private set
    var isTelescopeMode: Boolean = false
        private set

    private val pressInterpolator = DecelerateInterpolator(1.5f)
    private val releaseInterpolator = OvershootInterpolator(1.6f)
    private var tuckProgress: Float = 0f

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var totalDrag = 0f
    private var isDragging = false
    private var isLongPressTriggered = false

    private val longPressRunnable = Runnable {
        if (!isDragging && totalDrag < 18f) {
            isLongPressTriggered = true
            onLongPressBubble()
        }
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        // Enabled so Telescope dwell lock can emit CLOCK_TICK directly on this overlay view.
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refreshDynamicMagnifierColors()
        rasterizeLifebuoyBitmap()
        updateContentDescription()
    }

    /**
     * Reads live Material You wallpaper accent colors fresh from [liveThemeContext] (never from a
     * stale createConfigurationContext snapshot) so wallpaper changes apply immediately.
     */
    fun refreshDynamicMagnifierColors() {
        var primaryRgb: Int
        var secondaryRgb: Int
        var containerRgb: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                primaryRgb = ContextCompat.getColor(liveThemeContext, android.R.color.system_accent1_200)
                secondaryRgb = ContextCompat.getColor(liveThemeContext, android.R.color.system_accent1_500)
                containerRgb = ContextCompat.getColor(liveThemeContext, android.R.color.system_accent1_700)
            } catch (_: Throwable) {
                val scheme = try {
                    dynamicDarkColorScheme(liveThemeContext)
                } catch (_: Throwable) {
                    getAppDynamicColorScheme(liveThemeContext, darkTheme = true, toneId = "luminous")
                }
                primaryRgb = scheme.primary.toArgb()
                secondaryRgb = scheme.primaryContainer.toArgb()
                containerRgb = scheme.primaryContainer.toArgb()
            }
        } else {
            val scheme = getAppDynamicColorScheme(liveThemeContext, darkTheme = true, toneId = "luminous")
            primaryRgb = scheme.primary.toArgb()
            secondaryRgb = scheme.secondary.toArgb()
            containerRgb = scheme.primaryContainer.toArgb()
        }

        val nextPrimary = (0xFF shl 24) or (primaryRgb and 0x00FFFFFF)
        val nextSecondary = (0xFF shl 24) or (secondaryRgb and 0x00FFFFFF)
        // ~31% alpha translucent lens fill so underlying text stays crisp and readable
        val nextLensTint = (0x4F shl 24) or (containerRgb and 0x00FFFFFF)

        if (nextPrimary != magnifierPrimaryArgb ||
            nextSecondary != magnifierSecondaryArgb ||
            nextLensTint != magnifierLensTintArgb
        ) {
            magnifierPrimaryArgb = nextPrimary
            magnifierSecondaryArgb = nextSecondary
            magnifierLensTintArgb = nextLensTint
            magnifierLensPaint.color = magnifierLensTintArgb
            magnifierShaderWidth = -1
            magnifierShaderHeight = -1
            if (width > 0 && height > 0) {
                ensureMagnifierShader(width, height)
            }
            invalidate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        refreshDynamicMagnifierColors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            ensureMagnifierShader(w, h)
        }
    }

    private fun ensureMagnifierShader(w: Int, h: Int) {
        if (w == magnifierShaderWidth && h == magnifierShaderHeight) return
        magnifierShaderWidth = w
        magnifierShaderHeight = h
        val shader = LinearGradient(
            w * 0.18f,
            h * 0.16f,
            w * 0.84f,
            h * 0.84f,
            magnifierPrimaryArgb,
            magnifierSecondaryArgb,
            Shader.TileMode.CLAMP
        )
        magnifierRimPaint.shader = shader
        magnifierHandlePaint.shader = shader
    }

    private fun rasterizeLifebuoyBitmap() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_lifebuoy_ring) ?: return
        val sizePx = fullSizePx.toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(c)
        iconBitmap = bmp
    }

    fun updateState(
        recording: Boolean = this.isRecording,
        shrunk: Boolean = this.isShrunk,
        snappedToRight: Boolean = this.isSnappedToRight,
        networkProblem: Boolean = this.isNetworkProblem,
        clipboardFallback: Boolean = this.isClipboardFallback,
        repairKind: A11yRepairKind = this.repairKind,
        halfTucked: Boolean = this.isHalfTucked,
        telescopeMode: Boolean = this.isTelescopeMode
    ) {
        val enteringTelescope = telescopeMode && !this.isTelescopeMode
        val changed = this.isRecording != recording ||
            this.isShrunk != shrunk ||
            this.isSnappedToRight != snappedToRight ||
            this.isNetworkProblem != networkProblem ||
            this.isClipboardFallback != clipboardFallback ||
            this.repairKind != repairKind ||
            this.isHalfTucked != halfTucked ||
            this.isTelescopeMode != telescopeMode
        if (enteringTelescope) {
            refreshDynamicMagnifierColors()
        }
        if (!changed) return

        this.isRecording = recording
        this.isShrunk = shrunk
        this.isSnappedToRight = snappedToRight
        this.isNetworkProblem = networkProblem
        this.isClipboardFallback = clipboardFallback
        this.repairKind = repairKind
        this.isHalfTucked = halfTucked
        this.isTelescopeMode = telescopeMode
        updateContentDescription()
        invalidate()
    }

    /**
     * Drives opacity (100% -> 45%) and subtle scale (1.0 -> 0.94) in lockstep with the
     * horizontal bezel glide (0f = fully awake, 1f = half-tucked in bezel).
     */
    fun setTuckProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (abs(clamped - tuckProgress) < 0.004f) return
        tuckProgress = clamped
        invalidate()
    }

    private fun animateTouchScale(
        targetScale: Float,
        durationMs: Long,
        springRelease: Boolean = false
    ) {
        animate().cancel()
        animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(durationMs)
            .setInterpolator(if (springRelease) releaseInterpolator else pressInterpolator)
            .start()
    }

    private fun updateContentDescription() {
        contentDescription = if (repairKind != A11yRepairKind.NONE) {
            "Rapcha needs accessibility repair. Tap to fix."
        } else {
            "Floating voice bubble"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                totalDrag = 0f
                isDragging = false
                isLongPressTriggered = false
                animateTouchScale(targetScale = 0.90f, durationMs = 85L)
                onTouchDown()
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 650L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                totalDrag += hypot(dx, dy)
                if (totalDrag > touchSlopPx && !isDragging) {
                    isDragging = true
                    removeCallbacks(longPressRunnable)
                    // Keep scale strictly 1.0f while aiming so lens center aligns pixel-for-pixel.
                    animate().cancel()
                    scaleX = 1f
                    scaleY = 1f
                    refreshDynamicMagnifierColors()
                    onDragStart(event.rawX, event.rawY)
                }
                if (isDragging) {
                    onDrag(dx, dy)
                    onDragMoveRaw(event.rawX, event.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                animateTouchScale(targetScale = 1.0f, durationMs = 180L, springRelease = true)
                if (isDragging) {
                    isDragging = false
                    onDragEnd(event.rawX, event.rawY)
                } else if (!isLongPressTriggered) {
                    if (repairKind != A11yRepairKind.NONE) {
                        onRepairClick()
                    } else {
                        onRingClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                animateTouchScale(targetScale = 1.0f, durationMs = 140L, springRelease = false)
                if (isDragging) {
                    isDragging = false
                    onDragEnd(lastRawX, lastRawY)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val innerCenterY = height / 2f

        if (isTelescopeMode) {
            drawTelescopePointer(canvas, width / 2f, innerCenterY)
            return
        }

        val innerLeft = windowPaddingPx
        val innerRight = (width - windowPaddingPx).coerceAtLeast(innerLeft + fullSizePx)

        // When half-tucked in Always-on-Screen idle mode, keep the full circle centered
        // in the window so sliding 50% off-screen divides the lifesaver ring cleanly in half.
        val effectiveShrunk = isShrunk && !isHalfTucked
        val bubbleSize = if (effectiveShrunk) shrunkContainerPx else fullSizePx
        val imageSize = if (effectiveShrunk) shrunkImagePx else fullSizePx
        val bubbleCenterX = if (effectiveShrunk) {
            if (isSnappedToRight) innerRight - bubbleSize / 2f else innerLeft + bubbleSize / 2f
        } else {
            width / 2f
        }
        val radius = bubbleSize / 2f

        if (isRecording) {
            fillPaint.color = if (isNetworkProblem) {
                0x8C_FF3B30.toInt()
            } else {
                0x59_10B981.toInt()
            }
            canvas.drawCircle(bubbleCenterX, innerCenterY, radius * 0.95f, fillPaint)
        }

        if (isClipboardFallback) {
            val ringRadius = radius - 2f * density
            fillPaint.color = 0x59_D29922
            canvas.drawCircle(bubbleCenterX, innerCenterY, ringRadius * 1.15f, fillPaint)
            strokePaint.color = 0xFF_D29922.toInt()
            strokePaint.strokeWidth = 2.5f * density
            canvas.drawCircle(bubbleCenterX, innerCenterY, ringRadius, strokePaint)
        }

        if (repairKind != A11yRepairKind.NONE) {
            val repairRgb = if (repairKind == A11yRepairKind.PERMISSION_REVOKED) 0xFF3B30 else 0xD29922
            val ringRadius = radius - 2f * density
            fillPaint.color = (0x47 shl 24) or repairRgb
            canvas.drawCircle(bubbleCenterX, innerCenterY, ringRadius * 1.08f, fillPaint)
            strokePaint.color = (0xFF shl 24) or repairRgb
            strokePaint.strokeWidth = 2.5f * density
            canvas.drawCircle(bubbleCenterX, innerCenterY, ringRadius, strokePaint)
        }

        iconBitmap?.let { bmp ->
            val tuckScale = 1f - (0.06f * tuckProgress)
            val halfImg = (imageSize * tuckScale) / 2f
            dstRect.set(
                bubbleCenterX - halfImg,
                innerCenterY - halfImg,
                bubbleCenterX + halfImg,
                innerCenterY + halfImg
            )
            bitmapPaint.alpha = when {
                tuckProgress > 0.004f -> ((1f - 0.55f * tuckProgress) * 255f).toInt().coerceIn(115, 255)
                isHalfTucked -> 115
                effectiveShrunk -> 217
                else -> 255
            }
            canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
        }

        if (repairKind != A11yRepairKind.NONE) {
            val badgeColor = if (repairKind == A11yRepairKind.PERMISSION_REVOKED) {
                0xFF_FF3B30.toInt()
            } else {
                0xFF_D29922.toInt()
            }
            val badgeRadius = warningBadgeSizePx / 2f
            val badgeCx = bubbleCenterX + radius - badgeRadius - 2f * density
            val badgeCy = innerCenterY - radius + badgeRadius + 2f * density
            fillPaint.color = 0xFF_1A1412.toInt()
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, fillPaint)
            strokePaint.color = badgeColor
            strokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, strokePaint)
            badgeTextPaint.color = badgeColor
            badgeTextPaint.textSize = 11f * density
            val textOffsetY = -(badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText("!", badgeCx, badgeCy + textOffsetY, badgeTextPaint)
        }
    }

    /**
     * Draws a standalone see-through Dynamic Color magnifying glass (translucent tinted lens,
     * upper-left glass reflection arc, gradient rim, subtle contrast shadow, and thick rounded
     * 45-degree handle) using pre-allocated Paint/Shader objects (zero allocations per frame).
     */
    private fun drawTelescopePointer(canvas: Canvas, cx: Float, cy: Float) {
        ensureMagnifierShader(width.coerceAtLeast(1), height.coerceAtLeast(1))

        val lensR = TelescopeMagnifierGeometry.LENS_RADIUS_DP * density
        val rimStroke = TelescopeMagnifierGeometry.RIM_STROKE_DP * density
        val handleStroke = TelescopeMagnifierGeometry.HANDLE_STROKE_DP * density
        val lensCx = cx + TelescopeMagnifierGeometry.LENS_CENTER_SHIFT_DP * density
        val lensCy = cy + TelescopeMagnifierGeometry.LENS_CENTER_SHIFT_DP * density

        val diag = 0.70710677f
        val handleStartDist = lensR + rimStroke * 0.42f
        val handleEndDist = lensR + TelescopeMagnifierGeometry.HANDLE_EXTRA_LENGTH_DP * density
        val handleStartX = lensCx + diag * handleStartDist
        val handleStartY = lensCy + diag * handleStartDist
        val handleEndX = lensCx + diag * handleEndDist
        val handleEndY = lensCy + diag * handleEndDist

        // 1. Soft dark contrast halo behind rim & handle for readability over light & dark apps
        val shadowOffset = 1.2f * density
        magnifierShadowPaint.strokeWidth = handleStroke + 2.4f * density
        canvas.drawLine(
            handleStartX + shadowOffset,
            handleStartY + shadowOffset,
            handleEndX + shadowOffset,
            handleEndY + shadowOffset,
            magnifierShadowPaint
        )
        magnifierShadowPaint.strokeWidth = rimStroke + 2.4f * density
        canvas.drawCircle(lensCx + shadowOffset, lensCy + shadowOffset, lensR, magnifierShadowPaint)

        // 2. See-through tinted glass lens
        canvas.drawCircle(lensCx, lensCy, lensR - rimStroke * 0.35f, magnifierLensPaint)

        // 3. Subtle diagonal glossy reflection arc inside upper-left of glass lens
        val glossInset = 4.2f * density
        reflectionRect.set(
            lensCx - lensR + glossInset,
            lensCy - lensR + glossInset,
            lensCx + lensR - glossInset,
            lensCy + lensR - glossInset
        )
        magnifierReflectionPaint.strokeWidth = 2.4f * density
        canvas.drawArc(reflectionRect, 200f, 56f, false, magnifierReflectionPaint)

        // 4. Thick pill-capped 45-degree handle seamlessly joined to outer rim edge
        magnifierHandlePaint.strokeWidth = handleStroke
        canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, magnifierHandlePaint)

        // 5. Crisp Dynamic Color gradient circular rim
        magnifierRimPaint.strokeWidth = rimStroke
        canvas.drawCircle(lensCx, lensCy, lensR, magnifierRimPaint)
    }

    fun release() {
        removeCallbacks(longPressRunnable)
        animate().cancel()
        iconBitmap?.recycle()
        iconBitmap = null
    }
}
