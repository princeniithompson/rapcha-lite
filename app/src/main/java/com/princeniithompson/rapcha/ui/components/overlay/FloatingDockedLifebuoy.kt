package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.R
import kotlinx.coroutines.delay

/**
 * Docked Lifebuoy Ring: Unclipped circular composable nested in the top-right notch
 * with standalone dragging and clicking affordances.
 */
@Composable
fun FloatingDockedLifebuoy(
    onLifebuoyClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    spinning: Boolean = false,
    bubbleVisible: Boolean = true,
    staticPreview: Boolean = false,
    modifier: Modifier = Modifier
) {
    // v23: rotation is read in the layer lambda only (no recomposition per frame).
    val spin: () -> Float = if (!staticPreview && spinning && bubbleVisible) {
        val transition = rememberInfiniteTransition(label = "ring")
        val animatedSpin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
            label = "spin"
        )
        ({ animatedSpin.value })
    } else {
        ({ 0f })
    }
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
        targetValue = if (staticPreview && (isPressed || tapPulse)) 0.97f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "lifebuoy_mock_press_scale"
    )
    Box(
        modifier = modifier
            .padding(end = 4.dp)
            .size(42.dp)
            .graphicsLayer {
                if (staticPreview) {
                    scaleX = pressScale
                    scaleY = pressScale
                }
            }
            .clip(CircleShape)
            .then(
                if (staticPreview) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = if (staticPreview) LocalIndication.current else null,
                onClick = {
                    if (staticPreview) {
                        tapPulse = true
                    }
                    onLifebuoyClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
            contentDescription = "Lifebuoy voice typing",
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer { if (!staticPreview && spinning) rotationZ = spin() }
        )
    }
}
