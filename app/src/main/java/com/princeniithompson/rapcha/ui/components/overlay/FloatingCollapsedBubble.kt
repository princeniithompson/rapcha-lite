package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.service.floating.BubbleChromeGeometry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * v25 lean collapsed lifebuoy bubble. Static (no pulse or size animation).
 * Tap starts voice typing, drag moves it, a long press calls [onLongPress].
 * Shows a static tint while recording, an amber ring after a clipboard fallback,
 * and a repair badge when accessibility needs attention.
 */
@Composable
fun FloatingCollapsedBubble(
    isRecording: Boolean,
    isShrunk: Boolean = false,
    isSnappedToRight: Boolean = true,
    repairKind: A11yRepairKind = A11yRepairKind.NONE,
    onRepairClick: () -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isNetworkProblem by com.princeniithompson.rapcha.service.FloatingBubbleManager.isNetworkProblem.collectAsState()
    val isClipboardFallback by com.princeniithompson.rapcha.service.FloatingBubbleManager.isClipboardFallbackIndicator.collectAsState()

    val baseBubbleSize = BubbleChromeGeometry.CIRCLE_SIZE_DP.dp
    val bubbleSize = if (isShrunk) 38.dp else baseBubbleSize
    val imageSize = if (isShrunk) 34.dp else baseBubbleSize
    val imageAlpha = if (isShrunk) 0.85f else 1.0f
    val windowSize = BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP.dp

    Box(
        modifier = modifier
            .size(windowSize)
            .padding(BubbleChromeGeometry.WINDOW_PADDING_DP.dp)
            .testTag("collapsed_bubble")
            .pointerInput(repairKind) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isLongPressTriggered = false
                        var isDragging = false
                        var totalDrag = 0f

                        val longPressJob = launch {
                            delay(650L)
                            if (!isDragging && totalDrag < 18f) {
                                isLongPressTriggered = true
                                onLongPress()
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                // Pointer up
                                change.consume()
                                longPressJob.cancel()
                                if (isDragging) {
                                    onDragEnd()
                                } else if (!isLongPressTriggered) {
                                    if (repairKind != A11yRepairKind.NONE) {
                                        onRepairClick()
                                    } else {
                                        onClick()
                                    }
                                }
                                break
                            } else {
                                val posChange = change.positionChange()
                                val dx = posChange.x
                                val dy = posChange.y
                                val dist = hypot(dx, dy)
                                totalDrag += dist

                                if (totalDrag > 12f && !isDragging) {
                                    isDragging = true
                                    longPressJob.cancel()
                                    onDragStart()
                                }

                                if (isDragging) {
                                    change.consume()
                                    onDrag(dx, dy)
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = if (isSnappedToRight) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(bubbleSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
                if (isRecording) {
                    val recordingGlow = if (isNetworkProblem) {
                        Color(0xFFFF3B30).copy(alpha = 0.55f)
                    } else {
                        Color(0xFF10B981).copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .size(bubbleSize * 0.95f)
                            .background(recordingGlow, CircleShape)
                    )
                }
                if (isClipboardFallback) {
                    Canvas(modifier = Modifier.size(bubbleSize)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.minDimension / 2f - 2.dp.toPx()
                        drawCircle(
                            color = Color(0xFFD29922).copy(alpha = 0.35f),
                            radius = radius * 1.15f,
                            center = center
                        )
                        drawCircle(
                            color = Color(0xFFD29922),
                            radius = radius,
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
                if (repairKind != A11yRepairKind.NONE) {
                    val repairTint = if (repairKind == A11yRepairKind.PERMISSION_REVOKED) {
                        Color(0xFFFF3B30)
                    } else {
                        Color(0xFFD29922)
                    }
                    Canvas(modifier = Modifier.size(bubbleSize)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.minDimension / 2f - 2.dp.toPx()
                        drawCircle(
                            color = repairTint.copy(alpha = 0.28f),
                            radius = radius * 1.08f,
                            center = center
                        )
                        drawCircle(
                            color = repairTint,
                            radius = radius,
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(imageSize),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                        contentDescription = if (repairKind != A11yRepairKind.NONE) {
                            "Rapcha needs accessibility repair. Tap to fix."
                        } else {
                            "Floating voice bubble"
                        },
                        alpha = imageAlpha,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (repairKind != A11yRepairKind.NONE) {
                    val badgeColor = if (repairKind == A11yRepairKind.PERMISSION_REVOKED) {
                        Color(0xFFFF3B30)
                    } else {
                        Color(0xFFD29922)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(18.dp)
                            .background(Color(0xFF1A1412), CircleShape)
                            .border(1.5.dp, badgeColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = "Repair accessibility",
                            tint = badgeColor,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
        }
    }
}
