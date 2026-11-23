package com.princeniithompson.rapcha.ui.components.overlay.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import com.princeniithompson.rapcha.ui.components.overlay.NotchedContainerShape
import com.princeniithompson.rapcha.ui.components.overlay.createNotchedPath
import com.princeniithompson.rapcha.ui.components.overlay.drawNotchedOverlayBackground

/**
 * Renders the dark frosted-glass container with the dynamic multi-style Aurora Glow bloom
 * tailored to the custom notch path. Strict clipping ensures zero glow/lighting leaks outside the container.
 */
@Composable
fun FloatingAuroraGlowSection(
    notchedShape: NotchedContainerShape,
    palette: AuroraColorPalette,
    glowStyleId: String,
    state: FloatingDictationPopupState,
    isRecording: Boolean,
    cornerRadius: Dp = FloatingNotchedDimensions.CornerRadius,
    notchRadius: Dp = FloatingNotchedDimensions.NotchRadius,
    notchCenterOffsetX: Dp = FloatingNotchedDimensions.NotchCenterOffsetX,
    notchCenterOffsetY: Dp = FloatingNotchedDimensions.NotchCenterOffsetY,
    filletRadius: Dp = FloatingNotchedDimensions.FilletRadius,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 19.dp)
            .clip(notchedShape) // Strict clipping before draw prevents any glow/aurora from leaking outside
            .drawWithCache {
                val path = createNotchedPath(
                    size = size,
                    density = this,
                    cornerRadius = cornerRadius,
                    notchRadius = notchRadius,
                    notchCenterOffsetX = notchCenterOffsetX,
                    notchCenterOffsetY = notchCenterOffsetY,
                    filletRadius = filletRadius
                )
                val cx = size.width - notchCenterOffsetX.toPx()
                val cy = notchCenterOffsetY.toPx()
                val notchBloomYOffsetPx = 10.dp.toPx()
                val notchBloomRadiusPx = 42.dp.toPx()
                val glassBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xAD081210),
                        Color(0xC4050D0B)
                    )
                )
                val topSheenBrush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0x16FFFFFF),
                        0.38f to Color.Transparent
                    )
                )
                val borderStroke = Stroke(
                    width = 1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )

                onDrawBehind {
                    val baseAmp = if (isRecording || state.mimicTalkingWhenSilent) state.effectiveAmp else 0.06f
                    drawNotchedOverlayBackground(
                        path = path,
                        palette = palette,
                        glowPulse = state.glowPulse,
                        cx = cx,
                        cy = cy,
                        styleId = glowStyleId,
                        effectiveAmp = baseAmp,
                        phase1 = state.phase1,
                        phase2 = state.phase2,
                        morphProgress = state.morphProgress,
                        sunsetEntranceProgress = state.sunsetEntranceProgress,
                        waitingLoopProgress = state.waitingLoopProgress,
                        lowPower = state.lowPower,
                        glassBrush = glassBrush,
                        topSheenBrush = topSheenBrush,
                        borderStroke = borderStroke,
                        notchBloomYOffsetPx = notchBloomYOffsetPx,
                        notchBloomRadiusPx = notchBloomRadiusPx
                    )
                }
            }
            .padding(top = 10.dp, bottom = 10.dp, start = 14.dp, end = 14.dp),
        content = content
    )
}
