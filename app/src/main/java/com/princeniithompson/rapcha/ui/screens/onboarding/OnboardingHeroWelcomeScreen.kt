package com.princeniithompson.rapcha.ui.screens.onboarding

import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Page 1 Hero Welcome Screen:
 * Low-overhead pure Compose animated ambient backdrop in Rapcha's lifebuoy red, white, and ash
 * palette + curved scrolling sentence arc over a pulsing voice waveform capsule.
 * Animation states are read exclusively in draw/graphicsLayer phases so low-end devices
 * (e.g., Samsung Galaxy A15 / 3-4 GB RAM) never recompose or relayout per frame.
 */
@Composable
fun OnboardingHeroWelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var ambientDriftProvider: () -> Float = { 0f }
    var wavePhaseProvider: () -> Float = { 0f }
    var marqueeCycleProvider: () -> Float = { 0f }
    var pillPulseProvider: () -> Float = { 1f }

    if (isResumed) {
        val infiniteTransition = rememberInfiniteTransition(label = "hero_welcome_anim")

        val ambientDriftState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 12_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ambient_drift"
        )

        val wavePhaseState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_phase"
        )

        val marqueeCycleState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 15_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "marquee_cycle"
        )

        val pillPulseState = infiniteTransition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pill_pulse"
        )

        ambientDriftProvider = { ambientDriftState.value }
        wavePhaseProvider = { wavePhaseState.value }
        marqueeCycleProvider = { marqueeCycleState.value }
        pillPulseProvider = { pillPulseState.value }
    }

    val marqueeSentences = remember {
        listOf(
            "How would you like to set up the file? Here are a few options.",
            "Tell the team we'll ship the update right after lunch today.",
            "Turn spoken ideas into clear messages in seconds."
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF23272E))
            .testTag("onboarding_hero_welcome_screen")
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val isTablet = screenW >= 600.dp
        val isCompactHeight = screenH < 640.dp

        val logoSize = when {
            isTablet -> 38.dp
            isCompactHeight -> 26.dp
            else -> 32.dp
        }
        val brandFontSize = when {
            isTablet -> 34.sp
            isCompactHeight -> 24.sp
            else -> 28.sp
        }
        val headlineFontSize = when {
            isTablet -> 42.sp
            isCompactHeight -> 28.sp
            else -> 34.sp
        }
        val headlineLineHeight = when {
            isTablet -> 50.sp
            isCompactHeight -> 34.sp
            else -> 42.sp
        }
        val marqueeFontSizeSp = when {
            isTablet -> 22.sp
            isCompactHeight -> 15.sp
            else -> 17.sp
        }
        val pillWidth = when {
            isTablet -> 160.dp
            isCompactHeight -> 112.dp
            else -> 132.dp
        }
        val pillHeight = when {
            isTablet -> 66.dp
            isCompactHeight -> 48.dp
            else -> 56.dp
        }
        val horizontalPadding = if (isTablet) 40.dp else 24.dp

        // Full-bleed atmospheric animated backdrop matching Rapcha's red, white & ash lifebuoy bubble
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val baseGradient = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2329),
                            Color(0xFF3A3F47),
                            Color(0xFF6B1E23),
                            Color(0xFFA01820),
                            Color(0xFF321215)
                        )
                    )
                    val vignetteGradient = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.24f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.34f)
                        )
                    )
                    val upperGlowColors = listOf(
                        Color(0xFFF3F4F6).copy(alpha = 0.42f),
                        Color(0xFFD9232E).copy(alpha = 0.34f),
                        Color.Transparent
                    )
                    val lowerGlowColors = listOf(
                        Color(0xFFFFD6D6).copy(alpha = 0.36f),
                        Color(0xFFB40016).copy(alpha = 0.32f),
                        Color.Transparent
                    )
                    val w = size.width
                    val h = size.height
                    val upperRadius = w * 0.72f
                    val lowerRadius = w * 0.68f

                    onDrawBehind {
                        drawRect(brush = baseGradient)

                        val drift = ambientDriftProvider().toDouble()
                        val upperX = w * (0.48f + 0.14f * cos(drift).toFloat())
                        val upperY = h * (0.46f + 0.08f * sin(drift).toFloat())
                        val upperCenter = Offset(upperX, upperY)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = upperGlowColors,
                                center = upperCenter,
                                radius = upperRadius
                            ),
                            center = upperCenter,
                            radius = upperRadius
                        )

                        val lowerX = w * (0.38f - 0.12f * sin(drift).toFloat())
                        val lowerY = h * (0.68f + 0.06f * cos(drift).toFloat())
                        val lowerCenter = Offset(lowerX, lowerY)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = lowerGlowColors,
                                center = lowerCenter,
                                radius = lowerRadius
                            ),
                            center = lowerCenter,
                            radius = lowerRadius
                        )

                        drawRect(brush = vignetteGradient)
                    }
                }
        )

        // Foreground Safe Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = if (isCompactHeight) 16.dp else 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(if (isCompactHeight) 20.dp else 44.dp))

            // Brand Lockup: Lifesaver Ring + Rapcha
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                    contentDescription = stringResource(id = R.string.app_name),
                    modifier = Modifier.size(logoSize)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(id = R.string.app_name),
                    fontSize = brandFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.3).sp
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 18.dp))

            // Hero Headline: "4x faster than typing"
            Text(
                text = "4x faster than typing",
                fontFamily = EditorialSerifFontFamily,
                fontSize = headlineFontSize,
                lineHeight = headlineLineHeight,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.96f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 8.dp)
            )

            // Centerpiece: Curved Arc Marquee + Pulsing Voice Pill
            val density = LocalDensity.current
            val marqueePx = with(density) { marqueeFontSizeSp.toPx() }
            val shadowVOffsetPx = with(density) { 1.5.dp.toPx() }
            val boldTypeface = remember { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val marqueeTextPaint = remember(marqueePx, boldTypeface) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = marqueePx
                    typeface = boldTypeface
                }
            }
            val marqueeShadowPaint = remember(marqueePx, boldTypeface) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = marqueePx
                    typeface = boldTypeface
                }
            }
            val sentenceWidths = remember(marqueeSentences, marqueeTextPaint) {
                FloatArray(marqueeSentences.size) { idx ->
                    marqueeTextPaint.measureText(marqueeSentences[idx])
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                contentAlignment = Alignment.Center
            ) {
                // Curved scrolling sentence path with cached Path & Paints (zero per-frame allocations/blurs)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val w = size.width
                            val h = size.height
                            val centerY = h * 0.44f
                            val arcHeight = minOf(w * 0.20f, h * 0.24f)
                            val cachedArcPath = AndroidPath().apply {
                                moveTo(-w * 0.35f, centerY + arcHeight)
                                quadTo(
                                    w * 0.50f,
                                    centerY - arcHeight,
                                    w * 1.35f,
                                    centerY + arcHeight
                                )
                            }
                            val pathLengthApprox = w * 1.78f

                            onDrawBehind {
                                val cycle = marqueeCycleProvider()
                                val activeIndex = cycle.toInt().coerceIn(0, marqueeSentences.lastIndex)
                                val localProgress = cycle - activeIndex
                                val sentence = marqueeSentences[activeIndex]
                                val textWidth = sentenceWidths[activeIndex]

                                val alphaFade = when {
                                    localProgress < 0.12f -> localProgress / 0.12f
                                    localProgress > 0.88f -> (1f - localProgress) / 0.12f
                                    else -> 1f
                                }.coerceIn(0f, 1f)

                                val startOffset = pathLengthApprox * 0.72f -
                                    (localProgress * (pathLengthApprox * 0.48f + textWidth * 0.55f))

                                marqueeShadowPaint.alpha = (alphaFade * 88f).toInt().coerceIn(0, 255)
                                marqueeTextPaint.alpha = (alphaFade * 245f).toInt().coerceIn(0, 255)

                                val nativeCanvas = drawContext.canvas.nativeCanvas
                                nativeCanvas.drawTextOnPath(
                                    sentence,
                                    cachedArcPath,
                                    startOffset,
                                    shadowVOffsetPx,
                                    marqueeShadowPaint
                                )
                                nativeCanvas.drawTextOnPath(
                                    sentence,
                                    cachedArcPath,
                                    startOffset,
                                    0f,
                                    marqueeTextPaint
                                )
                            }
                        }
                )

                // Centered Frosted Voice Waveform Capsule (fixed size + GPU scale via graphicsLayer = 0 relayouts)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.18f),
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.92f)),
                    modifier = Modifier
                        .padding(top = if (isCompactHeight) 36.dp else 54.dp)
                        .size(width = pillWidth, height = pillHeight)
                        .graphicsLayer {
                            val scale = pillPulseProvider()
                            scaleX = scale
                            scaleY = scale
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val barCount = 15
                        val totalW = size.width
                        val slotW = totalW / barCount
                        val barW = slotW * 0.44f
                        val centerY = size.height / 2f
                        val wavePhase = wavePhaseProvider()
                        val minBarH = 4.dp.toPx()
                        val barColor = Color.White.copy(alpha = 0.95f)
                        val barRadius = CornerRadius(barW / 2f, barW / 2f)

                        for (i in 0 until barCount) {
                            val normDist = kotlin.math.abs((i - (barCount - 1) / 2f) / ((barCount - 1) / 2f))
                            val envelope = 1f - (normDist * 0.62f)
                            val wave = (0.48f + 0.52f * sin((wavePhase + i * 0.65f).toDouble()).toFloat())
                            val barH = (size.height * 0.80f * envelope * wave).coerceAtLeast(minBarH)
                            val x = i * slotW + (slotW - barW) / 2f
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, centerY - barH / 2f),
                                size = Size(barW, barH),
                                cornerRadius = barRadius
                            )
                        }
                    }
                }
            }

            // Minimal Page Indicator Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = if (isCompactHeight) 14.dp else 22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.42f))
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.42f))
                )
            }

            // Bottom "Get started" Pill Button
            Button(
                onClick = onGetStarted,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF141218)
                ),
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .testTag("btn_hero_get_started")
            ) {
                Text(
                    text = "Get started",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF141218)
                )
            }
        }
    }
}
