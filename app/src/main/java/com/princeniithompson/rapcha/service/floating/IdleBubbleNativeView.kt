package com.princeniithompson.rapcha.service.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.A11yRepairKind
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
    private val onRingClick: () -> Unit,
    private val onRepairClick: () -> Unit,
    private val onLongPressBubble: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit
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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dstRect = RectF()

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
        isHapticFeedbackEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        rasterizeLifebuoyBitmap()
        updateContentDescription()
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
        repairKind: A11yRepairKind = this.repairKind
    ) {
        val changed = this.isRecording != recording ||
            this.isShrunk != shrunk ||
            this.isSnappedToRight != snappedToRight ||
            this.isNetworkProblem != networkProblem ||
            this.isClipboardFallback != clipboardFallback ||
            this.repairKind != repairKind
        if (!changed) return

        this.isRecording = recording
        this.isShrunk = shrunk
        this.isSnappedToRight = snappedToRight
        this.isNetworkProblem = networkProblem
        this.isClipboardFallback = clipboardFallback
        this.repairKind = repairKind
        updateContentDescription()
        invalidate()
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
                    onDragStart()
                }
                if (isDragging) {
                    onDrag(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
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
                if (isDragging) {
                    isDragging = false
                    onDragEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val innerLeft = windowPaddingPx
        val innerRight = (width - windowPaddingPx).coerceAtLeast(innerLeft + fullSizePx)
        val innerCenterY = height / 2f

        val bubbleSize = if (isShrunk) shrunkContainerPx else fullSizePx
        val imageSize = if (isShrunk) shrunkImagePx else fullSizePx
        val bubbleCenterX = if (isShrunk) {
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
            val halfImg = imageSize / 2f
            dstRect.set(
                bubbleCenterX - halfImg,
                innerCenterY - halfImg,
                bubbleCenterX + halfImg,
                innerCenterY + halfImg
            )
            bitmapPaint.alpha = if (isShrunk) 217 else 255
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

    fun release() {
        removeCallbacks(longPressRunnable)
        iconBitmap?.recycle()
        iconBitmap = null
    }
}
