package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fixed 2-line (`38.dp`) live transcript viewport that rolls smoothly upwards like movie end
 * credits whenever speech wraps onto a new line.
 *
 * Locking the viewport height keeps the bubble rock-steady from 0 words to long dictations and
 * avoids repeated Android WindowManager relayouts on low-end devices.
 */
@Composable
fun FloatingTranscriptBox(
    transcriptText: String,
    cursorAlphaProvider: () -> Float,
    palette: AuroraColorPalette,
    scrollState: ScrollState,
    staticPreview: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp) // Exactly 2 crisp lines at 19.sp lineHeight
            .padding(end = 42.dp) // Clears the docked top-right lifebuoy notch cleanly
            .verticalScroll(scrollState, enabled = !staticPreview),
        contentAlignment = Alignment.TopStart
    ) {
        if (transcriptText.isBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(16.dp)
                        .graphicsLayer { alpha = cursorAlphaProvider() }
                        .clip(CircleShape)
                        .background(palette.primaryVibrant)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "Listening — speak now...",
                    style = TextStyle(
                        color = Color(0xA6E2E8F0),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        shadow = Shadow(
                            color = Color(0xB3000000),
                            blurRadius = 4f
                        )
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = transcriptText,
                style = TextStyle(
                    color = Color(0xFFFFFFFF),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 19.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    shadow = Shadow(
                        color = Color(0xCC000000),
                        offset = Offset(0f, 1f),
                        blurRadius = 5f
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
