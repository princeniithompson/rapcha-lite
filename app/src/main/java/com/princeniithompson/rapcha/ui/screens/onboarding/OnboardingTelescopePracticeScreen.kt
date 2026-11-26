package com.princeniithompson.rapcha.ui.screens.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import com.princeniithompson.rapcha.ui.components.RapchaIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.FloatingTextActionsClient
import com.princeniithompson.rapcha.data.ModuleItem
import com.princeniithompson.rapcha.data.ReplyLength
import com.princeniithompson.rapcha.data.TargetLanguageItem
import com.princeniithompson.rapcha.data.TextActionRequest
import com.princeniithompson.rapcha.data.TextActionResult
import com.princeniithompson.rapcha.data.ToneItem
import com.princeniithompson.rapcha.data.ToneRepository
import com.princeniithompson.rapcha.service.floating.BubbleChromeGeometry
import com.princeniithompson.rapcha.service.floating.TelescopeActionUiState
import com.princeniithompson.rapcha.service.floating.TelescopeExecutionPolicy
import com.princeniithompson.rapcha.service.floating.TelescopeMagnifierGeometry
import com.princeniithompson.rapcha.service.floating.TelescopeWorkspaceContent
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText
import com.princeniithompson.rapcha.ui.theme.getAppDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Interactive Hold & Highlight (Telescope) Onboarding Practice Screen — 1:1 Live Experience:
 *
 * 1. On open, automatically runs a smooth 3-cycle guided demo showing the Lifesaver bubble
 *    morphing into the exact Dynamic Color Magnifying Glass ("Telescope"), gliding over the
 *    message, lighting up the exact 10dp Dynamic Color highlight box, and returning to its dock.
 * 2. When the user holds and drags the bubble (tracked on a fixed non-translated stage layer so
 *    there is zero coordinate jump/glitch), it morphs into the exact live Magnifying Glass
 *    positioned diagonally above the user's finger (`TelescopeMagnifierGeometry`).
 * 3. Releasing over the message locks the 10dp highlight box and renders the **exact production
 *    `TelescopeWorkspaceContent`** (dark Dynamic Color Action Bar with Grammar / Translate /
 *    Reply / Tone pills, parameter chips above the bar, and the exact `TelescopeResultCard` with
 *    "Generating — Please wait, your text is coming", Copy / Retry, and pill "Insert" button).
 * 4. Calls the real `FloatingTextActionsClient` (`gemini-3.5-flash-lite`) when the API key is
 *    ready, with an instant realistic fallback if still activating.
 *
 * Zero Modifier.blur(), zero haptics, GPU-only transforms -> 60fps on Infinix Hot 11.
 */
