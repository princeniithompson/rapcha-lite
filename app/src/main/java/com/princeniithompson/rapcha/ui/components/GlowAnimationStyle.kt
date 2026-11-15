package com.princeniithompson.rapcha.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metadata and catalogue for ambient glowing container animations.
 */
data class GlowAnimationStyle(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: String
)

/**
 * v26: exactly one glow ships, the v24 default "Breathing Horizon". There is no picker and no
 * other glow styles. Any old saved glow id falls back to this one.
 */
object GlowAnimationCatalogue {
    const val DEFAULT_ID = "breathing_horizon"

    val styles: List<GlowAnimationStyle> = listOf(
        GlowAnimationStyle(
            id = DEFAULT_ID,
            name = "Breathing Horizon",
            subtitle = "Calm sweeping horizontal band of ambient diffused light rising calmly",
            category = "Default"
        )
    )

    fun getById(@Suppress("UNUSED_PARAMETER") id: String): GlowAnimationStyle = styles.first()
}

/**
 * Master renderer for all glowing container animations.
 * Completely hardware accelerated within Compose DrawScope.
 */
fun DrawScope.renderGlowAnimation(
    styleId: String,
    palette: AuroraColorPalette,
    effectiveAmp: Float,
    glowBrightness: Float,
    phase1: Float,
    phase2: Float,
    completionMorphProgress: Float = 0f,
    entranceProgress: Float = 1f,
    waitingLoopProgress: Float = 0f
) {
    val w = size.width
    val h = size.height
    val nX = w * 0.17f
    val pX = w * 0.81f
    val p = completionMorphProgress.coerceIn(0f, 1f)
    val ep = entranceProgress.coerceIn(0f, 1f)
    val loopP = waitingLoopProgress.coerceIn(0f, 1f)

    // Helper for Batch 1: Soft liquid ribbon layer with Grok-style feathered tips (BlendMode.DstIn)
    fun drawLiquidRibbonLayer(
        leftX: Float,
        rightX: Float,
        topY: Float,
        bottomY: Float,
        drawContent: DrawScope.() -> Unit
    ) {
        val blockW = (rightX - leftX).coerceAtLeast(1f)
        val blockH = (bottomY - topY).coerceAtLeast(1f)
        if (leftX <= 0.01f && rightX >= w - 0.01f) {
            drawContent()
        } else {
            val layerRect = Rect(leftX, topY, rightX, bottomY)
            drawContext.canvas.saveLayer(layerRect, Paint())
            drawContent()
            val featherRatio = (36.dp.toPx() / blockW).coerceIn(0.12f, 0.30f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        featherRatio to Color.Black,
                        (1f - featherRatio) to Color.Black,
                        1.0f to Color.Transparent
                    ),
                    startX = leftX,
                    endX = rightX
                ),
                topLeft = Offset(leftX, topY),
                size = Size(blockW, blockH),
                blendMode = BlendMode.DstIn
            )
            drawContext.canvas.restore()
        }
    }

    // Universal Lifecycle solver for all 21 styles (Sunset Mirage master blueprint):
    // Phase A: Entrance (Wait 350ms -> expands from [nX, pX] to full width in 280ms)
    // Phase B: Speaking (Full width, organic voice amplitude reactivity)
    // Phase C: Reverse-logic Exit (Contracts inward to Cancel 'N' and Complete 'P' -> glides smoothly to right & dissolves)
    // Phase D: Slow-network Waiting loop (Gentle, repeating sweep from left to right)
    fun solveUniversalBounds(): Triple<Float, Float, Float> { // (left, right, alpha)
        return if (p <= 0.001f) {
            if (ep < 0.999f) {
                // Entrance: softly unfolds from [nX, pX] outward to full width in 280ms
                val curL = nX * (1f - ep)
                val curR = pX + (w - pX) * ep
                val a = (ep / 0.15f).coerceIn(0f, 1f)
                Triple(curL, curR, a)
            } else {
                Triple(0f, w, 1f)
            }
        } else if (p < 0.999f) {
            if (p < 0.42f) {
                // Exit Step 1: contracts inward from edges to Cancel 'N' and Complete 'P'
                val cr = (p / 0.42f).coerceIn(0f, 1f)
                val curL = nX * cr
                val curR = w - (w - pX) * cr
                Triple(curL, curR, 1f)
            } else {
                // Exit Step 2: that exact calm wave block glides smoothly across to the right and fades out
                val sr = ((p - 0.42f) / 0.58f).coerceIn(0f, 1f)
                val blockWidth = pX - nX
                val startLeft = nX
                val endLeft = w + 16.dp.toPx()
                val curL = startLeft + (endLeft - startLeft) * sr
                val curR = curL + blockWidth
                val fadeOut = (1f - sr).coerceIn(0f, 1f)
                val a = sin(fadeOut * (PI / 2).toFloat())
                Triple(curL, curR, a)
            }
        } else {
            // Waiting loop: calm wave sweeps repeatedly from left to right
            val loopW = (pX - nX) * 0.85f
            val curL = -loopW + (w + loopW * 2f) * loopP
            val curR = curL + loopW
            val normX = ((curL + curR) / 2f / w).coerceIn(0f, 1f)
            val a = sin(normX * PI.toFloat()).coerceIn(0f, 1f) * 0.90f
            Triple(curL, curR, a)
        }
    }

    val (curL, curR, alphaMult) = solveUniversalBounds()
    val effAlpha = alphaMult * glowBrightness
    if (effAlpha <= 0.005f) return

    val blockW = (curR - curL).coerceAtLeast(1f)

    drawLiquidRibbonLayer(curL, curR, 0f, h) {
        // Breathing Horizon: a soft band that rises with the voice level.
        val barH = (h * 0.35f + (h * 0.50f) * effectiveAmp).coerceAtMost(h * 0.85f)
        val topY = (h - barH).coerceAtLeast(0f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    palette.secondary.copy(alpha = 0.35f * effAlpha),
                    palette.primaryLight.copy(alpha = 0.85f * effAlpha),
                    palette.primaryVibrant.copy(alpha = 0.90f * effAlpha)
                ),
                startY = topY,
                endY = h
            ),
            topLeft = Offset(curL, topY),
            size = Size(blockW, barH)
        )
    }
}
