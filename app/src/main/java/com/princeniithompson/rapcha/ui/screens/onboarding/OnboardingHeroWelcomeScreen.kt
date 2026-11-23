package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Page 1 Hero Welcome Screen — Rapcha's original "Lifesaver Acoustic Beacon":
 *
 * Uses Rapcha's live Material You Dynamic Dark color scheme so the deep atmospheric studio
 * backdrop, radial aura, sonar ripples, showcase card, and CTA harmonize seamlessly with the
 * user's wallpaper and transition naturally into the rest of onboarding — while keeping the
 * red-and-white Lifesaver Ring popping cleanly at center stage.
 *
 * Low-end device performance (e.g., Infinix Hot 11 / Helio G70):
 * - Zero Modifier.blur(), zero AGSL shaders, zero per-frame allocations.
 * - All continuous motion is read exclusively in drawWithCache / graphicsLayer phases.
 * - Animations pause automatically when the lifecycle is not RESUMED.
 */
@Composable
fun OnboardingHeroWelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    MyApplicationTheme(darkTheme = true, dynamicColor = true) {
        OnboardingHeroWelcomeContent(
            onGetStarted = onGetStarted,
            modifier = modifier
        )
    }
}

@Composable
private fun OnboardingHeroWelcomeContent(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
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

    var ripplePhaseProvider: () -> Float = { 0f }
    var ringFloatProvider: () -> Float = { 0f }
    var ringBreathProvider: () -> Float = { 1f }

    if (isResumed) {
        val infiniteTransition = rememberInfiniteTransition(label = "rapcha_beacon_hero")

        val ripplePhaseState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3_600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sonar_ripple"
        )

        val ringFloatState = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring_float"
        )

        val ringBreathState = infiniteTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ring_breath"
        )

        ripplePhaseProvider = { ripplePhaseState.value }
        ringFloatProvider = { ringFloatState.value }
        ringBreathProvider = { ringBreathState.value }
    }

    // Cycles cleanly between Rapcha's two signature abilities every 4.2s (zero per-frame work).
    var showcaseBeat by remember { mutableIntStateOf(0) }
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        while (isActive) {
            delay(4_200L)
            showcaseBeat = (showcaseBeat + 1) % 2
        }
    }

    val bgLowest = colorScheme.surfaceContainerLowest
    val bgBase = colorScheme.background
    val bgLow = colorScheme.surfaceContainerLow
    val bgContainer = colorScheme.surfaceContainer
    val primaryGlow = colorScheme.primary
    val primaryContainerGlow = colorScheme.primaryContainer
    val onBg = colorScheme.onBackground
    val onBgMuted = colorScheme.onSurfaceVariant
    val rimBorder = colorScheme.outlineVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(bgLowest)
            .testTag("onboarding_hero_welcome_screen")
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val isTablet = screenW >= 600.dp
        val isCompactHeight = screenH < 640.dp

        val headlineSize = when {
            isTablet -> 44.sp
            isCompactHeight -> 30.sp
            else -> 36.sp
        }
        val headlineLineHeight = when {
            isTablet -> 52.sp
            isCompactHeight -> 36.sp
            else -> 43.sp
        }
        val beaconStageSize = when {
            isTablet -> 290.dp
            isCompactHeight -> 196.dp
            else -> 244.dp
        }
        val ringVisualSize = when {
            isTablet -> 118.dp
            isCompactHeight -> 82.dp
            else -> 100.dp
        }
        val horizontalPadding = if (isTablet) 40.dp else 24.dp

        // Full-bleed Dynamic Dark studio canvas with a single cached radial aura behind the beacon
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val studioBg = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to bgLowest,
                            0.38f to bgBase,
                            0.74f to bgLow,
                            1.00f to bgLowest
                        )
                    )
                    val auraRadius = minOf(size.width, size.height) * 0.64f
                    val auraCenter = Offset(size.width * 0.5f, size.height * 0.45f)
                    val dynamicAura = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.28f),
                            primaryContainerGlow.copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        center = auraCenter,
                        radius = auraRadius
                    )
                    val topRimLight = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.85f
                    )
                    onDrawBehind {
                        drawRect(brush = studioBg)
                        drawCircle(
                            brush = topRimLight,
                            center = Offset(size.width * 0.5f, 0f),
                            radius = size.width * 0.85f
                        )
                        drawCircle(
                            brush = dynamicAura,
                            center = auraCenter,
                            radius = auraRadius
                        )
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
            Spacer(modifier = Modifier.height(if (isCompactHeight) 18.dp else 36.dp))

            // Brand Pill Lockup
            Surface(
                shape = RoundedCornerShape(50),
                color = bgContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, rimBorder.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                        contentDescription = stringResource(id = R.string.app_name),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.app_name),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onBg,
                        letterSpacing = 0.2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 14.dp else 22.dp))

            // Editorial Serif Headline
            Text(
                text = "Speak naturally.\nWrite effortlessly.",
                fontFamily = EditorialSerifFontFamily,
                fontSize = headlineSize,
                lineHeight = headlineLineHeight,
                fontWeight = FontWeight.Normal,
                color = onBg,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))

            Text(
                text = "Voice typing & instant replies in any app",
                fontSize = if (isCompactHeight) 14.sp else 15.sp,
                color = onBgMuted,
                textAlign = TextAlign.Center
            )

            // Centerpiece: Lifesaver Acoustic Beacon (concentric sonar ripples + precision ticks + floating ring)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(beaconStageSize)
                        .testTag("hero_lifesaver_beacon"),
                    contentAlignment = Alignment.Center
                ) {
                    // Single cached Canvas pass for concentric acoustic sonar rings + 16 radial voice ticks
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val cx = size.width * 0.5f
                                val cy = size.height * 0.5f
                                val center = Offset(cx, cy)
                                val minR = size.minDimension * 0.22f
                                val maxR = size.minDimension * 0.49f
                                val tickBaseR = size.minDimension * 0.265f
                                val tickCount = 16
                                val cosTable = FloatArray(tickCount) { i ->
                                    cos(i * (2.0 * PI / tickCount)).toFloat()
                                }
                                val sinTable = FloatArray(tickCount) { i ->
                                    sin(i * (2.0 * PI / tickCount)).toFloat()
                                }
                                val ringHaloBrush = Brush.radialGradient(
                                    colors = listOf(
                                        onBg.copy(alpha = 0.14f),
                                        primaryGlow.copy(alpha = 0.24f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = size.minDimension * 0.36f
                                )
                                val rippleStrokePx = 1.6.dp.toPx()
                                val tickStrokePx = 2.2.dp.toPx()
                                val minTickLen = 5.dp.toPx()
                                val maxTickExtra = 10.dp.toPx()

                                onDrawBehind {
                                    // Soft Dynamic Color halo right behind the lifebuoy
                                    drawCircle(
                                        brush = ringHaloBrush,
                                        radius = size.minDimension * 0.36f,
                                        center = center
                                    )

                                    // 3 staggered concentric sonar ripples radiating outward
                                    val phase = ripplePhaseProvider()
                                    for (i in 0 until 3) {
                                        val p = (phase + i / 3f) % 1f
                                        val eased = 1f - (1f - p) * (1f - p)
                                        val radius = minR + (maxR - minR) * eased
                                        val alpha = ((1f - p) * 0.48f).coerceIn(0f, 0.48f)
                                        val rippleColor = if (i % 2 == 0) {
                                            primaryGlow.copy(alpha = alpha)
                                        } else {
                                            onBg.copy(alpha = alpha * 0.85f)
                                        }
                                        drawCircle(
                                            color = rippleColor,
                                            radius = radius,
                                            center = center,
                                            style = Stroke(width = rippleStrokePx)
                                        )
                                    }

                                    // 16 subtle radial acoustic ticks dancing smoothly around the Lifesaver Ring
                                    val waveAngle = ringFloatProvider() * 2f
                                    for (i in 0 until tickCount) {
                                        val wave = 0.5f + 0.5f * sin((waveAngle + i * 0.78f).toDouble()).toFloat()
                                        val len = minTickLen + maxTickExtra * wave
                                        val c = cosTable[i]
                                        val s = sinTable[i]
                                        val start = Offset(cx + tickBaseR * c, cy + tickBaseR * s)
                                        val end = Offset(cx + (tickBaseR + len) * c, cy + (tickBaseR + len) * s)
                                        val tickColor = if (i % 4 == 0) {
                                            primaryGlow.copy(alpha = 0.40f + 0.48f * wave)
                                        } else {
                                            onBg.copy(alpha = 0.22f + 0.45f * wave)
                                        }
                                        drawLine(
                                            color = tickColor,
                                            start = start,
                                            end = end,
                                            strokeWidth = tickStrokePx,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                    )

                    // Floating Lifesaver Ring Emblem (GPU transform only = 0 recompositions)
                    Surface(
                        shape = CircleShape,
                        color = bgContainer,
                        border = BorderStroke(1.5.dp, primaryGlow.copy(alpha = 0.36f)),
                        modifier = Modifier
                            .size(ringVisualSize + 18.dp)
                            .graphicsLayer {
                                val breath = ringBreathProvider()
                                val floatPhase = ringFloatProvider()
                                scaleX = breath
                                scaleY = breath
                                rotationZ = sin(floatPhase.toDouble()).toFloat() * 9f
                                translationY = cos(floatPhase.toDouble()).toFloat() * 4.dp.toPx()
                            }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                contentDescription = "Rapcha Lifesaver Ring",
                                modifier = Modifier.size(ringVisualSize)
                            )
                        }
                    }
                }
            }

            // Dual-Superpower Glass Showcase Card (Voice Smart Polish <-> Hold & Highlight Reply)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = bgContainer.copy(alpha = 0.78f),
                border = BorderStroke(1.dp, rimBorder.copy(alpha = 0.65f)),
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .padding(bottom = if (isCompactHeight) 14.dp else 22.dp)
                    .testTag("hero_feature_showcase_card")
            ) {
                AnimatedContent(
                    targetState = showcaseBeat,
                    transitionSpec = {
                        (fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 6 }) togetherWith
                            (fadeOut(tween(240)) + slideOutVertically(tween(240)) { -it / 6 })
                    },
                    label = "hero_showcase_beat"
                ) { beat ->
                    if (beat == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = if (isCompactHeight) 12.dp else 15.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = primaryGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SMART VOICE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = primaryGlow
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "\"um tell the team we ship after lunch\"",
                                fontSize = 12.5.sp,
                                color = onBgMuted.copy(alpha = 0.72f),
                                textDecoration = TextDecoration.LineThrough,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = primaryGlow,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Tell the team we'll ship right after lunch.",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = onBg,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = if (isCompactHeight) 12.dp else 15.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Reply,
                                    contentDescription = null,
                                    tint = primaryGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "HOLD & HIGHLIGHT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = primaryGlow
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = primaryGlow.copy(alpha = 0.16f),
                                border = BorderStroke(1.2.dp, primaryGlow.copy(alpha = 0.80f))
                            ) {
                                Text(
                                    text = "Are we still on for the 3 PM sync?",
                                    fontSize = 12.5.sp,
                                    color = onBg,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = "→ \"Yes, 3 PM works great — see you then!\"",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onBg,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }

            // Primary "Get started" CTA — luminous Dynamic Color pill bridging cleanly into Page 2
            Button(
                onClick = onGetStarted,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary
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
                    color = colorScheme.onPrimary
                )
            }
        }
    }
}
