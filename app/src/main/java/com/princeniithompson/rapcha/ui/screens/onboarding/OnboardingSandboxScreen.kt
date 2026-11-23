package com.princeniithompson.rapcha.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Interactive Voice Dictation Practice Sandbox:
 * - Uses the exact live Original Bubble (Cancel | blinking red timer | Complete; NO legacy Polish pill).
 * - Uses Rapcha's real Gemini Live engine (`gemini-3.5-transcribe-live`, SMART mode) whenever the
 *   user's API key is ready.
 * - If the background API key fetch is still in flight when the user starts talking, seamlessly
 *   falls back to on-device SpeechRecognizer for this demo run only (and offers Skip) so onboarding
 *   is never blocked.
 */
@Composable
fun OnboardingSandboxScreen(
    @Suppress("UNUSED_PARAMETER") viewModel: VoiceTypingViewModel? = null,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    isApiKeyReady: Boolean = false,
    isActivatingKey: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // 1: Tap text box, 2: Tap bubble, 3: Original bubble expanded (recording/finalizing), 4: Result
    var stepStage by remember { mutableIntStateOf(1) }
    var typedText by remember { mutableStateOf("") }
    var liveBubbleTranscript by remember { mutableStateOf("") }
    var isRecordingActive by remember { mutableStateOf(false) }
    var isFinalizing by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var liveAmplitude by remember { mutableFloatStateOf(0f) }
    var usingGeminiLive by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    // On-device fallback used ONLY if user taps Record before background Gemini key activation finishes
    val fallbackSpeechManager = remember { OnboardingSpeechManager(context) }
    val fallbackListening by fallbackSpeechManager.isListening.collectAsState()
    val fallbackTranscript by fallbackSpeechManager.liveTranscript.collectAsState()
    val fallbackAmplitude by fallbackSpeechManager.amplitude.collectAsState()

    // Real Gemini Live session manager (lazy, deterministically released on pause/dispose)
    var geminiSessionManager by remember { mutableStateOf<FloatingDictationSessionManager?>(null) }

    fun ensureGeminiSessionManager(): FloatingDictationSessionManager {
        return geminiSessionManager ?: FloatingDictationSessionManager(
            context = context.applicationContext,
            onTranscriptUpdated = { fullText ->
                if (usingGeminiLive) {
                    liveBubbleTranscript = fullText
                }
            },
            onInterimReceived = {},
            onFinalSegmentReceived = {},
            onConnectionStateChanged = {},
            onAmplitudeChanged = { amp ->
                if (usingGeminiLive) {
                    liveAmplitude = amp
                }
            },
            onError = { err ->
                if (usingGeminiLive && !isFinalizing) {
                    // Seamless local fallback if network hiccups mid-demo
                    geminiSessionManager?.stopSession(coroutineScope, "sandbox_error_fallback")
                    usingGeminiLive = false
                    statusNotice = if (err.startsWith(FloatingDictationSessionManager.MIC_ERROR_PREFIX)) {
                        "Microphone busy — tap the bubble to try again"
                    } else {
                        "Switched to quick local preview for this demo"
                    }
                    if (!err.startsWith(FloatingDictationSessionManager.MIC_ERROR_PREFIX)) {
                        fallbackSpeechManager.startListening()
                        isRecordingActive = true
                    } else {
                        isRecordingActive = false
                    }
                }
            },
            onDurationTicked = { seconds ->
                if (usingGeminiLive) {
                    elapsedSeconds = seconds
                }
            },
            onSessionLimit = { _, finalizeNow ->
                if (finalizeNow && usingGeminiLive && !isFinalizing) {
                    coroutineScope.launch {
                        isFinalizing = true
                        val text = geminiSessionManager?.finishActivityAndAwaitTranscript().orEmpty().trim()
                        isFinalizing = false
                        isRecordingActive = false
                        if (text.isNotBlank()) {
                            typedText = text
                            liveBubbleTranscript = text
                            stepStage = 4
                        }
                    }
                }
            }
        ).also { geminiSessionManager = it }
    }

    fun stopAllRecorders(reason: String) {
        if (usingGeminiLive) {
            geminiSessionManager?.stopSession(coroutineScope, reason)
            usingGeminiLive = false
        }
        fallbackSpeechManager.stopListening()
        isRecordingActive = false
        isFinalizing = false
        liveAmplitude = 0f
    }

    fun startDictation() {
        statusNotice = null
        liveBubbleTranscript = ""
        elapsedSeconds = 0
        liveAmplitude = 0f
        isFinalizing = false

        val resolvedKey = ApiConfig.resolveApiKey(context)
        val canUseGemini = !ApiConfig.isPlaceholder(resolvedKey) && isNetworkOnline(context)
        if (canUseGemini) {
            usingGeminiLive = true
            isRecordingActive = true
            ApiConfig.prewarmGeminiConnection()
            val mgr = ensureGeminiSessionManager()
            mgr.clearTranscripts()
            mgr.startSession(
                context = context,
                scope = coroutineScope,
                apiKey = resolvedKey,
                model = GeminiLiveWebSocketClient.DEFAULT_MODEL,
                smartMode = true
            )
        } else {
            usingGeminiLive = false
            isRecordingActive = true
            statusNotice = if (isActivatingKey) {
                "Using quick preview while Gemini activates"
            } else {
                null
            }
            fallbackSpeechManager.startListening()
        }
    }

    // Mirror fallback speech manager state into unified UI state when not on Gemini Live
    LaunchedEffect(usingGeminiLive, fallbackTranscript, fallbackAmplitude, fallbackListening) {
        if (!usingGeminiLive && stepStage == 3) {
            if (fallbackTranscript.isNotBlank()) {
                liveBubbleTranscript = fallbackTranscript
            }
            liveAmplitude = fallbackAmplitude
            isRecordingActive = fallbackListening
        }
    }

    // Tick timer when running fallback speech recognizer (Gemini Live ticks via onDurationTicked)
    LaunchedEffect(stepStage, usingGeminiLive, isRecordingActive, isFinalizing) {
        if (stepStage == 3 && !usingGeminiLive && isRecordingActive && !isFinalizing) {
            while (isActive) {
                delay(1_000L)
                elapsedSeconds += 1
            }
        }
    }

    // Shaking animation offset for stage 2 (zero haptics)
    val shakeOffset = remember { Animatable(0f) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && (isRecordingActive || isFinalizing)) {
                stopAllRecorders("sandbox_lifecycle_pause")
                if (stepStage == 3) stepStage = 2
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            geminiSessionManager?.release()
            geminiSessionManager = null
            fallbackSpeechManager.stopListening()
        }
    }

    LaunchedEffect(stepStage) {
        when (stepStage) {
            2 -> {
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 800
                            0f at 0 using LinearEasing
                            (-5f) at 100
                            5f at 200
                            (-4f) at 300
                            4f at 400
                            0f at 500
                            0f at 800
                        },
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
            4 -> {
                shakeOffset.snapTo(0f)
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            else -> {
                shakeOffset.snapTo(0f)
            }
        }
    }

    // Permission launcher for real audio
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            stepStage = 3
            startDictation()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .testTag("onboarding_sandbox_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Upper Section: Chat Input Box and Step Guidance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Real Chat Input Box (triggers user's native keyboard)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFFECE6F0))
                        .clickable {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            if (stepStage == 1) {
                                stepStage = 2
                            }
                        }
                        .testTag("sandbox_native_input")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SentimentSatisfied,
                            contentDescription = "Emoji",
                            tint = OnboardingMutedText,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = typedText,
                                onValueChange = { typedText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused && stepStage == 1) {
                                            stepStage = 2
                                        }
                                    },
                                maxLines = 3,
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    color = OnboardingInkBlack,
                                    fontFamily = FontFamily.Default
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (typedText.isEmpty() && stepStage == 1) {
                                        Text(
                                            text = "Tap to type...",
                                            fontSize = 16.sp,
                                            color = OnboardingMutedText
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = OnboardingMutedText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Subtle engine status indicator if key is still activating in background
                if (!isApiKeyReady && isActivatingKey && stepStage in 1..2) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .testTag("sandbox_key_activating_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connecting your Gemini voice key...",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Prompt Text depending on Stage
                when (stepStage) {
                    1 -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                                    append("Tap")
                                }
                                append(" the text box above")
                            },
                            fontSize = 17.sp,
                            color = OnboardingInkBlack,
                            textAlign = TextAlign.Center
                        )
                    }
                    2 -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                                    append("Tap")
                                }
                                append(" the Lifesaver bubble to speak")
                            },
                            fontSize = 17.sp,
                            color = OnboardingInkBlack,
                            textAlign = TextAlign.Center
                        )
                    }
                    3 -> {
                        Text(
                            text = if (isFinalizing) "Polishing with Gemini..." else "Say something, then tap Complete!",
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnboardingInkBlack,
                            textAlign = TextAlign.Center
                        )
                        val activeNotice = statusNotice
                        if (!activeNotice.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = activeNotice,
                                fontSize = 13.sp,
                                color = OnboardingMutedText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    4 -> {
                        Text(
                            text = "Nice, that worked!",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnboardingInkBlack
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                stopAllRecorders("sandbox_continue")
                                onComplete()
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("btn_sandbox_continue")
                        ) {
                            Text(
                                text = "Continue",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (stepStage < 4) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            stopAllRecorders("sandbox_skip")
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onComplete()
                        },
                        modifier = Modifier.testTag("btn_sandbox_skip")
                    ) {
                        Text(
                            text = "Skip practice",
                            fontSize = 14.sp,
                            color = OnboardingMutedText
                        )
                    }
                }
            }

            // Lower Section: Exact Live Original Bubble docked right above native keyboard
            if (stepStage == 2 || stepStage == 3) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    when (stepStage) {
                        2 -> {
                            // Shaking Lifesaver Bubble docked above keyboard
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = shakeOffset.value.dp)
                                        .size(52.dp)
                                        .clickable {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasPermission) {
                                                stepStage = 3
                                                startDictation()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                        .testTag("onboarding_docked_bubble"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                        contentDescription = "Lifesaver Bubble",
                                        modifier = Modifier.size(46.dp)
                                    )
                                }
                            }
                        }

                        3 -> {
                            // Exact Live Original Bubble: Cancel | red blinking dot + 00:00 timer | Complete (NO Polish button)
                            val sandboxDensity = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(sandboxDensity.density, fontScale = 1f)
                            ) {
                                FloatingDictationPopup(
                                    transcriptText = liveBubbleTranscript,
                                    isRecording = isRecordingActive,
                                    isPendingFinalizing = isFinalizing,
                                    isPolishing = false,
                                    elapsedSeconds = elapsedSeconds,
                                    audioAmplitude = liveAmplitude,
                                    showPolish = false,
                                    onCancelClick = {
                                        stopAllRecorders("sandbox_cancel")
                                        liveBubbleTranscript = ""
                                        stepStage = 2
                                    },
                                    onPolishClick = {},
                                    onCompleteClick = {
                                        if (isFinalizing) return@FloatingDictationPopup
                                        if (usingGeminiLive) {
                                            coroutineScope.launch {
                                                isFinalizing = true
                                                isRecordingActive = false
                                                val result = geminiSessionManager
                                                    ?.finishActivityAndAwaitTranscript()
                                                    .orEmpty()
                                                    .trim()
                                                usingGeminiLive = false
                                                isFinalizing = false
                                                val finalText = result.ifBlank { liveBubbleTranscript.trim() }
                                                if (finalText.isNotBlank()) {
                                                    typedText = finalText
                                                    liveBubbleTranscript = finalText
                                                    stepStage = 4
                                                } else {
                                                    statusNotice = "Didn't catch any speech — tap the bubble to try again"
                                                    stepStage = 2
                                                }
                                            }
                                        } else {
                                            fallbackSpeechManager.stopListening()
                                            isRecordingActive = false
                                            val raw = liveBubbleTranscript.ifBlank { fallbackTranscript }.trim()
                                            val tidied = SandboxPolish.tidy(raw)
                                            if (tidied.isNotBlank()) {
                                                typedText = tidied
                                                liveBubbleTranscript = tidied
                                                stepStage = 4
                                            } else {
                                                statusNotice = "Didn't catch any speech — tap the bubble to try again"
                                                stepStage = 2
                                            }
                                        }
                                    },
                                    onLifebuoyClick = {
                                        if (isFinalizing) return@FloatingDictationPopup
                                        if (isRecordingActive) {
                                            stopAllRecorders("sandbox_toggle_stop")
                                            stepStage = 2
                                        } else {
                                            startDictation()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_floating_container")
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/** Local tidy helper used only when on-device fallback runs before Gemini key activation finishes. */
internal object SandboxPolish {
    fun tidy(text: String): String {
        val cleaned = text.trim().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString()
        }
        if (cleaned.isEmpty()) return cleaned
        return if (cleaned.endsWith(".") || cleaned.endsWith("?") || cleaned.endsWith("!")) cleaned else "$cleaned."
    }
}
