package com.princeniithompson.rapcha.ui.components.overlay.sections

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * State holder managing animations, audio amplitude smoothing, and completion transitions
 * for the floating dictation popup.
 */
@Stable
class FloatingDictationPopupState(
    private val glowPulseProvider: () -> Float,
    private val phase1Provider: () -> Float,
    private val phase2Provider: () -> Float,
    private val animatedAmplitudeProvider: () -> Float,
    private val idleBreathingProvider: () -> Float,
    val cursorAlphaProvider: () -> Float,
    private val morphProgressProvider: () -> Float,
    val sunsetEntranceProgress: Float,
    private val waitingLoopProgressProvider: () -> Float,
    val isFinalizing: Boolean,
    val isPolishing: Boolean,
    val scrollState: ScrollState,
    val mimicTalkingWhenSilent: Boolean = false,
    val lowPower: Boolean = false,
    private val onLocalCompleteTriggered: () -> Unit
) {
    val glowPulse: Float get() = glowPulseProvider()
    val phase1: Float get() = phase1Provider()
    val phase2: Float get() = phase2Provider()
    val animatedAmplitude: Float get() = animatedAmplitudeProvider()
    val idleBreathing: Float get() = idleBreathingProvider()
    val cursorAlpha: Float get() = cursorAlphaProvider()
    val morphProgress: Float get() = morphProgressProvider()
    val waitingLoopProgress: Float get() = waitingLoopProgressProvider()

    val effectiveAmp: Float
        get() {
            val animAmp = animatedAmplitude
            if (mimicTalkingWhenSilent) {
                return if (animAmp > 0.05f) {
                    animAmp.coerceIn(0.85f, 1.0f)
                } else {
                    (0.88f + 0.12f * kotlin.math.abs(kotlin.math.sin(phase1 * 3.2f))).coerceIn(0.85f, 1.0f)
                }
            }
            val voiceWeight = (animAmp / 0.18f).coerceIn(0f, 1f)
            return ((1f - voiceWeight) * idleBreathing + animAmp).coerceIn(0.08f, 1f)
        }

    fun markLocalComplete() {
        onLocalCompleteTriggered()
    }
}

@Composable
fun rememberFloatingDictationPopupState(
    transcriptText: String,
    isRecording: Boolean,
    isPendingFinalizing: Boolean,
    isPolishing: Boolean,
    audioAmplitude: Float,
    mimicTalkingWhenSilent: Boolean = false,
    bubbleVisible: Boolean = true,
    lowPower: Boolean = false
): FloatingDictationPopupState {
    var glowPulseProvider: () -> Float = { 0.95f }
    var phase1Provider: () -> Float = { 0f }
    var phase2Provider: () -> Float = { 0f }
    var idleBreathingProvider: () -> Float = { 0.12f }
    var waitingLoopAnimProvider: () -> Float = { 0f }
    var cursorAlphaProvider: () -> Float = { 1f }

    if (bubbleVisible) {
        val infiniteTransition = rememberInfiniteTransition(label = "auroraGlow")
        if (lowPower) {
            // Low-power mode: animate only 2 values with longer, gentler tweens, read only in draw/layer phase
            val p1State = infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = (2f * Math.PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(3600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "phase1"
            )
            val cAlphaState = infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "cursorBlink"
            )
            phase1Provider = { p1State.value }
            phase2Provider = { (2f * Math.PI).toFloat() - p1State.value }
            glowPulseProvider = { 0.92f + 0.08f * kotlin.math.sin(p1State.value.toDouble()).toFloat() }
            idleBreathingProvider = { 0.12f }
            waitingLoopAnimProvider = { (p1State.value / (2f * Math.PI.toFloat())).coerceIn(0f, 1f) }
            cursorAlphaProvider = { cAlphaState.value }
        } else {
            val animGlowPulseState = infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowPulse"
            )
            val animPhase1State = infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = (2f * Math.PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "phase1"
            )
            val animPhase2State = infiniteTransition.animateFloat(
                initialValue = (2f * Math.PI).toFloat(),
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "phase2"
            )
            val animIdleBreathingState = infiniteTransition.animateFloat(
                initialValue = 0.08f,
                targetValue = 0.18f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "idleBreathing"
            )
            val animWaitingLoopState = infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "waitingLoopAnim"
            )
            val animCursorAlphaState = infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "cursorBlink"
            )
            glowPulseProvider = { animGlowPulseState.value }
            phase1Provider = { animPhase1State.value }
            phase2Provider = { animPhase2State.value }
            idleBreathingProvider = { animIdleBreathingState.value }
            waitingLoopAnimProvider = { animWaitingLoopState.value }
            cursorAlphaProvider = { animCursorAlphaState.value }
        }
    }

    // Asymmetric audio amplitude tracking; silent mimicTalkingWhenSilent modulation runs in draw phase.
    val rawTargetAmp = if (isRecording && audioAmplitude > 0.05f) {
        audioAmplitude.coerceIn(0f, 1f)
    } else 0f
    var prevTargetAmp by remember { mutableFloatStateOf(0f) }
    val isAttacking = rawTargetAmp > prevTargetAmp

    val animatedAmplitudeState = animateFloatAsState(
        targetValue = rawTargetAmp,
        animationSpec = if (isAttacking) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "animatedAmplitude"
    )

    LaunchedEffect(rawTargetAmp) {
        prevTargetAmp = rawTargetAmp
    }

    var isCompletingLocally by remember { mutableStateOf(false) }
    val isFinalizing = isPendingFinalizing || isCompletingLocally
    val isFinishingActive = isFinalizing || isPolishing

    val exitAnim = remember { Animatable(0f) }
    LaunchedEffect(isFinishingActive) {
        if (isFinishingActive) {
            exitAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1400, easing = LinearEasing)
            )
        } else {
            exitAnim.snapTo(0f)
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            isCompletingLocally = false
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(transcriptText) {
        if (transcriptText.isNotEmpty()) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    return remember(
        isFinalizing, isPolishing, scrollState, mimicTalkingWhenSilent, lowPower, bubbleVisible
    ) {
        FloatingDictationPopupState(
            glowPulseProvider = glowPulseProvider,
            phase1Provider = phase1Provider,
            phase2Provider = phase2Provider,
            animatedAmplitudeProvider = { animatedAmplitudeState.value },
            idleBreathingProvider = idleBreathingProvider,
            cursorAlphaProvider = cursorAlphaProvider,
            morphProgressProvider = { exitAnim.value },
            sunsetEntranceProgress = 1f,
            waitingLoopProgressProvider = {
                if (isFinishingActive && exitAnim.value >= 0.999f) waitingLoopAnimProvider() else 0f
            },
            isFinalizing = isFinalizing,
            isPolishing = isPolishing,
            scrollState = scrollState,
            mimicTalkingWhenSilent = mimicTalkingWhenSilent,
            lowPower = lowPower,
            onLocalCompleteTriggered = {
                isCompletingLocally = true
            }
        )
    }
}
