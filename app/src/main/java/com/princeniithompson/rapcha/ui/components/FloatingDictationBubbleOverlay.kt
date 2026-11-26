package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.ui.components.overlay.PillActionButton
import com.princeniithompson.rapcha.ui.components.overlay.isColorDark
import com.princeniithompson.rapcha.ui.components.overlay.toDangerPalette
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette as rememberDynamicAuroraPaletteFromOverlay
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingAuroraGlowSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingButtonRowSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingNotchedCutoutSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingTranscriptSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberFloatingDictationPopupState
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberNotchedContainerShape

/**
 * Compact 2-row Floating Dictation Popup coordinating modular UI sections:
 * - Dynamic Aurora Glow bloom diffused within the notched frosted-glass container
 * - Fixed-height 2-line (Option A, ~101dp) or 1-line (Option B, ~83dp) auto-scrolling transcript
 * - Unified bottom bar with Cancel, inline live session timer (center or left), and Complete
 * - Docked lifebuoy ring nested unclipped in the top-right cutout notch
 */
@Composable
fun FloatingDictationPopup(
    transcriptText: String,
    isRecording: Boolean,
    isPendingFinalizing: Boolean,
    isPolishing: Boolean,
    audioAmplitude: Float = 0f,
    glowStyleId: String = GlowAnimationCatalogue.DEFAULT_ID,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onLifebuoyClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Settings preview only. The live overlay leaves this false, so it still
     * shows the transcript and the live Cancel/Complete row.
     */
    blankPreview: Boolean = false,
    mimicTalkingWhenSilent: Boolean = false,
    bubbleVisible: Boolean = true,
    lowPower: Boolean = false,
    /** v26: the live bubble hides Polish (Smart polish is a Settings switch). The onboarding demo shows it. */
    showPolish: Boolean = false,
    /** Elapsed seconds in the active dictation session (displayed in tabular numerals). */
    elapsedSeconds: Int = 0,
    /** Home-screen card preview mode: static picture, zero infinite loops, mock-only button press effect. */
    staticPreview: Boolean = false
) {
    val liveNetworkProblem by com.princeniithompson.rapcha.service.FloatingBubbleManager.isNetworkProblem.collectAsState()
    val isNetworkProblem = if (staticPreview) false else liveNetworkProblem
    val effectiveBubbleVisible = if (staticPreview) false else bubbleVisible
    val basePalette = rememberDynamicAuroraPaletteFromOverlay()
    val palette = if (isNetworkProblem) basePalette.toDangerPalette() else basePalette
    val state = rememberFloatingDictationPopupState(
        transcriptText = transcriptText,
        isRecording = isRecording,
        isPendingFinalizing = if (staticPreview) false else isPendingFinalizing,
        isPolishing = if (staticPreview) false else isPolishing,
        audioAmplitude = audioAmplitude,
        mimicTalkingWhenSilent = mimicTalkingWhenSilent,
        bubbleVisible = effectiveBubbleVisible,
        lowPower = lowPower
    )
    val notchedShape = rememberNotchedContainerShape()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        // 1. Notched Glass Container Box with Aurora Glow background
        FloatingAuroraGlowSection(
            notchedShape = notchedShape,
            palette = palette,
            glowStyleId = glowStyleId,
            state = state,
            isRecording = isRecording
        ) {
            if (blankPreview) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillActionButton(
                            onClick = onCancelClick,
                            backgroundColor = Color(0x5C0E1417),
                            borderColor = Color(0x30FFFFFF),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Cancel",
                                    color = Color(0xFFF1F5F9),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        val polishInk = if (isColorDark(palette.primaryVibrant)) Color.White else Color(0xFF042F2E)
                        PillActionButton(
                            onClick = onPolishClick,
                            backgroundColor = palette.primaryVibrant,
                            borderColor = Color(0x36FFFFFF),
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "✦", color = polishInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Polish",
                                    color = polishInk,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    // Row 1: Fixed 2-line (38.dp) live transcript with movie-credits upward roll
                    FloatingTranscriptSection(
                        transcriptText = transcriptText,
                        cursorAlphaProvider = if (staticPreview) ({ 1f }) else state.cursorAlphaProvider,
                        palette = palette,
                        scrollState = state.scrollState,
                        staticPreview = staticPreview
                    )

                    Spacer(modifier = Modifier.height(9.dp))

                    // Row 2: Action & Standalone Center Timer Bar (34.dp)
                    FloatingButtonRowSection(
                        state = state,
                        palette = palette,
                        onCancelClick = onCancelClick,
                        onPolishClick = onPolishClick,
                        onCompleteClick = onCompleteClick,
                        showPolish = showPolish,
                        elapsedSeconds = elapsedSeconds,
                        isNetworkProblem = isNetworkProblem,
                        staticPreview = staticPreview
                    )
                }
            }
        }

        // 2. Docked Lifebuoy Ring (Unclipped, nested in notch)
        FloatingNotchedCutoutSection(
            onLifebuoyClick = onLifebuoyClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            spinning = if (staticPreview) false else (state.isFinalizing || state.isPolishing),
            bubbleVisible = effectiveBubbleVisible,
            staticPreview = staticPreview,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
