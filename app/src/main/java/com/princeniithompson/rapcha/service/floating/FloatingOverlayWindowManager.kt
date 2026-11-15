package com.princeniithompson.rapcha.service.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.compositionContext
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.diagnostics.BubbleHealthRateLimiter
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.service.A11yWatchdog
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the overlay WindowManager, layout parameters, lifecycle, and view composition
 * for the floating voice bubble.
 *
 * Uses a lightweight native [IdleBubbleNativeView] while collapsed so idle background
 * RAM stays minimal on 3 GB devices, and lazily creates/disposes the expanded [ComposeView]
 * only during an active dictation session.
 */
class FloatingOverlayWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingOverlayWinMgr"
        private const val SHRINK_TIMEOUT_MS = 3000L
        const val BUBBLE_ANIM_MS = 140L

        private val ATTACH_BACKOFF_MS = longArrayOf(250L, 1_000L, 3_000L, 10_000L)

        /**
         * The bubble and the expanded compact pill share one window.
         * NOT_FOCUSABLE keeps a tap on Complete from taking input focus, so the editor's
         * input connection stays bound. NOT_TOUCH_MODAL lets touches outside the overlay
         * through. LAYOUT_NO_LIMITS + LAYOUT_IN_SCREEN + HARDWARE_ACCELERATED match reliable
         * overlay hosts on One UI and Android 12-16.
         */
        const val OVERLAY_WINDOW_FLAGS: Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val lifecycleOwner = FloatingOverlayLifecycleOwner()
    val dragSnapHandler = FloatingDragSnapHandler(context)

    private var overlayScope: CoroutineScope? = null
    private var windowManager: WindowManager? = null
    var overlayView: View? = null
        private set
    var layoutParams: WindowManager.LayoutParams? = null
        private set

    private var overlayRoot: FrameLayout? = null
    private var idleBubbleView: IdleBubbleNativeView? = null
    private var expandedComposeView: ComposeView? = null

    private var onRingClickCallback: () -> Unit = {}
    private var onCancelClickCallback: () -> Unit = {}
    private var onCompleteClickCallback: () -> Unit = {}
    private var onLongPressBubbleCallback: () -> Unit = {}
    private var onRepairClickCallback: () -> Unit = {}

    @Volatile
    var lastHideReason: String? = null
        private set

    private val attachRateLimiter = BubbleHealthRateLimiter(capPerWindow = 6, windowMs = 60_000L)
    private var attachRetryAttempt = 0
    private var pendingRetryRunnable: Runnable? = null

    val overlayRecording = MutableStateFlow(false)
    val overlayElapsedSeconds = MutableStateFlow(0)
    val overlayPendingFinalizing = MutableStateFlow(false)
    val overlayExpanded = MutableStateFlow(false)
    val overlayShrunk = MutableStateFlow(false)
    val overlayBubbleVisible = MutableStateFlow(false)
    /** Live transcript for the Words bubble. */
    val overlayTranscript = MutableStateFlow("")
    /** 0..1 voice level that drives the glow. */
    val overlayAudioAmplitude = MutableStateFlow(0f)

    private val shrinkRunnable = Runnable { applyShrink() }

    val isSnappedToRight: Boolean
        get() = dragSnapHandler.isSnappedToRight

    fun isAttached(): Boolean = overlayView?.isAttachedToWindow == true

    fun isWindowVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    fun shouldBeVisible(): Boolean {
        return overlayRecording.value ||
            overlayExpanded.value ||
            overlayPendingFinalizing.value ||
            FloatingBubbleManager.isKeyboardVisible.value ||
            A11yWatchdog.repairKind.value != A11yRepairKind.NONE
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

    @SuppressLint("ClickableViewAccessibility")
    fun initOverlay(
        onRingClick: () -> Unit,
        onCancelClick: () -> Unit,
        onCompleteClick: () -> Unit,
        onLongPressBubble: () -> Unit = {},
        onRepairClick: () -> Unit = {}
    ) {
        onRingClickCallback = onRingClick
        onCancelClickCallback = onCancelClick
        onCompleteClickCallback = onCompleteClick
        onLongPressBubbleCallback = onLongPressBubble
        onRepairClickCallback = onRepairClick

        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onPause()

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        dragSnapHandler.loadPreferences()
        dragSnapHandler.updateImeTopPx(FloatingBubbleManager.imeTopPx.value)

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()

        val initialY = dragSnapHandler.resolveYForWindow(
            windowHeightPx = windowSize,
            isExpanded = false
        )
        val initialX = if (dragSnapHandler.isSnappedToRight) {
            (screenWidth - windowSize - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
        } else {
            (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            windowType,
            OVERLAY_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val safeContext = createFontWeightSafeContext(context)
        val rootContainer = FrameLayout(safeContext).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        lifecycleOwner.attachToView(rootContainer)
        rootContainer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // WindowRecomposer cancels the root's Recomposer on detach; clear the reference
                // so a subsequent re-attach & expand creates a fresh active Recomposer.
                try {
                    v.compositionContext = null
                } catch (_: Throwable) {
                }
            }
        })

        val nativeIdle = IdleBubbleNativeView(
            context = safeContext,
            onRingClick = { onRingClickCallback() },
            onRepairClick = { onRepairClickCallback() },
            onLongPressBubble = { onLongPressBubbleCallback() },
            onDragStart = {
                resetInactivityTimer(keepShrunk = true)
            },
            onDrag = { dx, dy ->
                val lp = layoutParams ?: return@IdleBubbleNativeView
                dragSnapHandler.handleOverlayDrag(
                    dx = dx,
                    dy = dy,
                    isExpanded = overlayExpanded.value,
                    lp = lp,
                    windowManager = windowManager,
                    overlayView = overlayView
                )
            },
            onDragEnd = {
                val lp = layoutParams ?: return@IdleBubbleNativeView
                dragSnapHandler.handleOverlayDragEnd(
                    isExpanded = overlayExpanded.value,
                    lp = lp,
                    windowManager = windowManager,
                    overlayView = overlayView,
                    onComplete = {
                        syncIdleBubbleState()
                        resetInactivityTimer(keepShrunk = true)
                    }
                )
            }
        )
        rootContainer.addView(
            nativeIdle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlayRoot = rootContainer
        idleBubbleView = nativeIdle
        overlayView = rootContainer
        syncIdleBubbleState()

        val showNow = shouldBeVisible()
        setViewVisibilityImmediate(
            rootContainer,
            showNow,
            reason = if (showNow) null else BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE
        )

        attachWithRetry("init")

        overlayScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        overlayScope = scope

        scope.launch {
            FloatingBubbleManager.bubbleChrome.collect {
                if (overlayExpanded.value) {
                    expandPanel()
                }
            }
        }
        scope.launch {
            FloatingBubbleManager.imeTopPx.collect { imeTop ->
                dragSnapHandler.updateImeTopPx(imeTop)
                if (imeTop > 0 && (shouldBeVisible() || isWindowVisible())) {
                    syncVerticalPositionForKeyboard(imeTop)
                }
            }
        }
        scope.launch {
            FloatingBubbleManager.isNetworkProblem.collect { syncIdleBubbleState() }
        }
        scope.launch {
            FloatingBubbleManager.isClipboardFallbackIndicator.collect { syncIdleBubbleState() }
        }
        scope.launch {
            overlayRecording.collect { syncIdleBubbleState() }
        }
        scope.launch {
            overlayShrunk.collect { syncIdleBubbleState() }
        }
        scope.launch {
            A11yWatchdog.repairKind.collect { kind ->
                syncIdleBubbleState()
                if (kind != A11yRepairKind.NONE) {
                    val root = overlayView ?: return@collect
                    if (!root.isAttachedToWindow) {
                        attachWithRetry("a11y_repair")
                    }
                    revealBubble(root)
                } else if (kind == A11yRepairKind.NONE &&
                    !FloatingBubbleManager.isKeyboardVisible.value &&
                    !overlayRecording.value &&
                    !overlayExpanded.value
                ) {
                    overlayView?.let { concealBubble(it, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE) }
                }
            }
        }

        resetInactivityTimer()
    }

    private fun syncIdleBubbleState() {
        idleBubbleView?.updateState(
            recording = overlayRecording.value,
            shrunk = overlayShrunk.value,
            snappedToRight = dragSnapHandler.isSnappedToRight,
            networkProblem = FloatingBubbleManager.isNetworkProblem.value,
            clipboardFallback = FloatingBubbleManager.isClipboardFallbackIndicator.value,
            repairKind = A11yWatchdog.repairKind.value
        )
    }

    private fun syncVerticalPositionForKeyboard(imeTopPx: Int) {
        val lp = layoutParams ?: return
        val view = overlayView ?: return
        val density = context.resources.displayMetrics.density
        val isExpanded = overlayExpanded.value
        val estWindowHeightPx = when {
            !isExpanded -> (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
            FloatingBubbleManager.bubbleChrome.value == BubbleChromeStyle.COMPACT ->
                (BubbleChromeGeometry.RECORDING_WINDOW_HEIGHT_DP * density).toInt()
            else -> (190 * density).toInt()
        }
        val nextY = dragSnapHandler.resolveYForWindow(
            windowHeightPx = estWindowHeightPx,
            isExpanded = isExpanded,
            imeTopPx = imeTopPx
        )
        if (lp.y == nextY) return
        lp.y = nextY
        if (view.isAttachedToWindow) {
            try {
                windowManager?.updateViewLayout(view, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing overlay Y to keyboard bounds", e)
            }
        }
    }

    private fun ensureExpandedComposeViewAttached() {
        val root = overlayRoot ?: return
        if (expandedComposeView != null) {
            expandedComposeView?.visibility = View.VISIBLE
            return
        }
        val composeContext = createFontWeightSafeContext(context)
        val useLowPower = DevicePerformanceTier.isLowPowerDevice(context)
        val composeView = ComposeView(composeContext).apply {
            lifecycleOwner.attachToView(this)
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val recording by overlayRecording.collectAsState()
                val pendingFinalizing by overlayPendingFinalizing.collectAsState()
                val isExpanded by overlayExpanded.collectAsState()
                val isShrunk by overlayShrunk.collectAsState()
                val elapsedSeconds by overlayElapsedSeconds.collectAsState()
                val repairKind by A11yWatchdog.repairKind.collectAsState()
                val transcript by overlayTranscript.collectAsState()
                val audioAmplitude by overlayAudioAmplitude.collectAsState()
                val bubbleVisible by overlayBubbleVisible.collectAsState()

                FloatingOverlayContent(
                    recording = recording,
                    pendingFinalizing = pendingFinalizing,
                    isExpanded = isExpanded,
                    isShrunk = isShrunk,
                    isSnappedToRight = dragSnapHandler.isSnappedToRight,
                    elapsedSeconds = elapsedSeconds,
                    transcript = transcript,
                    audioAmplitude = audioAmplitude,
                    bubbleVisible = bubbleVisible,
                    lowPower = useLowPower,
                    repairKind = repairKind,
                    onRepairClick = onRepairClickCallback,
                    onRingClick = onRingClickCallback,
                    onCancelClick = onCancelClickCallback,
                    onCompleteClick = onCompleteClickCallback,
                    onLongPressBubble = onLongPressBubbleCallback,
                    onDragStart = {
                        resetInactivityTimer(keepShrunk = true)
                    },
                    onDrag = { dx, dy ->
                        val lp = this@FloatingOverlayWindowManager.layoutParams ?: return@FloatingOverlayContent
                        dragSnapHandler.handleOverlayDrag(
                            dx = dx,
                            dy = dy,
                            isExpanded = overlayExpanded.value,
                            lp = lp,
                            windowManager = windowManager,
                            overlayView = overlayView
                        )
                    },
                    onDragEnd = {
                        val lp = this@FloatingOverlayWindowManager.layoutParams ?: return@FloatingOverlayContent
                        dragSnapHandler.handleOverlayDragEnd(
                            isExpanded = overlayExpanded.value,
                            lp = lp,
                            windowManager = windowManager,
                            overlayView = overlayView,
                            onComplete = {
                                resetInactivityTimer(keepShrunk = true)
                            }
                        )
                    }
                )
            }
        }
        expandedComposeView = composeView
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun disposeExpandedComposeView() {
        val compose = expandedComposeView ?: return
        expandedComposeView = null
        try {
            compose.disposeComposition()
        } catch (_: Throwable) {
        }
        try {
            overlayRoot?.removeView(compose)
        } catch (_: Throwable) {
        }
    }

    fun attachWithRetry(reason: String): Boolean {
        val view = overlayView ?: return false
        val lp = layoutParams ?: return false
        if (view.isAttachedToWindow) return true

        if (!Settings.canDrawOverlays(context)) {
            BubbleHealthLog.updateOpportunityBlockingReason("overlay_permission_missing")
            BubbleHealthLog.record(
                context = context,
                type = DiagnosticType.BUBBLE_ATTACH_FAILED,
                message = "reason=$reason kind=overlay_permission_missing"
            )
            BubbleHealthLog.recordHideReason(context, BubbleHealthLog.HIDE_OVERLAY_PERMISSION_MISSING)
            return false
        }

        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager).also { windowManager = it }
            ?: return false

        val resultKind = try {
            wm.addView(view, lp)
            null
        } catch (e: IllegalStateException) {
            try {
                wm.removeViewImmediate(view)
                wm.addView(view, lp)
                null
            } catch (retryEx: Throwable) {
                "IllegalStateException:${retryEx.javaClass.simpleName}"
            }
        } catch (e: WindowManager.BadTokenException) {
            "BadTokenException"
        } catch (e: IllegalArgumentException) {
            "IllegalArgumentException"
        } catch (e: SecurityException) {
            "SecurityException"
        } catch (e: Throwable) {
            e.javaClass.simpleName
        }

        if (resultKind == null) {
            attachRetryAttempt = 0
            cancelPendingAttachRetry()
            Log.d(TAG, "Overlay window attached to WindowManager (reason=$reason)")
            view.post {
                if (view.isAttachedToWindow && view.visibility == View.VISIBLE) {
                    BubbleHealthLog.confirmBubbleVisible(context, "attached_visible:$reason")
                }
            }
            return true
        }

        Log.e(TAG, "addView failed (reason=$reason, kind=$resultKind)")
        BubbleHealthLog.updateOpportunityBlockingReason("add_failed:$resultKind")
        BubbleHealthLog.record(
            context = context,
            type = DiagnosticType.BUBBLE_ATTACH_FAILED,
            message = "reason=$reason kind=$resultKind attempt=$attachRetryAttempt"
        )
        scheduleNextAttachRetry(reason)
        return false
    }

    private fun scheduleNextAttachRetry(reason: String) {
        if (!attachRateLimiter.tryAcquire()) {
            Log.w(TAG, "Attach retry cap (6/min) reached; handing over to watchdog")
            FloatingNotificationManager.postRestoreNotification(context)
            return
        }
        if (attachRetryAttempt >= ATTACH_BACKOFF_MS.size) {
            FloatingNotificationManager.postRestoreNotification(context)
            return
        }
        val delayMs = ATTACH_BACKOFF_MS[attachRetryAttempt++]
        cancelPendingAttachRetry()
        val runnable = Runnable {
            pendingRetryRunnable = null
            attachWithRetry("retry_$reason")
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingAttachRetry() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun setViewVisibilityImmediate(view: View, visible: Boolean, reason: String? = null) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
        overlayBubbleVisible.value = visible
        setOverlayRendering(visible && overlayExpanded.value)
        if (!visible && reason != null) {
            lastHideReason = reason
        } else if (visible) {
            isConcealing = false
            lastHideReason = null
        }
    }

    /** Pause Compose frames whenever the window is hidden or collapsed. */
    private fun setOverlayRendering(active: Boolean) {
        val apply = {
            try {
                lifecycleOwner.setRendering(active)
            } catch (t: Throwable) {
                Log.w(TAG, "Overlay lifecycle change failed: ${t.javaClass.simpleName}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post { apply() }
    }

    fun resetInactivityTimer(keepShrunk: Boolean = false) {
        mainHandler.removeCallbacks(shrinkRunnable)
        if (!overlayRecording.value && !overlayExpanded.value) {
            if (!keepShrunk) {
                overlayShrunk.value = false
                mainHandler.postDelayed(shrinkRunnable, SHRINK_TIMEOUT_MS)
            } else {
                if (!overlayShrunk.value) {
                    mainHandler.postDelayed(shrinkRunnable, SHRINK_TIMEOUT_MS)
                }
            }
        }
    }

    fun expandToFullSize() {
        mainHandler.removeCallbacks(shrinkRunnable)
        overlayShrunk.value = false
        syncIdleBubbleState()
    }

    private fun applyShrink() {
        if (!overlayRecording.value && !overlayExpanded.value) {
            overlayShrunk.value = true
            syncIdleBubbleState()
        }
    }

    fun expandPanel() {
        val root = overlayView ?: return
        dragSnapHandler.cancel()
        dragSnapHandler.updateImeTopPx(FloatingBubbleManager.imeTopPx.value)
        isConcealing = false
        root.animate().cancel()
        root.alpha = 1f
        root.scaleX = 1f
        root.scaleY = 1f
        overlayExpanded.value = true
        idleBubbleView?.visibility = View.GONE
        setViewVisibilityImmediate(root, true, null)
        ensureExpandedComposeViewAttached()
        setOverlayRendering(true)

        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density

        val isCompact = FloatingBubbleManager.bubbleChrome.value == BubbleChromeStyle.COMPACT
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
        val estHeightPx: Int
        if (isCompact) {
            val windowWidth = (BubbleChromeGeometry.RECORDING_WINDOW_WIDTH_DP * density).toInt()
            val windowHeight = (BubbleChromeGeometry.RECORDING_WINDOW_HEIGHT_DP * density).toInt()
            lp.width = windowWidth
            lp.height = windowHeight
            lp.x = if (dragSnapHandler.isSnappedToRight) {
                (displayMetrics.widthPixels - windowWidth - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
            } else {
                (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
            }
            estHeightPx = windowHeight
        } else {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = 0
            estHeightPx = (190 * density).toInt()
        }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = OVERLAY_WINDOW_FLAGS
        lp.y = dragSnapHandler.resolveYForWindow(
            windowHeightPx = estHeightPx,
            isExpanded = true
        )

        if (!isAttached()) {
            attachWithRetry("expand")
        }
        if (isAttached()) {
            try {
                windowManager?.updateViewLayout(root, lp)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating window layout on expand", e)
            }
        }
    }

    fun collapsePanel() {
        val wasExpanded = overlayExpanded.value
        overlayExpanded.value = false
        disposeExpandedComposeView()
        idleBubbleView?.visibility = View.VISIBLE
        syncIdleBubbleState()
        setOverlayRendering(false)

        dragSnapHandler.updateImeTopPx(FloatingBubbleManager.imeTopPx.value)
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()

        val targetX = if (dragSnapHandler.isSnappedToRight) {
            (screenWidth - windowSize - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
        } else {
            (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
        }
        val targetY = dragSnapHandler.resolveYForWindow(
            windowHeightPx = windowSize,
            isExpanded = false
        )
        val layoutChanged = wasExpanded ||
            lp.width != windowSize ||
            lp.height != windowSize ||
            lp.x != targetX ||
            lp.y != targetY

        lp.width = windowSize
        lp.height = windowSize
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = OVERLAY_WINDOW_FLAGS
        lp.x = targetX
        lp.y = targetY

        if (layoutChanged) {
            try {
                if (isAttached()) {
                    windowManager?.updateViewLayout(overlayView, lp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating window layout on collapse", e)
            }
        }
        resetInactivityTimer()
    }

    fun showImmediately(trigger: String = "focus_or_restore") {
        fun apply() {
            val root = overlayView ?: return
            if (!FloatingBubbleManager.isKeyboardVisible.value &&
                A11yWatchdog.repairKind.value == A11yRepairKind.NONE &&
                !overlayRecording.value &&
                !overlayExpanded.value
            ) {
                return
            }
            val imeTop = FloatingBubbleManager.imeTopPx.value
            if (imeTop > 0) {
                dragSnapHandler.updateImeTopPx(imeTop)
                syncVerticalPositionForKeyboard(imeTop)
            }
            if (!root.isAttachedToWindow) {
                attachWithRetry(trigger)
            }
            revealBubble(root)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post { apply() }
        }
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean, isSessionActive: Boolean) {
        fun apply() {
            val root = overlayView ?: return
            val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
            if (isVisible || repairActive) {
                val imeTop = FloatingBubbleManager.imeTopPx.value
                dragSnapHandler.updateImeTopPx(imeTop)
                if (!root.isAttachedToWindow) {
                    attachWithRetry("keyboard_show")
                }
                if (!overlayRecording.value && !overlayExpanded.value) {
                    collapsePanel()
                } else if (imeTop > 0) {
                    syncVerticalPositionForKeyboard(imeTop)
                }
                expandToFullSize()
                revealBubble(root)
                resetInactivityTimer(keepShrunk = false)
            } else if (!isSessionActive) {
                collapsePanel()
                concealBubble(root, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            } else {
                setViewVisibilityImmediate(root, true, null)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post { apply() }
        }
    }

    private fun revealBubble(root: View) {
        isConcealing = false
        if (!root.isAttachedToWindow) {
            attachWithRetry("reveal")
        }
        if (root.visibility == View.VISIBLE && root.alpha >= 0.99f && root.scaleX >= 0.99f) {
            overlayBubbleVisible.value = true
            setOverlayRendering(overlayExpanded.value)
            return
        }
        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        setViewVisibilityImmediate(root, true, null)
        root.post {
            if (root.isAttachedToWindow && root.visibility == View.VISIBLE) {
                BubbleHealthLog.confirmBubbleVisible(context, "reveal_visible")
            }
        }
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(BUBBLE_ANIM_MS)
            .setStartDelay(0L)
            .start()
    }

    private fun concealBubble(root: View, hideReason: String) {
        if (root.visibility != View.VISIBLE || isConcealing) {
            overlayBubbleVisible.value = false
            setOverlayRendering(false)
            return
        }
        isConcealing = true
        root.animate().cancel()
        BubbleHealthLog.cancelShowOpportunity(context, hideReason)
        lastHideReason = hideReason
        root.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(BUBBLE_ANIM_MS)
            .setStartDelay(0L)
            .withEndAction {
                isConcealing = false
                setViewVisibilityImmediate(root, false, hideReason)
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
            }
            .start()
    }

    @Volatile
    private var isConcealing = false

    fun hideOverlayImmediately(reason: String) {
        mainHandler.post {
            val root = overlayView
            val wasVisibleOrPending = (root?.visibility == View.VISIBLE) ||
                isConcealing ||
                BubbleHealthLog.hasPendingOpportunity()
            isConcealing = false
            root?.animate()?.cancel()
            root?.let { setViewVisibilityImmediate(it, false, reason) }
            if (wasVisibleOrPending) {
                BubbleHealthLog.cancelShowOpportunity(context, reason)
            }
        }
    }

    fun restoreOverlayVisibility(isKeyboardOpen: Boolean) {
        mainHandler.post {
            val root = overlayView ?: return@post
            val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
            if (isKeyboardOpen || repairActive || overlayRecording.value || overlayExpanded.value) {
                revealBubble(root)
            } else {
                concealBubble(root, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            }
        }
    }

    fun updateOverlayVisibility(isSessionActive: Boolean) {
        val isKeyboardOpen = FloatingBubbleManager.isKeyboardVisible.value
        val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
        val root = overlayView ?: return
        if (!isSessionActive && !isKeyboardOpen && !repairActive) {
            setViewVisibilityImmediate(root, false, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            BubbleHealthLog.cancelShowOpportunity(context, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
        } else {
            if (!root.isAttachedToWindow) {
                attachWithRetry("update_visibility")
            }
            setViewVisibilityImmediate(root, true, null)
        }
    }

    fun onDestroy() {
        cancelPendingAttachRetry()
        overlayBubbleVisible.value = false
        overlayScope?.cancel()
        overlayScope = null
        mainHandler.removeCallbacks(shrinkRunnable)
        dragSnapHandler.cancel()

        disposeExpandedComposeView()
        idleBubbleView?.release()
        idleBubbleView = null
        overlayRoot = null

        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()

        if (overlayView != null) {
            try {
                windowManager?.removeViewImmediate(overlayView)
            } catch (_: Throwable) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlayView from WindowManager", e)
                }
            }
            overlayView = null
        }
    }
}