@Composable
fun OnboardingTelescopePracticeScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    isApiKeyReady: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ToneRepository.init(context)
    }

    val allModules by ToneRepository.modules.collectAsState()
    val allLanguages by ToneRepository.languages.collectAsState()
    val allTones by ToneRepository.tones.collectAsState()

    val enabledModules = remember(allModules) {
        val active = allModules.filter { it.enabled }
        active.ifEmpty { defaultPracticeModules() }
    }
    val enabledLanguages = remember(allLanguages) {
        val active = allLanguages.filter { it.enabled }
        active.ifEmpty { defaultPracticeLanguages() }
    }
    val enabledTones = remember(allTones) {
        val active = allTones.filter { it.enabled }
        active.ifEmpty { defaultPracticeTones() }
    }

    // Exact Dynamic Color palette matching IdleBubbleNativeView & TelescopeHighlightView
    val telescopePalette = remember(context) { resolveLiveTelescopePalette(context) }

    val sampleIncomingMessage =
        "Hey! Are we still on for the 3 PM meeting today, or should we move it to tomorrow morning?"

    // Fixed stage-local geometry (prevents any translation coordinate feedback loop / glitch)
    var stageBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var messageBoundsInRoot by remember { mutableStateOf(Rect.Zero) }

    val messageRectInStage = remember(stageBoundsInRoot, messageBoundsInRoot) {
        if (stageBoundsInRoot == Rect.Zero || messageBoundsInRoot == Rect.Zero) {
            Rect.Zero
        } else {
            Rect(
                left = messageBoundsInRoot.left - stageBoundsInRoot.left,
                top = messageBoundsInRoot.top - stageBoundsInRoot.top,
                right = messageBoundsInRoot.right - stageBoundsInRoot.left,
                bottom = messageBoundsInRoot.bottom - stageBoundsInRoot.top
            )
        }
    }

    val pointerWindowSizeDp = 72.dp
    val pointerWindowSizePx = with(density) { pointerWindowSizeDp.toPx() }
    val halfPointerPx = pointerWindowSizePx / 2f
    val dockEndMarginPx = with(density) { 10.dp.toPx() }
    val dockBottomMarginPx = with(density) { 54.dp.toPx() }
    val fingerOffsetPx = with(density) { TelescopeMagnifierGeometry.FINGER_OFFSET_FROM_CENTER_DP.dp.toPx() }
    val lensShiftPx = with(density) { TelescopeMagnifierGeometry.LENS_CENTER_SHIFT_DP.dp.toPx() }
    val hitRadiusPx = with(density) { 44.dp.toPx() }
    val targetInflatePx = with(density) { 24.dp.toPx() }

    val dockCenter = remember(stageSize, halfPointerPx, dockEndMarginPx, dockBottomMarginPx) {
        if (stageSize.width == 0 || stageSize.height == 0) {
            Offset.Zero
        } else {
            Offset(
                x = (stageSize.width - dockEndMarginPx - halfPointerPx).coerceAtLeast(halfPointerPx),
                y = (stageSize.height - dockBottomMarginPx - halfPointerPx).coerceAtLeast(halfPointerPx)
            )
        }
    }

    // Drag, guided-demo, and Telescope workspace state
    var hasUserInteracted by remember { mutableStateOf(false) }
    var isDraggingBubble by remember { mutableStateOf(false) }
    var userDragCenter by remember { mutableStateOf(Offset.Zero) }
    var isHoveringMessage by remember { mutableStateOf(false) }
    var isMessageLocked by remember { mutableStateOf(false) }

    // 3-cycle guided demo state
    var isDemoMagnifierActive by remember { mutableStateOf(false) }
    var isDemoHighlightActive by remember { mutableStateOf(false) }
    val demoProgress = remember { Animatable(0f) }

    // Live TelescopeWorkspaceContent state
    var workspaceState by remember {
        mutableStateOf(TelescopeActionUiState(sourceText = sampleIncomingMessage))
    }
    var lastRequest by remember { mutableStateOf<TextActionRequest?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    var insertedReply by remember { mutableStateOf<String?>(null) }
    var practiceDone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            activeJob = null
        }
    }

    // 3-cycle automatic guided demo animation:
    // Lifesaver ring lifts -> morphs into Magnifying Glass -> glides over message ->
    // highlights message -> returns to dock (3x, then rests). Stops instantly on user touch.
    val stageReady = dockCenter != Offset.Zero && messageRectInStage != Rect.Zero
    LaunchedEffect(stageReady, hasUserInteracted, isMessageLocked, practiceDone) {
        if (!stageReady || hasUserInteracted || isMessageLocked || practiceDone) {
            isDemoMagnifierActive = false
            isDemoHighlightActive = false
            demoProgress.snapTo(0f)
            return@LaunchedEffect
        }
        delay(650L)
        repeat(3) {
            isDemoMagnifierActive = true
            demoProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing)
            )
            isDemoHighlightActive = true
            delay(760L)
            isDemoHighlightActive = false
            demoProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 640, easing = FastOutSlowInEasing)
            )
            isDemoMagnifierActive = false
            delay(520L)
        }
    }

    fun executeAction(request: TextActionRequest, title: String) {
        activeJob?.cancel()
        lastRequest = request
        workspaceState = workspaceState.copy(
            cardTitle = title,
            isLoading = true,
            resultText = null,
            errorMessage = null
        )
        activeJob = coroutineScope.launch {
            val resolvedKey = ApiConfig.resolveApiKey(context)
            val fallbackText = buildFallbackTextFor(request)
            if (isApiKeyReady && resolvedKey.isNotBlank()) {
                val res = FloatingTextActionsClient.executeStreaming(
                    context = context,
                    request = request,
                    onPartialText = { partial ->
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            if (lastRequest === request && workspaceState.isLoading) {
                                workspaceState = workspaceState.copy(
                                    resultText = partial,
                                    errorMessage = null
                                )
                            }
                        }
                    }
                )
                when (res) {
                    is TextActionResult.Success -> {
                        workspaceState = workspaceState.copy(
                            isLoading = false,
                            resultText = res.text.trim().ifBlank { fallbackText },
                            errorMessage = null
                        )
                    }
                    is TextActionResult.Error -> {
                        // Graceful streaming fallback if network/key hiccups during onboarding
                        val words = fallbackText.split(" ")
                        val built = StringBuilder()
                        for (word in words) {
                            if (built.isNotEmpty()) built.append(' ')
                            built.append(word)
                            workspaceState = workspaceState.copy(
                                isLoading = true,
                                resultText = built.toString(),
                                errorMessage = null
                            )
                            delay(24L)
                        }
                        workspaceState = workspaceState.copy(
                            isLoading = false,
                            resultText = fallbackText,
                            errorMessage = null
                        )
                    }
                }
            } else {
                // Show animated sparkle star briefly, then stream words live just like Gemini SSE
                delay(380L)
                val words = fallbackText.split(" ")
                val built = StringBuilder()
                for (word in words) {
                    if (built.isNotEmpty()) built.append(' ')
                    built.append(word)
                    workspaceState = workspaceState.copy(
                        isLoading = true,
                        resultText = built.toString(),
                        errorMessage = null
                    )
                    delay(26L)
                }
                workspaceState = workspaceState.copy(
                    isLoading = false,
                    resultText = fallbackText,
                    errorMessage = null
                )
            }
        }
    }

    fun handleModuleSelected(module: ModuleItem) {
        val immediate = TelescopeExecutionPolicy.executesImmediatelyOnTap(module.id)
        if (!immediate) {
            activeJob?.cancel()
            workspaceState = workspaceState.copy(
                activeModuleId = module.id,
                selectedLanguageId = null,
                selectedReplyLength = null,
                selectedToneId = null,
                cardTitle = module.name,
                isLoading = false,
                resultText = null,
                errorMessage = null
            )
            return
        }
        workspaceState = workspaceState.copy(
            activeModuleId = module.id,
            selectedLanguageId = null,
            selectedReplyLength = null,
            selectedToneId = null
        )
        val request = when (module.id) {
            ToneRepository.MODULE_GRAMMAR -> TextActionRequest.Grammar(sampleIncomingMessage)
            else -> TextActionRequest.CustomModule(
                text = sampleIncomingMessage,
                moduleName = module.name,
                query = module.query
            )
        }
        executeAction(request, module.name)
    }

    fun handleLanguageSelected(language: TargetLanguageItem) {
        workspaceState = workspaceState.copy(
            activeModuleId = ToneRepository.MODULE_TRANSLATE,
            selectedLanguageId = language.id
        )
        executeAction(
            request = TextActionRequest.Translate(sampleIncomingMessage, language.name),
            title = "Translate · ${language.name}"
        )
    }

    fun handleReplyLengthSelected(length: ReplyLength) {
        workspaceState = workspaceState.copy(
            activeModuleId = ToneRepository.MODULE_REPLY,
            selectedReplyLength = length
        )
        executeAction(
            request = TextActionRequest.Reply(sampleIncomingMessage, length),
            title = "Reply · ${length.displayName}"
        )
    }

    fun handleToneSelected(tone: ToneItem) {
        workspaceState = workspaceState.copy(
            activeModuleId = ToneRepository.MODULE_TONE,
            selectedToneId = tone.id
        )
        executeAction(
            request = TextActionRequest.Tone(sampleIncomingMessage, tone.name, tone.instruction),
            title = "Tone · ${tone.name}"
        )
    }

    fun resetWorkspaceLock() {
        activeJob?.cancel()
        isMessageLocked = false
        workspaceState = TelescopeActionUiState(sourceText = sampleIncomingMessage)
    }

    val isHighlightVisible = isHoveringMessage || isMessageLocked || isDemoHighlightActive
    val showMagnifierShape = isDraggingBubble || isDemoMagnifierActive

    // Compute live pointer center (user drag takes immediate priority over demo glide)
    val demoTargetCenter = remember(messageRectInStage, lensShiftPx) {
        if (messageRectInStage == Rect.Zero) {
            Offset.Zero
        } else {
            Offset(
                x = messageRectInStage.center.x - lensShiftPx,
                y = messageRectInStage.center.y - lensShiftPx
            )
        }
    }

    val activePointerCenter: Offset = when {
        isDraggingBubble -> userDragCenter
        isDemoMagnifierActive && stageReady -> Offset(
            x = dockCenter.x + (demoTargetCenter.x - dockCenter.x) * demoProgress.value,
            y = dockCenter.y + (demoTargetCenter.y - dockCenter.y) * demoProgress.value
        )
        else -> dockCenter
    }

    // Updated lambdas/values for stable pointerInput without restarting mid-gesture
    val latestDockCenter by rememberUpdatedState(dockCenter)
    val latestStageSize by rememberUpdatedState(stageSize)
    val latestMessageRect by rememberUpdatedState(messageRectInStage)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .testTag("onboarding_telescope_practice_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Headline
            Text(
                text = "Hold & highlight\nany message",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 29.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            val instruction = when {
                practiceDone -> "Nice! You mastered Hold & Highlight."
                workspaceState.isLoading -> "Generating your response..."
                workspaceState.resultText != null -> "Tap Insert to drop it right into the chat"
                isMessageLocked -> "Pick Grammar, Translate, Reply, or Tone above"
                isHoveringMessage -> "Release now to open Smart Actions!"
                else -> "Drag the Lifesaver bubble onto Alex's message"
            }
            // Fixed 2-line height prevents vertical stage shifts when instruction text changes mid-drag
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = instruction,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    maxLines = 2,
                    fontWeight = if (isHoveringMessage || practiceDone) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isHoveringMessage || practiceDone) MaterialTheme.colorScheme.primary else OnboardingMutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("telescope_practice_instruction")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Chat Stage (fixed coordinate frame -> zero drag jitter)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        stageBoundsInRoot = coords.boundsInRoot()
                        stageSize = coords.size
                    }
            ) {
                // 1. Mini Chat Card
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, OnboardingBorderStroke.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("telescope_mock_chat_card")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Mini Chat Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "A",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Alex",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnboardingInkBlack
                                )
                                Text(
                                    text = "Active now",
                                    fontSize = 12.sp,
                                    color = Color(0xFF16A34A)
                                )
                            }
                        }

                        // Chat Thread Body — leaves natural headroom above Alex's message so the
                        // exact live Telescope Action Bar + Result Card anchor right above it!
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Exact Live Telescope Action Overlay (anchored directly ABOVE highlighted text,
                            // pixel-identical to TelescopeActionOverlay.kt!)
                            AnimatedVisibility(
                                visible = isMessageLocked && !practiceDone,
                                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
                            ) {
                                MyApplicationTheme(darkTheme = true, dynamicColor = true) {
                                    TelescopeWorkspaceContent(
                                        state = workspaceState,
                                        enabledModules = enabledModules,
                                        enabledLanguages = enabledLanguages,
                                        enabledTones = enabledTones,
                                        onSelectModule = { mod -> handleModuleSelected(mod) },
                                        onSelectLanguage = { lang -> handleLanguageSelected(lang) },
                                        onSelectReplyLength = { len -> handleReplyLengthSelected(len) },
                                        onSelectTone = { tone -> handleToneSelected(tone) },
                                        onCloseCard = { resetWorkspaceLock() },
                                        onCopyResult = { text ->
                                            try {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                cm?.setPrimaryClip(ClipData.newPlainText("Rapcha result", text))
                                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                            } catch (_: Throwable) {
                                            }
                                        },
                                        onRetry = {
                                            lastRequest?.let { req ->
                                                executeAction(req, workspaceState.cardTitle)
                                            }
                                        },
                                        onInsertResult = { text ->
                                            insertedReply = text
                                            practiceDone = true
                                            isMessageLocked = false
                                        }
                                    )
                                }
                            }

                            // When the live bar isn't open yet, keep Alex's message balanced nicely
                            // in the middle of the chat window so drag motion feels effortless.
                            if (!isMessageLocked && !practiceDone) {
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            // Incoming Message wrapped in the exact 10dp Dynamic Color Telescope Highlight Box
                            // (matches TelescopeHighlightView.kt: 10dp radius, 2.5dp stroke, 0x29 fill, 0xF2 border)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.90f)
                                    .onGloballyPositioned { coords ->
                                        messageBoundsInRoot = coords.boundsInRoot()
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isHighlightVisible) telescopePalette.highlightFill else Color.Transparent
                                    )
                                    .then(
                                        if (isHighlightVisible) {
                                            Modifier.border(
                                                width = 2.5.dp,
                                                color = telescopePalette.highlightStroke,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(3.dp)
                                    .testTag("telescope_target_message")
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF1EFF5)
                                ) {
                                    Text(
                                        text = sampleIncomingMessage,
                                        fontSize = 14.5.sp,
                                        lineHeight = 20.sp,
                                        color = OnboardingInkBlack,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                                    )
                                }
                            }

                            // Inserted Outgoing Reply Bubble after tapping Insert
                            val finalReply = insertedReply
                            if (finalReply != null) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth(0.86f)
                                            .testTag("telescope_inserted_reply_bubble")
                                    ) {
                                        Text(
                                            text = finalReply,
                                            fontSize = 14.5.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Mini Chat Composer Footer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFFF3EFF6))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (practiceDone) "Reply sent!" else "Message Alex...",
                                fontSize = 14.sp,
                                color = OnboardingMutedText,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 2. Non-Translated Touch Tracking Layer (ONLY claims touches near the docked bubble ->
                // ZERO coordinate feedback loop, ZERO jumping/glitching while dragging!)
                if (!practiceDone && !isMessageLocked && stageSize.width > 0 && stageSize.height > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(4f)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val dock = latestDockCenter
                                    val distToDock = hypot(
                                        (down.position.x - dock.x).toDouble(),
                                        (down.position.y - dock.y).toDouble()
                                    ).toFloat()

                                    // Ignore touches elsewhere so taps/scrolls in chat work normally
                                    if (distToDock > hitRadiusPx) {
                                        return@awaitEachGesture
                                    }

                                    down.consume()
                                    hasUserInteracted = true
                                    isDraggingBubble = true

                                    val stageW = latestStageSize.width.toFloat().coerceAtLeast(1f)
                                    val stageH = latestStageSize.height.toFloat().coerceAtLeast(1f)

                                    fun computeMagnifierCenter(fingerPos: Offset): Offset {
                                        val cx = (fingerPos.x - fingerOffsetPx)
                                            .coerceIn(halfPointerPx * 0.55f, stageW - halfPointerPx * 0.55f)
                                        val cy = (fingerPos.y - fingerOffsetPx)
                                            .coerceIn(halfPointerPx * 0.55f, stageH - halfPointerPx * 0.55f)
                                        return Offset(cx, cy)
                                    }

                                    var currentCenter = computeMagnifierCenter(down.position)
                                    userDragCenter = currentCenter
                                    var hovered = false

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) {
                                            break
                                        }
                                        change.consume()
                                        currentCenter = computeMagnifierCenter(change.position)
                                        userDragCenter = currentCenter

                                        // Hit-test the exact circular glass lens center with gentle edge hysteresis
                                        val lensCenter = Offset(
                                            x = currentCenter.x + lensShiftPx,
                                            y = currentCenter.y + lensShiftPx
                                        )
                                        val targetRect = latestMessageRect
                                        val activeInflate = if (hovered) targetInflatePx * 1.45f else targetInflatePx
                                        hovered = targetRect != Rect.Zero &&
                                            targetRect.inflate(activeInflate).contains(lensCenter)
                                        isHoveringMessage = hovered
                                    }

                                    isDraggingBubble = false
                                    isHoveringMessage = false
                                    if (hovered) {
                                        isMessageLocked = true
                                    }
                                }
                            }
                    )
                }

                // 3. Visual Lifesaver Bubble <-> Exact Live Magnifying Glass ("Telescope")
                // Driven strictly by GPU graphicsLayer translation (0 relayouts, 60fps on Infinix Hot 11).
                if (!practiceDone && !isMessageLocked && stageSize.width > 0 && stageSize.height > 0) {
                    Box(
                        modifier = Modifier
                            .zIndex(5f)
                            .size(pointerWindowSizeDp)
                            .graphicsLayer {
                                translationX = activePointerCenter.x - halfPointerPx
                                translationY = activePointerCenter.y - halfPointerPx
                            }
                            .testTag("telescope_draggable_bubble"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showMagnifierShape) {
                            LiveTelescopeMagnifier(
                                palette = telescopePalette,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("telescope_magnifier_pointer")
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                contentDescription = "Hold and drag Lifesaver Bubble over message",
                                modifier = Modifier.size(BubbleChromeGeometry.CIRCLE_SIZE_DP.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Action Row
            if (practiceDone) {
                Button(
                    onClick = onComplete,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, OnboardingBorderStroke),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("btn_telescope_continue")
                ) {
                    Text(
                        text = "Continue",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            hasUserInteracted = true
                            isMessageLocked = true
                        },
                        modifier = Modifier.testTag("btn_telescope_tap_try")
                    ) {
                        Icon(
                            imageVector = RapchaIcons.Modules,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMessageLocked) "Highlighted" else "Preview highlight",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = onComplete,
                        modifier = Modifier.testTag("btn_telescope_skip")
                    ) {
                        Text(
                            text = "Skip",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnboardingMutedText
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pixel-faithful Compose port of `IdleBubbleNativeView.drawTelescopePointer`:
 * draws Rapcha's exact see-through Dynamic Color magnifying glass (soft contrast shadow halo,
 * translucent tinted glass lens, upper-left glossy reflection arc, thick 45-degree pill-capped
 * handle, and gradient circular rim) using `TelescopeMagnifierGeometry`.
 */
@Composable
private fun LiveTelescopeMagnifier(
    palette: LiveTelescopePalette,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        val lensR = TelescopeMagnifierGeometry.LENS_RADIUS_DP.dp.toPx()
        val rimStroke = TelescopeMagnifierGeometry.RIM_STROKE_DP.dp.toPx()
        val handleStroke = TelescopeMagnifierGeometry.HANDLE_STROKE_DP.dp.toPx()
        val lensCx = cx + TelescopeMagnifierGeometry.LENS_CENTER_SHIFT_DP.dp.toPx()
        val lensCy = cy + TelescopeMagnifierGeometry.LENS_CENTER_SHIFT_DP.dp.toPx()

        val diag = 0.70710677f
        val handleStartDist = lensR + rimStroke * 0.42f
        val handleEndDist = lensR + TelescopeMagnifierGeometry.HANDLE_EXTRA_LENGTH_DP.dp.toPx()
        val handleStart = Offset(lensCx + diag * handleStartDist, lensCy + diag * handleStartDist)
        val handleEnd = Offset(lensCx + diag * handleEndDist, lensCy + diag * handleEndDist)
        val lensCenter = Offset(lensCx, lensCy)

        val gradBrush = Brush.linearGradient(
            colors = listOf(palette.magnifierPrimary, palette.magnifierSecondary),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )

        // 1. Soft dark contrast halo behind rim & handle for readability over light & dark chats
        val shadowOffset = 1.2.dp.toPx()
        val shadowColor = Color(0x42000000)
        drawLine(
            color = shadowColor,
            start = handleStart + Offset(shadowOffset, shadowOffset),
            end = handleEnd + Offset(shadowOffset, shadowOffset),
            strokeWidth = handleStroke + 2.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = shadowColor,
            radius = lensR,
            center = lensCenter + Offset(shadowOffset, shadowOffset),
            style = Stroke(width = rimStroke + 2.4.dp.toPx())
        )

        // 2. See-through tinted glass lens
        drawCircle(
            color = palette.magnifierLensTint,
            radius = lensR - rimStroke * 0.35f,
            center = lensCenter
        )

        // 3. Subtle diagonal glossy reflection arc inside upper-left of glass lens
        val glossInset = 4.2.dp.toPx()
        val arcSide = ((lensR - glossInset) * 2f).coerceAtLeast(1f)
        drawArc(
            color = Color(0x6BFFFFFF),
            startAngle = 200f,
            sweepAngle = 56f,
            useCenter = false,
            topLeft = Offset(lensCx - lensR + glossInset, lensCy - lensR + glossInset),
            size = Size(arcSide, arcSide),
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )

        // 4. Thick pill-capped 45-degree handle seamlessly joined to outer rim edge
        drawLine(
            brush = gradBrush,
            start = handleStart,
            end = handleEnd,
            strokeWidth = handleStroke,
            cap = StrokeCap.Round
        )

        // 5. Crisp Dynamic Color gradient circular rim
        drawCircle(
            brush = gradBrush,
            radius = lensR,
            center = lensCenter,
            style = Stroke(width = rimStroke)
        )
    }
}

private data class LiveTelescopePalette(
    val magnifierPrimary: Color,
    val magnifierSecondary: Color,
    val magnifierLensTint: Color,
    val highlightFill: Color,
    val highlightStroke: Color
)

private fun resolveLiveTelescopePalette(context: Context): LiveTelescopePalette {
    var primaryRgb: Int
    var secondaryRgb: Int
    var containerRgb: Int
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            primaryRgb = ContextCompat.getColor(context, android.R.color.system_accent1_200)
            secondaryRgb = ContextCompat.getColor(context, android.R.color.system_accent1_500)
            containerRgb = ContextCompat.getColor(context, android.R.color.system_accent1_700)
        } catch (_: Throwable) {
            val scheme = getAppDynamicColorScheme(context, darkTheme = true, toneId = "luminous")
            primaryRgb = scheme.primary.toArgbInt()
            secondaryRgb = scheme.secondary.toArgbInt()
            containerRgb = scheme.primaryContainer.toArgbInt()
        }
    } else {
        val scheme = getAppDynamicColorScheme(context, darkTheme = true, toneId = "luminous")
        primaryRgb = scheme.primary.toArgbInt()
        secondaryRgb = scheme.secondary.toArgbInt()
        containerRgb = scheme.primaryContainer.toArgbInt()
    }

    val rgbPrimary = primaryRgb and 0x00FFFFFF
    val rgbSecondary = secondaryRgb and 0x00FFFFFF
    val rgbContainer = containerRgb and 0x00FFFFFF

    return LiveTelescopePalette(
        magnifierPrimary = Color((0xFF shl 24) or rgbPrimary),
        magnifierSecondary = Color((0xFF shl 24) or rgbSecondary),
        magnifierLensTint = Color((0x4F shl 24) or rgbContainer),
        highlightFill = Color((0x29 shl 24) or rgbPrimary),
        highlightStroke = Color((0xF2 shl 24) or rgbPrimary)
    )
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun buildFallbackTextFor(request: TextActionRequest): String {
    return when (request) {
        is TextActionRequest.Grammar ->
            "Hey! Are we still on for the 3 PM meeting today, or should we move it to tomorrow morning?"
        is TextActionRequest.Translate -> when (request.languageName.lowercase()) {
            "spanish" -> "¡Hola! ¿Seguimos en pie para la reunión de las 3 PM hoy, o la movemos a mañana por la mañana?"
            "french" -> "Salut ! Sommes-nous toujours partants pour la réunion de 15 h aujourd'hui, ou préfères-tu demain matin ?"
            else -> "Hey! Are we still confirmed for today's 3 PM meeting, or should we reschedule to tomorrow morning?"
        }
        is TextActionRequest.Reply -> when (request.length) {
            ReplyLength.SHORT -> "Yes, 3 PM today still works great — see you then!"
            ReplyLength.MEDIUM -> "Hey Alex! 3 PM today still works great on my end, so let's keep it as planned. See you soon!"
            ReplyLength.LONG -> "Hey Alex! Thanks for checking in — 3 PM today still works well for me, so let's keep it on the calendar. Let me know if anything changes on your side!"
            ReplyLength.NO_REPLY -> "Hey Alex, sorry — my afternoon filled up unexpectedly. Could we push this to tomorrow morning instead?"
        }
        is TextActionRequest.Tone ->
            "Hi Alex, confirming whether our 3:00 PM meeting today still works for you, or if tomorrow morning is preferable."
        is TextActionRequest.CustomModule ->
            "Yes, 3 PM today still works great — see you then!"
    }
}

private fun defaultPracticeModules(): List<ModuleItem> = listOf(
    ModuleItem(id = ToneRepository.MODULE_GRAMMAR, name = "Grammar", query = "", isBuiltIn = true, enabled = true),
    ModuleItem(id = ToneRepository.MODULE_TRANSLATE, name = "Translate", query = "", isBuiltIn = true, enabled = true),
    ModuleItem(id = ToneRepository.MODULE_REPLY, name = "Reply", query = "", isBuiltIn = true, enabled = true),
    ModuleItem(id = ToneRepository.MODULE_TONE, name = "Tone", query = "", isBuiltIn = true, enabled = true)
)

private fun defaultPracticeLanguages(): List<TargetLanguageItem> = listOf(
    TargetLanguageItem(id = "spanish", name = "Spanish", enabled = true),
    TargetLanguageItem(id = "french", name = "French", enabled = true),
    TargetLanguageItem(id = "german", name = "German", enabled = true)
)

private fun defaultPracticeTones(): List<ToneItem> = listOf(
    ToneItem(id = "professional", name = "Professional", instruction = "Rewrite professionally", isDefault = true, enabled = true),
    ToneItem(id = "friendly", name = "Friendly", instruction = "Rewrite warmly and casually", isDefault = true, enabled = true)
)
