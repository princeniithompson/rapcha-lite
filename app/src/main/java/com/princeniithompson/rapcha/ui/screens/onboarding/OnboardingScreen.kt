package com.princeniithompson.rapcha.ui.screens.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.data.OnboardingRepository
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.screens.KeepAwakeScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningStatus
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText

internal fun isNetworkOnline(context: Context): Boolean {
    return runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}

/**
 * Main Onboarding Coordinator:
 * 0. NO_INTERNET gate: Clear signal-tower + exclamation illustration at the very start when offline.
 * 1. WELCOME_HERO: Crash-free animated "Rapcha / 4x faster than typing" screen.
 * 2. GOOGLE_SIGN_IN: Clean Google-only sign-in gate ("Continue with Google").
 * 3. Embedded Google Account picker -> GOOGLE_AUTH_HANDOFF ("Log in as <email> / Open Rapcha").
 * 4. Existing Rapcha editorial onboarding tour while AiStudioAutomationEngine silently
 *    provisions and encrypts the user's personal Gemini API key behind the scenes (alpha = 0f).
 */
@Composable
fun OnboardingScreen(
    viewModel: VoiceTypingViewModel,
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    sharedAutomationEngine: AiStudioAutomationEngine? = null
) {
    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(isNetworkOnline(context)) }
    var stage by remember {
        OnboardingRepository.init(context)
        val savedName = OnboardingRepository.getSavedStageName()
        val restored = OnboardingStage.entries.firstOrNull { it.name == savedName }
            ?: OnboardingStage.WELCOME_HERO
        mutableStateOf(restored)
    }

    LaunchedEffect(stage) {
        OnboardingRepository.setSavedStageName(stage.name)
    }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
            }

            override fun onLost(network: Network) {
                isOnline = isNetworkOnline(context)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    val ownsEngine = sharedAutomationEngine == null
    val automationEngine = remember(sharedAutomationEngine) {
        sharedAutomationEngine ?: AiStudioAutomationEngine(context)
    }
    val provisioningState by automationEngine.state.collectAsState()
    val activeWebView by automationEngine.activeWebView.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val savedEmail by viewModel.signedInGoogleEmail.collectAsState()
    val isApiKeyVerified by viewModel.isApiKeyVerified.collectAsState()
    val apiKeyErrorReason by viewModel.apiKeyErrorReason.collectAsState()
    val hasVerifiedWorkingKey = viewModel.hasProvisionedPersonalKey() &&
        isApiKeyVerified &&
        apiKeyErrorReason.isBlank()

    // Auto-resume key provisioning or verification after process death (e.g., 2FA switch on low-RAM phone)
    LaunchedEffect(isOnline, hasVerifiedWorkingKey) {
        if (isOnline && !hasVerifiedWorkingKey) {
            if (viewModel.hasProvisionedPersonalKey() && !isApiKeyVerified && apiKeyErrorReason.isBlank()) {
                viewModel.verifyCurrentApiKeyIfNeeded()
            } else {
                automationEngine.resumeSilentProvisioningIfPossible()
            }
        }
    }

    LaunchedEffect(hasVerifiedWorkingKey) {
        if (hasVerifiedWorkingKey && !provisioningState.showBrowserToUser) {
            automationEngine.tearDownWebView()
        }
    }

    DisposableEffect(lifecycleOwner, ownsEngine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isOnline = isNetworkOnline(context)
                    viewModel.refreshPermissions()
                    viewModel.tryAutoInjectFromClipboard()
                    if (ownsEngine && !hasVerifiedWorkingKey) {
                        automationEngine.onHostResumed()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (ownsEngine) {
                        automationEngine.onHostPaused()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ownsEngine) {
                automationEngine.tearDownWebView()
            }
        }
    }

    // Move automatically from Sign-In browser to "Log in as <email> / Open Rapcha" once Google redirects to AI Studio
    LaunchedEffect(provisioningState.status) {
        if (provisioningState.status == ProvisioningStatus.SIGNED_IN_HANDOFF &&
            stage == OnboardingStage.GOOGLE_SIGN_IN
        ) {
            stage = OnboardingStage.GOOGLE_AUTH_HANDOFF
        }
    }

    BackHandler(
        enabled = provisioningState.showBrowserToUser || stage != OnboardingStage.WELCOME_HERO
    ) {
        when {
            provisioningState.showBrowserToUser -> {
                val wv = activeWebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    automationEngine.dismissVisibleBrowser()
                }
            }
            stage == OnboardingStage.GOOGLE_SIGN_IN -> stage = OnboardingStage.WELCOME_HERO
            stage == OnboardingStage.GOOGLE_AUTH_HANDOFF -> stage = OnboardingStage.GOOGLE_SIGN_IN
            stage == OnboardingStage.INTRO -> stage = OnboardingStage.WELCOME_HERO
            stage == OnboardingStage.HOW_IT_WORKS -> stage = OnboardingStage.INTRO
            stage == OnboardingStage.SANDBOX -> stage = OnboardingStage.HOW_IT_WORKS
            stage == OnboardingStage.APPS_SHOWCASE -> stage = OnboardingStage.SANDBOX
            stage == OnboardingStage.OVERLAY_PERMISSION -> stage = OnboardingStage.APPS_SHOWCASE
            stage == OnboardingStage.KEEP_AWAKE -> stage = OnboardingStage.OVERLAY_PERMISSION
            stage == OnboardingStage.ACCESSIBILITY_GUIDE -> stage = OnboardingStage.KEEP_AWAKE
            stage == OnboardingStage.NOTIFICATION_PERMISSION ||
            stage == OnboardingStage.ALL_SET -> stage = OnboardingStage.ACCESSIBILITY_GUIDE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_onboarding")
    ) {
        if (!isOnline) {
            OnboardingNoInternetScreen(
                onRetry = {
                    isOnline = isNetworkOnline(context)
                    if (isOnline && !hasVerifiedWorkingKey) {
                        automationEngine.resumeSilentProvisioningIfPossible()
                    }
                }
            )
            return@Box
        }

        // Live full-viewport WebView kept laid out underneath foreground onboarding (alpha = 0f when hidden)
        // only until onboarding completes AND the API key is confirmed working.
        if (activeWebView != null && (!hasVerifiedWorkingKey || provisioningState.showBrowserToUser)) {
            EmbeddedGoogleAuthBrowserContainer(
                webView = activeWebView,
                visible = provisioningState.showBrowserToUser,
                pageTitle = provisioningState.pageTitle,
                currentUrl = provisioningState.currentUrl,
                isLoading = provisioningState.isPageLoading,
                progress = provisioningState.loadProgress,
                onClose = { automationEngine.dismissVisibleBrowser() },
                onRefresh = { automationEngine.reloadCurrentPage() }
            )
        }

        // Foreground Active Onboarding Screen (opaque layer above hidden WebView)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
        ) {
            when (stage) {
                OnboardingStage.WELCOME_HERO -> {
                    OnboardingHeroWelcomeScreen(
                        onGetStarted = {
                            stage = if (viewModel.hasProvisionedPersonalKey() && apiKeyErrorReason.isBlank()) {
                                OnboardingStage.INTRO
                            } else {
                                OnboardingStage.GOOGLE_SIGN_IN
                            }
                        }
                    )
                }

                OnboardingStage.GOOGLE_SIGN_IN -> {
                    OnboardingGoogleSignInScreen(
                        onContinueWithGoogle = {
                            automationEngine.createOrReuseWebView(forceRecovery = true)
                            automationEngine.startGoogleSignIn()
                        }
                    )
                }

                OnboardingStage.GOOGLE_AUTH_HANDOFF -> {
                    OnboardingGoogleHandoffScreen(
                        email = provisioningState.detectedEmail.ifBlank { savedEmail },
                        onOpenRapcha = {
                            automationEngine.onUserTappedOpenRapcha()
                            stage = OnboardingStage.INTRO
                        },
                        onUseAnotherAccount = {
                            automationEngine.createOrReuseWebView(forceRecovery = true)
                            automationEngine.switchGoogleAccount()
                            stage = OnboardingStage.GOOGLE_SIGN_IN
                        }
                    )
                }

                OnboardingStage.INTRO,
                OnboardingStage.HOW_IT_WORKS,
                OnboardingStage.SANDBOX,
                OnboardingStage.APPS_SHOWCASE,
                OnboardingStage.OVERLAY_PERMISSION,
                OnboardingStage.KEEP_AWAKE,
                OnboardingStage.ACCESSIBILITY_GUIDE,
                OnboardingStage.NOTIFICATION_PERMISSION,
                OnboardingStage.ALL_SET -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OnboardingCreamBackground)
                            .safeDrawingPadding(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 620.dp)
                                .fillMaxSize()
                        ) {
                            OnboardingTopBar(
                                currentSegment = stage.progressIndex,
                                totalSegments = 8
                            )

                            when (stage) {
                                OnboardingStage.INTRO -> {
                                    OnboardingIntroScreen(
                                        onNext = { stage = OnboardingStage.HOW_IT_WORKS }
                                    )
                                }

                                OnboardingStage.HOW_IT_WORKS -> {
                                    OnboardingHowItWorksScreen(
                                        onNext = { stage = OnboardingStage.SANDBOX }
                                    )
                                }

                                OnboardingStage.SANDBOX -> {
                                    OnboardingSandboxScreen(
                                        viewModel = viewModel,
                                        onComplete = { stage = OnboardingStage.APPS_SHOWCASE }
                                    )
                                }

                                OnboardingStage.APPS_SHOWCASE -> {
                                    OnboardingAppsShowcaseScreen(
                                        onNext = { stage = OnboardingStage.OVERLAY_PERMISSION }
                                    )
                                }

                                OnboardingStage.OVERLAY_PERMISSION -> {
                                    OnboardingOverlayPermissionScreen(
                                        canDrawOverlays = hasOverlayPermission,
                                        onNext = { stage = OnboardingStage.KEEP_AWAKE }
                                    )
                                }

                                OnboardingStage.KEEP_AWAKE -> {
                                    KeepAwakeScreen(
                                        isOnboarding = true,
                                        onContinue = { stage = OnboardingStage.ACCESSIBILITY_GUIDE }
                                    )
                                }

                                OnboardingStage.ACCESSIBILITY_GUIDE -> {
                                    OnboardingAccessibilityGuideScreen(
                                        isAccessibilityConnected = isAccessibilityConnected,
                                        onNext = { stage = OnboardingStage.NOTIFICATION_PERMISSION }
                                    )
                                }

                                OnboardingStage.NOTIFICATION_PERMISSION,
                                OnboardingStage.ALL_SET -> {
                                    val isActivatingKey =
                                        provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                                            provisioningState.status == ProvisioningStatus.SIGNING_IN ||
                                            (viewModel.hasProvisionedPersonalKey() && !isApiKeyVerified && apiKeyErrorReason.isBlank())
                                    OnboardingNotificationScreen(
                                        isApiKeyReady = hasVerifiedWorkingKey,
                                        isActivatingKey = isActivatingKey,
                                        statusMessage = apiKeyErrorReason.ifBlank { provisioningState.statusDetail },
                                        onRetryKeySetup = {
                                            if (viewModel.hasProvisionedPersonalKey() && !isApiKeyVerified) {
                                                viewModel.verifyCurrentApiKeyIfNeeded()
                                            } else if (!automationEngine.resumeSilentProvisioningIfPossible()) {
                                                automationEngine.createOrReuseWebView(forceRecovery = true)
                                                automationEngine.startGoogleSignIn(savedEmail)
                                            }
                                        },
                                        onFinish = {
                                            viewModel.completeOnboarding()
                                            if (hasOverlayPermission) {
                                                viewModel.setBubbleEnabled(true)
                                            }
                                            onFinishOnboarding()
                                        }
                                    )
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnboardingNoInternetScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .testTag("onboarding_no_internet_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Canvas(
                modifier = Modifier
                    .size(260.dp)
                    .testTag("illustration_no_internet_tower")
            ) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f

                // Soft circular backdrop
                drawCircle(
                    color = Color(0xFFECEFF3),
                    radius = w * 0.42f,
                    center = Offset(cx, h * 0.48f)
                )

                // Soft clouds
                val cloudColor = Color(0xFFDCE1E7)
                drawRoundRect(
                    color = cloudColor,
                    topLeft = Offset(w * 0.10f, h * 0.43f),
                    size = Size(w * 0.28f, h * 0.055f),
                    cornerRadius = CornerRadius(h * 0.03f, h * 0.03f)
                )
                drawRoundRect(
                    color = cloudColor,
                    topLeft = Offset(w * 0.58f, h * 0.34f),
                    size = Size(w * 0.25f, h * 0.05f),
                    cornerRadius = CornerRadius(h * 0.025f, h * 0.025f)
                )
                drawRoundRect(
                    color = cloudColor,
                    topLeft = Offset(w * 0.62f, h * 0.53f),
                    size = Size(w * 0.28f, h * 0.055f),
                    cornerRadius = CornerRadius(h * 0.03f, h * 0.03f)
                )

                // Concentric Wi-Fi signal arcs above tower
                val arcCenter = Offset(cx, h * 0.36f)
                val arcRadii = listOf(w * 0.10f, w * 0.16f, w * 0.22f)
                val arcColors = listOf(
                    Color(0xFFD6DADF),
                    Color(0xFFC2C8D0),
                    Color(0xFFAEB6C0)
                )
                arcRadii.forEachIndexed { idx, r ->
                    drawArc(
                        color = arcColors[idx],
                        startAngle = 215f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
                    )
                }

                // White halo + bold red exclamation mark (!) centered over signal arcs
                val bangTop = h * 0.145f
                val bangBarHeight = h * 0.125f
                val bangWidth = w * 0.046f
                drawRoundRect(
                    color = Color(0xFFECEFF3),
                    topLeft = Offset(cx - bangWidth * 0.85f, bangTop - bangWidth * 0.3f),
                    size = Size(bangWidth * 1.7f, bangBarHeight + bangWidth * 0.6f),
                    cornerRadius = CornerRadius(bangWidth, bangWidth)
                )
                drawRoundRect(
                    color = Color(0xFFE53935),
                    topLeft = Offset(cx - bangWidth * 0.5f, bangTop),
                    size = Size(bangWidth, bangBarHeight),
                    cornerRadius = CornerRadius(bangWidth * 0.5f, bangWidth * 0.5f)
                )
                drawCircle(
                    color = Color(0xFFE53935),
                    radius = bangWidth * 0.56f,
                    center = Offset(cx, bangTop + bangBarHeight + bangWidth * 0.9f)
                )

                // Lattice Radio / Signal Tower
                val towerTopY = h * 0.405f
                val towerBottomY = h * 0.88f
                val towerHalfSpread = w * 0.23f
                val towerColor = Color(0xFF9EA6B0)
                val strokeW = w * 0.022f

                // Left & Right legs
                drawLine(
                    color = towerColor,
                    start = Offset(cx, towerTopY),
                    end = Offset(cx - towerHalfSpread, towerBottomY),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = towerColor,
                    start = Offset(cx, towerTopY),
                    end = Offset(cx + towerHalfSpread, towerBottomY),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )

                // Horizontal rungs & X-bracing
                val fractions = listOf(0.20f, 0.44f, 0.70f, 0.88f)
                for (i in fractions.indices) {
                    val f = fractions[i]
                    val y = towerTopY + (towerBottomY - towerTopY) * f
                    val halfW = towerHalfSpread * f
                    drawLine(
                        color = towerColor,
                        start = Offset(cx - halfW, y),
                        end = Offset(cx + halfW, y),
                        strokeWidth = strokeW * 0.8f,
                        cap = StrokeCap.Round
                    )
                    if (i > 0) {
                        val prevF = fractions[i - 1]
                        val prevY = towerTopY + (towerBottomY - towerTopY) * prevF
                        val prevHalfW = towerHalfSpread * prevF
                        drawLine(
                            color = towerColor,
                            start = Offset(cx - prevHalfW, prevY),
                            end = Offset(cx + halfW, y),
                            strokeWidth = strokeW * 0.75f,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = towerColor,
                            start = Offset(cx + prevHalfW, prevY),
                            end = Offset(cx - halfW, y),
                            strokeWidth = strokeW * 0.75f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Tower transmitter node at apex
                drawCircle(
                    color = Color(0xFF6E7681),
                    radius = w * 0.028f,
                    center = Offset(cx, towerTopY)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "No internet connection",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 32.sp,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Turn on Wi-Fi or mobile data to set up Rapcha.",
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = OnboardingMutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.5.dp, OnboardingBorderStroke),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .testTag("btn_no_internet_retry")
        ) {
            Text(
                text = "Try again",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
