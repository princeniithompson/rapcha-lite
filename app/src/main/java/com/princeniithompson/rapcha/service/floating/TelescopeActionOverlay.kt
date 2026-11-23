package com.princeniithompson.rapcha.service.floating

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.data.FloatingTextActionsClient
import com.princeniithompson.rapcha.data.ModuleItem
import com.princeniithompson.rapcha.data.ReplyLength
import com.princeniithompson.rapcha.data.TargetLanguageItem
import com.princeniithompson.rapcha.data.TextActionRequest
import com.princeniithompson.rapcha.data.TextActionResult
import com.princeniithompson.rapcha.data.ToneItem
import com.princeniithompson.rapcha.data.ToneRepository
import com.princeniithompson.rapcha.ui.screens.CuratedIconCatalog
import com.princeniithompson.rapcha.ui.screens.ItemBadgeGlyph
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure execution policy for floating bar taps:
 * - Grammar & Custom Modules execute immediately.
 * - Translate, Reply, and Tone ONLY open their sub-option chips and wait for an explicit chip tap.
 */
internal object TelescopeExecutionPolicy {
    fun executesImmediatelyOnTap(moduleId: String): Boolean {
        return when (moduleId) {
            ToneRepository.MODULE_TRANSLATE,
            ToneRepository.MODULE_REPLY,
            ToneRepository.MODULE_TONE -> false
            else -> true
        }
    }
}

internal data class TelescopeActionUiState(
    val sourceText: String = "",
    val activeModuleId: String? = null,
    val selectedLanguageId: String? = null,
    val selectedReplyLength: ReplyLength? = null,
    val selectedToneId: String? = null,
    val cardTitle: String = "",
    val isLoading: Boolean = false,
    val resultText: String? = null,
    val errorMessage: String? = null
)

/**
 * Compact, non-focus-stealing floating workspace anchored above (or below) the highlighted text.
 *
 * Strictly lazy: creates its ComposeView + LifecycleOwner only when shown, and disposes both
 * completely on dismiss so idle RAM overhead is zero on low-end devices.
 */
