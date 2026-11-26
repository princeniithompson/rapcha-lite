package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.SessionClockFormat

import kotlinx.coroutines.delay

@Composable
fun PillActionButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mockPressFeedback: Boolean = false,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var tapPulse by remember { mutableStateOf(false) }
    LaunchedEffect(tapPulse) {
        if (tapPulse) {
            delay(100L)
            tapPulse = false
        }
    }
    val pressScale by animateFloatAsState(
        targetValue = if (mockPressFeedback && (isPressed || tapPulse)) 0.97f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pill_mock_press_scale"
    )
    Box(
        modifier = modifier
            .height(34.dp)
            .graphicsLayer {
                if (mockPressFeedback) {
                    scaleX = pressScale
                    scaleY = pressScale
                }
            }
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.9.dp, borderColor, CircleShape)
                } else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = {
                    if (mockPressFeedback) {
                        tapPulse = true
                    }
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Standalone center live timer (`● 00:35`) with NO container or border.
 * Uses a classic standalone red blinking recording dot (`#FF3B30`) plus crisp bright white
 * tabular digits with a dark drop shadow so it remains razor-sharp over white, black, and
 * colorful pages alike.
 */
@Composable
private fun StandaloneCenterTimer(
    elapsedSeconds: Int,
    isNetworkProblem: Boolean,
    blinkAlphaProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val label = if (isNetworkProblem) "Offline" else SessionClockFormat.format(elapsedSeconds)
    val labelColor = if (isNetworkProblem) Color(0xFFFF7B7B) else Color(0xFFFFFFFF)

    Row(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .testTag("words_timer_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Standalone classic recording red blinking dot (read strictly in graphicsLayer phase)
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    alpha = (0.12f + 0.88f * blinkAlphaProvider()).coerceIn(0.12f, 1f)
                }
                .clip(CircleShape)
                .background(Color(0xFFFF3B30))
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = TextStyle(
                color = labelColor,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.35.sp,
                fontFeatureSettings = "tnum",
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                shadow = Shadow(
                    color = Color(0xE6000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 6f
                )
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Unified compact action bar: `Cancel · ● 00:35 · Complete` (standalone center timer).
 * [showPolish] keeps the Polish pill for the onboarding demo.
 */
@Composable
fun FloatingActionRow(
    isFinalizing: Boolean,
    isPolishing: Boolean,
    palette: AuroraColorPalette,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPolish: Boolean = false,
    elapsedSeconds: Int = 0,
    isNetworkProblem: Boolean = false,
    blinkAlphaProvider: () -> Float = { 1f },
    mockPressFeedback: Boolean = false
) {
    val onAccentColor = if (isColorDark(palette.primaryVibrant)) Color.White else Color(0xFF042F2E)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cancel Button — calm smoked-glass tonal pill
        PillActionButton(
            onClick = onCancelClick,
            backgroundColor = Color(0x5C0E1417),
            borderColor = Color(0x30FFFFFF),
            enabled = !isFinalizing && !isPolishing,
            mockPressFeedback = mockPressFeedback,
            modifier = Modifier
                .weight(if (showPolish) 0.95f else 1f)
                .testTag("words_cancel_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel",
                    tint = Color(0xFFFF8A80),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Cancel",
                    color = Color(0xFFF1F5F9),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Standalone live timer in the center (no container or border)
        if (!showPolish) {
            StandaloneCenterTimer(
                elapsedSeconds = elapsedSeconds,
                isNetworkProblem = isNetworkProblem,
                blinkAlphaProvider = blinkAlphaProvider
            )
        }

        // Polish Button (onboarding demo only)
        if (showPolish) PillActionButton(
            onClick = onPolishClick,
            backgroundColor = Color(0x2BFFFFFF),
            borderColor = Color(0x30FFFFFF),
            enabled = !isPolishing && !isFinalizing,
            mockPressFeedback = mockPressFeedback,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isPolishing) {
                    Text(
                        text = "Polishing…",
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Text(
                        text = "✦",
                        color = palette.primaryLight,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Polish",
                        color = Color(0xFFF1F5F9),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Complete Button — vibrant primary CTA
        PillActionButton(
            onClick = onCompleteClick,
            backgroundColor = if (isFinalizing) palette.primaryVibrant.copy(alpha = 0.86f) else palette.primaryVibrant,
            borderColor = Color(0x36FFFFFF),
            enabled = !isFinalizing && !isPolishing,
            mockPressFeedback = mockPressFeedback,
            modifier = Modifier
                .weight(if (showPolish) 1.15f else 1.12f)
                .testTag("words_complete_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isFinalizing) {
                    Text(
                        text = "Finishing…",
                        color = onAccentColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Complete",
                        tint = onAccentColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Complete",
                        color = onAccentColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
