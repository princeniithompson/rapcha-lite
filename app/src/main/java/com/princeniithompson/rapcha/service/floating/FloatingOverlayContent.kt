package com.princeniithompson.rapcha.service.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.FloatingCollapsedBubble
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import com.princeniithompson.rapcha.ui.components.overlay.toDangerPalette
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

/** Label shown under the timer in the compact pill. */
internal fun compactPillLabel(finishing: Boolean, networkProblem: Boolean): String = when {
    finishing -> "Finishing"
    networkProblem -> "Offline"
    else -> "Listening"
}

/**
 * v26 overlay tree. While a session is open it shows the active bubble:
 * - Words ([BubbleChromeStyle.ORIGINAL]): the original v24 notched container with the
 *   Breathing Horizon glow, the live transcript and Cancel | Complete.
 * - Mini ([BubbleChromeStyle.COMPACT]): the compact pill with the same glow.
 * Otherwise the collapsed lifebuoy bubble. The glow follows the voice level and stops
 * animating while the window is hidden ([bubbleVisible] false).
 * The system font scale is pinned to 1 here so the bubble keeps its shape at any font size.
 */
@Composable
fun FloatingOverlayContent(
    recording: Boolean,
    pendingFinalizing: Boolean,
    isExpanded: Boolean,
    isShrunk: Boolean,
    isSnappedToRight: Boolean,
    elapsedSeconds: Int = 0,
    transcript: String = "",
    audioAmplitude: Float = 0f,
    bubbleVisible: Boolean = true,
    chrome: BubbleChromeStyle? = null,
    repairKind: A11yRepairKind = A11yRepairKind.NONE,
    onRepairClick: () -> Unit = {},
    onRingClick: () -> Unit,
    onCancelClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onLongPressBubble: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        MyApplicationTheme(darkTheme = true, dynamicColor = true) {
            val savedChrome by FloatingBubbleManager.bubbleChrome.collectAsState()
            val activeChrome = chrome ?: savedChrome
            val words = isExpanded && activeChrome == BubbleChromeStyle.ORIGINAL
            val rootModifier = if (words) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.wrapContentSize()
            Box(modifier = rootModifier) {
                if (isExpanded) {
                    val problem by FloatingBubbleManager.isNetworkProblem.collectAsState()
                    if (words) {
                        FloatingDictationPopup(
                            transcriptText = transcript,
                            isRecording = recording,
                            isPendingFinalizing = pendingFinalizing,
                            isPolishing = false,
                            audioAmplitude = audioAmplitude,
                            glowStyleId = GlowAnimationCatalogue.DEFAULT_ID,
                            bubbleVisible = bubbleVisible,
                            onCancelClick = onCancelClick,
                            onPolishClick = {},
                            onCompleteClick = onCompleteClick,
                            onLifebuoyClick = onRingClick,
                            onDragStart = onDragStart,
                            onDrag = onDrag,
                            onDragEnd = onDragEnd
                        )
                    } else {
                        val base = rememberDynamicAuroraPalette()
                        val palette = if (problem) base.toDangerPalette() else base
                        CompactRecordingBar(
                            elapsedSeconds = elapsedSeconds,
                            appName = compactPillLabel(pendingFinalizing, problem),
                            glowStyleId = GlowAnimationCatalogue.DEFAULT_ID,
                            palette = palette,
                            audioAmplitude = audioAmplitude,
                            processing = pendingFinalizing,
                            bubbleVisible = bubbleVisible,
                            onCancel = onCancelClick,
                            onComplete = onCompleteClick,
                            onDragStart = onDragStart,
                            onDrag = onDrag,
                            onDragEnd = onDragEnd
                        )
                    }
                } else {
                    FloatingCollapsedBubble(
                        isRecording = recording,
                        isShrunk = isShrunk,
                        isSnappedToRight = isSnappedToRight,
                        repairKind = repairKind,
                        onRepairClick = onRepairClick,
                        onClick = onRingClick,
                        onLongPress = onLongPressBubble,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                }
            }
        }
    }
}