internal class TelescopeActionOverlay(
    private val context: Context,
    private val onInsertText: (String) -> Unit,
    private val onDismissed: () -> Unit
) {
    companion object {
        private const val TAG = "TelescopeActionOverlay"
        private const val ANCHOR_MARGIN_DP = 10f
        private const val HORIZONTAL_MARGIN_DP = 12f
        private const val TOP_SAFE_THRESHOLD_FRACTION = 0.34f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var lifecycleOwner: FloatingOverlayLifecycleOwner? = null
    private var rootContainer: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var scope: CoroutineScope? = null
    private var activeJob: Job? = null

    private val uiState = MutableStateFlow(TelescopeActionUiState())
    private var lastRequest: TextActionRequest? = null

    val isShowing: Boolean
        get() = rootContainer != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(targetText: String, targetBounds: Rect) {
        dismiss(notifyCaller = false)
        ToneRepository.init(context)

        val wm = windowManager ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val marginPx = (ANCHOR_MARGIN_DP * density).toInt()
        val sideMarginPx = (HORIZONTAL_MARGIN_DP * density).toInt()

        uiState.value = TelescopeActionUiState(sourceText = targetText)

        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = overlayScope

        val owner = FloatingOverlayLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }
        lifecycleOwner = owner

        val safeContext = createFontWeightSafeContext(context)
        val container = object : FrameLayout(safeContext) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss(notifyCaller = true)
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        owner.attachToView(container)

        val cv = ComposeView(safeContext).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            owner.attachToView(this)
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by uiState.collectAsState()
                val allModules by ToneRepository.modules.collectAsState()
                val allTones by ToneRepository.tones.collectAsState()
                val allLanguages by ToneRepository.languages.collectAsState()
                val enabledModules = remember(allModules) { allModules.filter { it.enabled } }
                val enabledTones = remember(allTones) { allTones.filter { it.enabled } }
                val enabledLanguages = remember(allLanguages) { allLanguages.filter { it.enabled } }
                val localDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(localDensity.density, fontScale = 1f)) {
                    MyApplicationTheme(darkTheme = true, dynamicColor = true) {
                        TelescopeWorkspaceContent(
                            state = state,
                            enabledModules = enabledModules,
                            enabledLanguages = enabledLanguages,
                            enabledTones = enabledTones,
                            onSelectModule = { mod -> handleModuleSelected(mod) },
                            onSelectLanguage = { lang -> handleLanguageSelected(lang) },
                            onSelectReplyLength = { length -> handleReplyLengthSelected(length) },
                            onSelectTone = { tone -> handleToneSelected(tone) },
                            onCloseCard = { dismiss(notifyCaller = true) },
                            onCopyResult = { text -> copyToClipboard(text) },
                            onRetry = { retryLastAction() },
                            onInsertResult = { text ->
                                dismiss(notifyCaller = true)
                                onInsertText(text)
                            }
                        )
                    }
                }
            }
        }
        container.addView(
            cv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val placeAbove = targetBounds.top >= (screenHeight * TOP_SAFE_THRESHOLD_FRACTION).toInt()
        val gravity = if (placeAbove) {
            Gravity.BOTTOM or Gravity.START
        } else {
            Gravity.TOP or Gravity.START
        }
        val anchorY = if (placeAbove) {
            (screenHeight - targetBounds.top + marginPx).coerceIn(marginPx, (screenHeight * 0.72f).toInt())
        } else {
            (targetBounds.bottom + marginPx).coerceIn((48 * density).toInt(), (screenHeight * 0.70f).toInt())
        }

        val lp = WindowManager.LayoutParams(
            (displayMetrics.widthPixels - sideMarginPx * 2).coerceAtLeast(240),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = sideMarginPx
            y = anchorY
            windowAnimations = 0
        }

        try {
            wm.addView(container, lp)
            rootContainer = container
            composeView = cv
        } catch (t: Throwable) {
            Log.e(TAG, "Failed attaching TelescopeActionOverlay", t)
            dismiss(notifyCaller = false)
        }
    }

    fun dismiss(notifyCaller: Boolean = true) {
        activeJob?.cancel()
        activeJob = null
        scope?.cancel()
        scope = null

        val cv = composeView
        composeView = null
        try {
            cv?.disposeComposition()
        } catch (_: Throwable) {
        }

        val container = rootContainer
        rootContainer = null
        if (container != null) {
            try {
                windowManager?.removeViewImmediate(container)
            } catch (_: Throwable) {
                try {
                    windowManager?.removeView(container)
                } catch (_: Throwable) {
                }
            }
        }

        lifecycleOwner?.let { owner ->
            try {
                owner.onPause()
                owner.onStop()
                owner.onDestroy()
            } catch (_: Throwable) {
            }
        }
        lifecycleOwner = null
        lastRequest = null

        if (notifyCaller && container != null) {
            onDismissed()
        }
    }

    private fun handleModuleSelected(module: ModuleItem) {
        val current = uiState.value
        if (TelescopeExecutionPolicy.executesImmediatelyOnTap(module.id)) {
            uiState.value = current.copy(
                activeModuleId = module.id,
                cardTitle = module.name
            )
            val request = if (module.id == ToneRepository.MODULE_GRAMMAR) {
                TextActionRequest.Grammar(current.sourceText)
            } else {
                TextActionRequest.CustomModule(
                    text = current.sourceText,
                    moduleName = module.name,
                    query = module.query
                )
            }
            runRequest(request, title = module.name)
            return
        }

        // Deferred execution: open sub-chips row and WAIT for an explicit sub-chip tap (zero API call).
        activeJob?.cancel()
        activeJob = null
        lastRequest = null

        val emptyHint = when (module.id) {
            ToneRepository.MODULE_TRANSLATE -> {
                if (ToneRepository.getEnabledLanguages().isEmpty()) {
                    "Enable or add a language in Rapcha's Modules tab."
                } else null
            }
            ToneRepository.MODULE_TONE -> {
                if (ToneRepository.getEnabledTones().isEmpty()) {
                    "Enable or add a tone in Rapcha's Modules tab."
                } else null
            }
            else -> null
        }

        uiState.value = current.copy(
            activeModuleId = module.id,
            selectedLanguageId = null,
            selectedReplyLength = null,
            selectedToneId = null,
            cardTitle = module.name,
            isLoading = false,
            resultText = null,
            errorMessage = emptyHint
        )
    }

    private fun handleLanguageSelected(lang: TargetLanguageItem) {
        ToneRepository.setLastTranslateLanguage(lang.name)
        val current = uiState.value
        uiState.value = current.copy(
            activeModuleId = ToneRepository.MODULE_TRANSLATE,
            selectedLanguageId = lang.id,
            cardTitle = lang.name
        )
        runRequest(
            TextActionRequest.Translate(
                text = current.sourceText,
                languageName = lang.name,
                instruction = lang.instruction
            ),
            title = lang.name
        )
    }

    private fun handleReplyLengthSelected(length: ReplyLength) {
        ToneRepository.setLastReplyLength(length.displayName)
        val current = uiState.value
        uiState.value = current.copy(
            activeModuleId = ToneRepository.MODULE_REPLY,
            selectedReplyLength = length,
            cardTitle = length.displayName
        )
        runRequest(TextActionRequest.Reply(current.sourceText, length), title = length.displayName)
    }

    private fun handleToneSelected(tone: ToneItem) {
        val current = uiState.value
        uiState.value = current.copy(
            activeModuleId = ToneRepository.MODULE_TONE,
            selectedToneId = tone.id,
            cardTitle = tone.displayLabel
        )
        runRequest(
            TextActionRequest.Tone(current.sourceText, tone.name, tone.instruction),
            title = tone.displayLabel
        )
    }

    private fun retryLastAction() {
        val req = lastRequest ?: return
        runRequest(req, title = uiState.value.cardTitle)
    }

    private fun runRequest(request: TextActionRequest, title: String) {
        lastRequest = request
        activeJob?.cancel()
        uiState.value = uiState.value.copy(
            cardTitle = title,
            isLoading = true,
            resultText = null,
            errorMessage = null
        )
        val s = scope ?: return
        activeJob = s.launch {
            val res = FloatingTextActionsClient.executeStreaming(
                context = context,
                request = request,
                onPartialText = { partial ->
                    s.launch(Dispatchers.Main.immediate) {
                        if (lastRequest === request && uiState.value.isLoading) {
                            uiState.value = uiState.value.copy(
                                resultText = partial,
                                errorMessage = null
                            )
                        }
                    }
                }
            )
            when (res) {
                is TextActionResult.Success -> {
                    uiState.value = uiState.value.copy(
                        isLoading = false,
                        resultText = res.text,
                        errorMessage = null
                    )
                }
                is TextActionResult.Error -> {
                    uiState.value = uiState.value.copy(
                        isLoading = false,
                        resultText = null,
                        errorMessage = res.message
                    )
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Rapcha result", text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun createFontWeightSafeContext(base: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        return try {
            val config = Configuration(base.resources.configuration)
            config.fontWeightAdjustment = 0
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }
    }
}

private fun builtInModuleIconFor(moduleId: String): ImageVector = when (moduleId) {
    ToneRepository.MODULE_GRAMMAR -> Icons.Filled.Spellcheck
    ToneRepository.MODULE_TRANSLATE -> Icons.Filled.Translate
    ToneRepository.MODULE_REPLY -> Icons.AutoMirrored.Filled.Reply
    ToneRepository.MODULE_TONE -> Icons.Rounded.RecordVoiceOver
    else -> Icons.Rounded.AutoFixHigh
}

@Composable
internal fun TelescopeWorkspaceContent(
    state: TelescopeActionUiState,
    enabledModules: List<ModuleItem>,
    enabledLanguages: List<TargetLanguageItem>,
    enabledTones: List<ToneItem>,
    onSelectModule: (ModuleItem) -> Unit,
    onSelectLanguage: (TargetLanguageItem) -> Unit,
    onSelectReplyLength: (ReplyLength) -> Unit,
    onSelectTone: (ToneItem) -> Unit,
    onCloseCard: () -> Unit,
    onCopyResult: (String) -> Unit,
    onRetry: () -> Unit,
    onInsertResult: (String) -> Unit
) {
    val showCard = state.activeModuleId != null &&
        (state.isLoading || state.resultText != null || state.errorMessage != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("telescope_action_workspace"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Floating Result Card (above chips and action bar, max height ~220dp)
        if (showCard) {
            TelescopeResultCard(
                title = state.cardTitle,
                isLoading = state.isLoading,
                resultText = state.resultText,
                errorMessage = state.errorMessage,
                onClose = onCloseCard,
                onCopy = onCopyResult,
                onRetry = onRetry,
                onInsert = onInsertResult
            )
        }

        // 2. Parameter Chips directly above the action bar (shown immediately on tap, waits for click)
        when (state.activeModuleId) {
            ToneRepository.MODULE_TRANSLATE -> {
                if (enabledLanguages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("telescope_translate_chips"),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        enabledLanguages.forEach { lang ->
                            ParameterChip(
                                label = lang.name,
                                selected = state.selectedLanguageId == lang.id,
                                onClick = { onSelectLanguage(lang) },
                                testTag = "translate_chip_${lang.id}"
                            )
                        }
                    }
                }
            }
            ToneRepository.MODULE_REPLY -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("telescope_reply_chips"),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ReplyLength.entries.forEach { length ->
                        ParameterChip(
                            label = length.displayName,
                            selected = state.selectedReplyLength == length,
                            onClick = { onSelectReplyLength(length) },
                            testTag = "reply_chip_${length.displayName.lowercase().replace(" ", "_")}"
                        )
                    }
                }
            }
            ToneRepository.MODULE_TONE -> {
                if (enabledTones.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("telescope_tone_chips"),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        enabledTones.forEach { tone ->
                            ParameterChip(
                                label = tone.name,
                                iconEmoji = tone.iconEmoji,
                                iconKey = tone.iconKey,
                                iconColorKey = tone.iconColorKey,
                                selected = state.selectedToneId == tone.id,
                                onClick = { onSelectTone(tone) },
                                testTag = "tone_chip_${tone.id}"
                            )
                        }
                    }
                }
            }
            else -> Unit
        }

        // 3. Compact Floating Action Bar with clean outer padding and spaced Dynamic Color pills
        // Zero shadowElevation + explicit clip prevents rectangular overlay-window corner halos.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .testTag("telescope_action_bar")
        ) {
            if (enabledModules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Enable modules in Rapcha's Modules tab",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    enabledModules.forEach { module ->
                        ActionBarPillButton(
                            module = module,
                            selected = state.activeModuleId == module.id,
                            onClick = { onSelectModule(module) },
                            testTag = "action_bar_${module.id}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBarPillButton(
    module: ModuleItem,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!module.isBuiltIn && (module.iconEmoji.isNotBlank() || module.iconKey.isNotBlank())) {
                ItemBadgeGlyph(
                    iconEmoji = module.iconEmoji,
                    iconKey = module.iconKey,
                    iconColorKey = if (selected) "" else module.iconColorKey,
                    defaultTint = content,
                    iconSize = 15.5.dp,
                    emojiSize = 14.5.sp
                )
            } else {
                Icon(
                    imageVector = builtInModuleIconFor(module.id),
                    contentDescription = module.name,
                    tint = content,
                    modifier = Modifier.size(15.5.dp)
                )
            }
            Spacer(modifier = Modifier.width(5.5.dp))
            Text(
                text = module.name,
                color = content,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ParameterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    iconEmoji: String = "",
    iconKey: String = "",
    iconColorKey: String = ""
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
        ),
        shadowElevation = 0.dp,
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (iconEmoji.isNotBlank() || iconKey.isNotBlank()) {
                ItemBadgeGlyph(
                    iconEmoji = iconEmoji,
                    iconKey = iconKey,
                    iconColorKey = if (selected) "" else iconColorKey,
                    defaultTint = contentColor,
                    iconSize = 15.dp,
                    emojiSize = 14.5.sp
                )
                Spacer(modifier = Modifier.width(5.5.dp))
            }
            Text(
                text = label,
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun TelescopeResultCard(
    title: String,
    isLoading: Boolean,
    resultText: String?,
    errorMessage: String?,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onInsert: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(20.dp))
            .testTag("telescope_result_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                    contentDescription = "Rapcha",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("telescope_result_close")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when {
                isLoading && resultText.isNullOrEmpty() -> {
                    TelescopeGeneratingStarRow()
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("telescope_result_retry")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                resultText != null -> {
                    val textScrollState = rememberScrollState()
                    LaunchedEffect(resultText, isLoading) {
                        if (isLoading && textScrollState.maxValue > 0) {
                            textScrollState.scrollTo(textScrollState.maxValue)
                        }
                    }
                    val displayText = if (isLoading) "$resultText \u258D" else resultText
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 112.dp)
                            .verticalScroll(textScrollState)
                    ) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("telescope_result_text")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = { onCopy(resultText) },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("telescope_result_copy")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = onRetry,
                                enabled = !isLoading,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("telescope_result_retry")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (isLoading) {
                            Text(
                                text = "Streaming...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        } else {
                            Button(
                                onClick = { onInsert(resultText) },
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("telescope_result_insert")
                            ) {
                                Text(
                                    text = "Insert",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated sparkle star shown strictly while waiting for the first streamed token from Gemini.
 * Unmounts cleanly (stopping all GPU transforms) the instant streaming begins.
 */
@Composable
private fun TelescopeGeneratingStarRow() {
    val infiniteTransition = rememberInfiniteTransition(label = "telescope_sparkle_loading")
    val starScale by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "star_scale"
    )
    val starRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "star_rotation"
    )
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_scale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_alpha"
    )

    var dotCount by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(360L)
            dotCount = (dotCount % 3) + 1
        }
    }
    val dots = ".".repeat(dotCount)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .testTag("telescope_result_loading"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                    alpha = haloAlpha
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "Generating",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .size(19.dp)
                    .graphicsLayer {
                        scaleX = starScale
                        scaleY = starScale
                        rotationZ = starRotation
                    }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Generating$dots",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Please wait, your text is coming",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
