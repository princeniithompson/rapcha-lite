package com.princeniithompson.rapcha.service

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.service.floating.DictationAlerts
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.service.floating.FloatingOverlayWindowManager
import com.princeniithompson.rapcha.service.floating.FloatingTextInjector
import com.princeniithompson.rapcha.service.floating.RapchaTextInserter
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinator service for the floating voice bubble.
 *
 * v25 lean path: bubble tap -> 16 kHz mic -> one SMART Gemini Live socket -> Complete ->
 * finishActivityAndAwaitTranscript -> FloatingTextInjector.injectReporting -> collapse.
 * No app detection, no polish stages, no Safe Mode, no retry clips, no history.
 */
class FloatingBubbleService : Service() {

    enum class RingTap { HIDE, CONTINUE }

    companion object {
        private const val TAG = "FloatingBubbleService"
        const val ACTION_SHOW = "com.princeniithompson.rapcha.action.SHOW_BUBBLE"
        const val ACTION_RESTORE = "com.princeniithompson.rapcha.action.RESTORE_BUBBLE"
        const val ACTION_REPAIR_A11Y = "com.princeniithompson.rapcha.action.REPAIR_A11Y"
        const val EXTRA_START_SOURCE = "start_source"

        var instance: FloatingBubbleService? = null
            private set

        @Suppress("UNUSED_PARAMETER")
        internal fun decideRingTap(
            recording: Boolean,
            keyboardVisible: Boolean,
            fieldFocused: Boolean
        ): RingTap {
            // A blinking cursor with no keyboard is the same as nothing focused: hide, don't start.
            return if (!recording && !keyboardVisible) RingTap.HIDE else RingTap.CONTINUE
        }

        /** Field focus alone does not start a session. The keyboard has to be on screen. */
        internal fun startsRecordingOnTap(
            recording: Boolean,
            keyboardVisible: Boolean,
            fieldFocused: Boolean
        ): Boolean {
            return !recording && keyboardVisible &&
                decideRingTap(recording, keyboardVisible, fieldFocused) == RingTap.CONTINUE
        }

        /** The field-focus path may reveal the bubble only when the IME window is already up. */
        internal fun fieldFocusRevealsBubble(keyboardVisible: Boolean): Boolean = keyboardVisible

        /**
         * The short keyboard re-check hides the bubble whenever the IME window is gone,
         * including when an editable field is still focused. An active session stays up.
         */
        internal fun keyboardRecheckShouldHide(imeVisible: Boolean, sessionActive: Boolean): Boolean {
            return !imeVisible && !sessionActive
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlayWindowManager: FloatingOverlayWindowManager
    private lateinit var sessionManager: FloatingDictationSessionManager

    @Volatile
    private var sessionLimitAutoFinish = false
    private var pendingCompletionTimeoutJob: Job? = null
    private var fgsDowngradeJob: Job? = null
    private var connectionLostAlertSent = false
    private var watchdogJob: Job? = null
    private var detachedRecoverTicks = 0
    private var watchdogState = VisibilityWatchdogDecision.WatchdogState()
    private var screenInteractive = true
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenInteractive = false
                Intent.ACTION_SCREEN_ON -> screenInteractive = true
            }
        }
    }

    /**
     * RapchaAccessibilityService binds here (BIND_AUTO_CREATE). A bound service created by
     * our running accessibility service is not a background foreground-service start, so Android
     * 12+ cannot refuse it, and the bubble lives as long as the accessibility binding.
     */
    inner class LocalBinder : android.os.Binder() {
        val service: FloatingBubbleService get() = this@FloatingBubbleService
    }

    private val localBinder = LocalBinder()

    /** True once startForeground succeeded at least once for this service instance. */
    @Volatile
    private var promotedToForeground = false

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FloatingBubbleManager.isBubbleEnabledSync(this) ||
            !FloatingBubbleManager.canDrawOverlays(this)
        ) {
            val reason = if (!FloatingBubbleManager.isBubbleEnabledSync(this)) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_USER_HIDDEN
            } else {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_OVERLAY_PERMISSION_MISSING
            }
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordHideReason(this, reason)
            // v23: a bound service survives stopSelf(); drop the accessibility binding too.
            RapchaAccessibilityService.instance?.unbindBubbleService("disabled_or_no_overlay")
            stopSelf()
            return START_NOT_STICKY
        }

        val currentlyRecording = ::sessionManager.isInitialized && sessionManager.isRecording
        if (currentlyRecording) {
            startMicrophoneForeground()
        } else if (!enterSpecialUseForeground()) {
            // v23: Android 12+ refused the foreground promotion (background start). The overlay
            // window does not need a foreground service while we hold SYSTEM_ALERT_WINDOW, so keep
            // running unpromoted and stay START_STICKY instead of stopping ourselves. The recovery
            // job retries the promotion later; the user can also tap the restore notification.
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
            if (!RapchaAccessibilityService.isBubbleBound()) {
                FloatingNotificationManager.postRestoreNotification(this)
            }
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.FGS_FALLBACK,
                message = "site=start_command action=continue_unpromoted bound=${RapchaAccessibilityService.isBubbleBound()}"
            )
        }
        BubbleRecoveryJobScheduler.scheduleIfMissing(this, RecoveryJobPolicy.REPEAT_LATENCY_MS)

        val startSource = intent?.getStringExtra(EXTRA_START_SOURCE)
        if (intent == null) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(
                this,
                "System START_STICKY restart"
            )
            rebuildOverlayIfNeeded("sticky_restart")
        } else {
            if (!startSource.isNullOrBlank()) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(
                    this,
                    "Started ($startSource)"
                )
            }
            when (intent.action) {
                ACTION_SHOW -> {
                    FloatingNotificationManager.cancelRestoreNotification(this)
                    rebuildOverlayIfNeeded("action_show")
                    if (FloatingBubbleManager.isKeyboardVisible.value || A11yWatchdog.repairKind.value != A11yRepairKind.NONE) {
                        overlayWindowManager.showImmediately("action_show")
                    }
                }
                ACTION_RESTORE -> {
                    FloatingNotificationManager.cancelRestoreNotification(this)
                    rebuildOverlayIfNeeded("action_restore")
                    // v23: no main-thread getWindows() binder call here; use the state the
                    // accessibility worker thread already computed, and ask it to re-evaluate.
                    val access = RapchaAccessibilityService.instance
                    access?.requestEvaluation()
                    val imeUp = FloatingBubbleManager.isKeyboardVisible.value ||
                        access?.isImeVisibleCached() == true
                    if (imeUp) {
                        FloatingBubbleManager.notifyKeyboardVisibility(true)
                    }
                    overlayWindowManager.restoreOverlayVisibility(
                        isKeyboardOpen = imeUp || A11yWatchdog.repairKind.value != A11yRepairKind.NONE
                    )
                }
                ACTION_REPAIR_A11Y -> {
                    openRepairDestination()
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.princeniithompson.rapcha.RapchaApplication.ensureBackgroundInit(this)
        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markServiceRunning(this, true)
        if (!enterSpecialUseForeground()) {
            // Keep running unpromoted (overlay works with SYSTEM_ALERT_WINDOW alone).
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
        }
        BubbleRecoveryJobScheduler.scheduleIfMissing(this, RecoveryJobPolicy.REPEAT_LATENCY_MS)
        serviceScope.launch(Dispatchers.IO) {
            try {
                CustomVocabularyRepository.init(this@FloatingBubbleService)
            } catch (t: Throwable) {
                Log.w(TAG, "Background service init failed: ${t.javaClass.simpleName}")
            }
        }

        FloatingBubbleManager.loadBubbleChrome(this)
        overlayWindowManager = FloatingOverlayWindowManager(this)
        sessionManager = FloatingDictationSessionManager(
            context = this,
            onTranscriptUpdated = { text ->
                // v26: the Words bubble shows the live transcript again (memory only).
                if (::overlayWindowManager.isInitialized) {
                    overlayWindowManager.overlayTranscript.value = text
                }
            },
            onInterimReceived = { _ -> },
            onFinalSegmentReceived = { _ -> },
            onConnectionStateChanged = { _: ConnectionState -> },
            onAmplitudeChanged = { amp ->
                if (::overlayWindowManager.isInitialized) {
                    overlayWindowManager.overlayAudioAmplitude.value = amp
                }
            },
            onError = { err -> onSessionError(err) },
            onDurationTicked = { seconds ->
                if (::overlayWindowManager.isInitialized) {
                    overlayWindowManager.overlayElapsedSeconds.value = seconds
                }
            },
            onSessionLimit = { _, finalizeNow ->
                serviceScope.launch(Dispatchers.Main) {
                    if (!finalizeNow) {
                        DictationAlerts.endingSoon(this@FloatingBubbleService)
                    } else if (sessionManager.isRecording) {
                        sessionLimitAutoFinish = true
                        onConfirmClicked()
                    }
                }
            },
            onPreviewPhase = { phase ->
                if (phase == InterimPreviewMonitor.Phase.CONNECTION_LOST) {
                    if (!connectionLostAlertSent) {
                        connectionLostAlertSent = true
                        DictationAlerts.connectionDropped(this@FloatingBubbleService)
                    }
                } else if (phase != InterimPreviewMonitor.Phase.PREVIEW_DELAYED) {
                    connectionLostAlertSent = false
                }
            }
        )

        try {
            initOverlayWindow()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing overlay window", e)
        }
        registerScreenReceiver()
        startWatchdogs()
    }

    private fun initOverlayWindow() {
        overlayWindowManager.initOverlay(
            onRingClick = { onRingClicked() },
            onCancelClick = { onCancelClicked() },
            onCompleteClick = { onConfirmClicked() },
            onRepairClick = { openRepairDestination() }
        )
    }

    private fun rebuildOverlayIfNeeded(reason: String) {
        if (!::overlayWindowManager.isInitialized) {
            overlayWindowManager = FloatingOverlayWindowManager(this)
        }
        if (overlayWindowManager.overlayView == null) {
            try {
                initOverlayWindow()
            } catch (e: Exception) {
                Log.e(TAG, "Failed rebuilding overlay window ($reason)", e)
            }
        } else if (!overlayWindowManager.isAttached()) {
            overlayWindowManager.attachWithRetry(reason)
        }
    }

    fun onRecoveryJobPing() {
        if (!FloatingBubbleManager.isBubbleEnabledSync(this) ||
            !FloatingBubbleManager.canDrawOverlays(this)
        ) {
            return
        }
        serviceScope.launch(Dispatchers.Main) {
            rebuildOverlayIfNeeded("recovery_job_ping")
            A11yWatchdog.check(this@FloatingBubbleService)
        }
    }

    private fun openRepairDestination() {
        val destination = if (A11yWatchdog.repairKind.value == A11yRepairKind.PERMISSION_REVOKED) {
            FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE
        } else {
            FloatingNotificationManager.DEST_KEEP_AWAKE
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FloatingNotificationManager.EXTRA_OPEN_DESTINATION, destination)
        }
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Falling back to system accessibility settings", e)
            com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager.openAccessibilitySettings(this)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (t: Throwable) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.WARNING,
                message = "bubble_screen_receiver_failed kind=${t.javaClass.simpleName}"
            )
        }
    }

    private fun startWatchdogs() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var tickCounter = 0
            while (isActive) {
                delay(VisibilityWatchdogDecision.WATCHDOG_INTERVAL_MS)
                tickCounter++

                if (::overlayWindowManager.isInitialized) {
                    val (nextState, action) = VisibilityWatchdogDecision.onTick(
                        state = watchdogState,
                        screenOn = screenInteractive,
                        bubbleEnabled = FloatingBubbleManager.isBubbleEnabledSync(this@FloatingBubbleService),
                        componentsInitialized = componentsReady(),
                        hasOverlayPermission = FloatingBubbleManager.canDrawOverlays(this@FloatingBubbleService),
                        shouldBeVisible = overlayWindowManager.shouldBeVisible(),
                        sessionActive = isBubbleSessionActive(),
                        isAttached = overlayWindowManager.isAttached(),
                        windowVisibilityVisible = overlayWindowManager.isWindowVisible(),
                        keyboardVisible = FloatingBubbleManager.isKeyboardVisible.value
                    )
                    watchdogState = nextState
                    if (action == VisibilityWatchdogDecision.TickAction.RECOVER) {
                        val recoverReason = if (!overlayWindowManager.isAttached()) {
                            "detached_while_needed"
                        } else {
                            "invisible_while_keyboard_up"
                        }
                        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                            context = this@FloatingBubbleService,
                            type = DiagnosticType.WATCHDOG_RECOVER,
                            message = "reason=$recoverReason session_active=${isBubbleSessionActive()}"
                        )
                        if (isBubbleSessionActive()) {
                            overlayWindowManager.attachWithRetry("watchdog_session")
                            overlayWindowManager.updateOverlayVisibility(isSessionActive = true)
                        } else {
                            if (overlayWindowManager.overlayView == null) {
                                initOverlayWindow()
                            } else if (!overlayWindowManager.isAttached()) {
                                overlayWindowManager.attachWithRetry("watchdog_recover")
                            }
                            // v23: rebuilding the whole Compose overlay is expensive on low-end
                            // phones. Only do it when the window has really been dead for more
                            // than two watchdog ticks in a row, not on every visibility mismatch.
                            if (!overlayWindowManager.isAttached()) {
                                detachedRecoverTicks++
                                if (detachedRecoverTicks > 2) {
                                    detachedRecoverTicks = 0
                                    overlayWindowManager.onDestroy()
                                    overlayWindowManager = FloatingOverlayWindowManager(this@FloatingBubbleService)
                                    initOverlayWindow()
                                }
                            } else {
                                detachedRecoverTicks = 0
                            }
                            overlayWindowManager.restoreOverlayVisibility(
                                FloatingBubbleManager.isKeyboardVisible.value ||
                                    A11yWatchdog.repairKind.value != A11yRepairKind.NONE
                            )
                        }
                    }
                }

                if (tickCounter % 2 == 0) {
                    A11yWatchdog.check(this@FloatingBubbleService)
                }
            }
        }
    }

    private fun componentsReady(): Boolean {
        return ::overlayWindowManager.isInitialized && ::sessionManager.isInitialized
    }

    private fun startForegroundForPlan(notification: android.app.Notification, plan: FgsStartupDecision.FgsPlan) {
        when (plan) {
            FgsStartupDecision.FgsPlan.LEGACY_NO_TYPE -> {
                startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
            }
            FgsStartupDecision.FgsPlan.TYPE_ZERO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification, 0)
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.MICROPHONE_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification, 0)
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.SPECIAL_USE_AND_MICROPHONE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
        }
    }

    /**
     * Must run before any other service work. A late or failed startForeground
     * is what produces ForegroundServiceDidNotStartInTime after an update.
     */
    private fun enterSpecialUseForeground(): Boolean {
        FloatingNotificationManager.createChannel(this)
        val notification = FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active")
        val plan = FgsStartupDecision.evaluatePlan(
            sdkInt = Build.VERSION.SDK_INT,
            isRecordingStarting = false,
            hasRecordAudioPermission = false,
            allowMicrophoneInCurrentState = false
        )
        return try {
            startForegroundForPlan(notification, plan)
            promotedToForeground = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to startForeground(plan=$plan)", e)
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.FGS_REFUSED,
                message = "site=special_use plan=$plan kind=${e.javaClass.simpleName} detail=${e.message?.take(140)}"
            )
            false
        }
    }

    private fun startMicrophoneForeground(): Boolean {
        val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val allowedState = MicrophoneForegroundPolicy.isAllowed(this)
        if (!hasMicPerm || !allowedState) {
            Log.w(TAG, "microphone startForeground skipped: perm=$hasMicPerm allowed=$allowedState")
            return false
        }
        val plan = FgsStartupDecision.evaluatePlan(
            sdkInt = Build.VERSION.SDK_INT,
            isRecordingStarting = true,
            hasRecordAudioPermission = hasMicPerm,
            allowMicrophoneInCurrentState = allowedState
        )
        val notification = FloatingNotificationManager.buildNotification(this, "Listening...")
        return try {
            startForegroundForPlan(notification, plan)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "microphone startForeground failed(plan=$plan)", e)
            val fallback = FgsStartupDecision.fallbackPlan(Build.VERSION.SDK_INT, plan)
            if (fallback != null) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                    context = this,
                    type = DiagnosticType.FGS_FALLBACK,
                    message = "from=$plan to=$fallback kind=${e.javaClass.simpleName} detail=${e.message?.take(120)}"
                )
                try {
                    val activeNotif = FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active")
                    startForegroundForPlan(activeNotif, fallback)
                } catch (fallbackEx: Throwable) {
                    com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                        context = this,
                        type = DiagnosticType.FGS_REFUSED,
                        message = "site=mic_fallback plan=$fallback kind=${fallbackEx.javaClass.simpleName}"
                    )
                }
            }
            false
        }
    }

    private fun onRingClicked() {
        val access = RapchaAccessibilityService.instance
        val keyboard = access?.isImeVisibleNow() == true
        val field = access?.hasFocusedEditableField() == true
        val recording = ::sessionManager.isInitialized && sessionManager.isRecording
        if (decideRingTap(recording, keyboard, field) == RingTap.HIDE) {
            FloatingBubbleManager.noteBubbleHideSignal()
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
            return
        }
        if (startsRecordingOnTap(recording, keyboard, field)) {
            if (overlayWindowManager.overlayShrunk.value || !overlayWindowManager.overlayExpanded.value) {
                overlayWindowManager.expandToFullSize()
            }
            if (!overlayWindowManager.overlayExpanded.value) {
                overlayWindowManager.expandPanel()
            }
            startVoiceTyping()
        }
    }

    private fun startVoiceTyping() {
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required. Please grant it in Rapcha.", Toast.LENGTH_LONG).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(appIntent)
            return
        }

        val apiKey = getEffectiveApiKey()
        if (ApiConfig.isPlaceholder(apiKey)) {
            Toast.makeText(this, "Gemini API key is required. Please set it in Rapcha app first.", Toast.LENGTH_LONG).show()
            if (::overlayWindowManager.isInitialized) {
                overlayWindowManager.collapsePanel()
            }
            return
        }

        if (!startMicrophoneForeground()) {
            DictationAlerts.microphoneFailed(this)
            if (::sessionManager.isInitialized && sessionManager.isRecording) {
                stopVoiceTyping(endedReason = "microphone_foreground_denied")
            }
            return
        }

        overlayWindowManager.expandToFullSize()
        FloatingBubbleManager.setRecordingState(true)
        overlayWindowManager.expandPanel()

        overlayWindowManager.overlayPendingFinalizing.value = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        overlayWindowManager.overlayRecording.value = true
        overlayWindowManager.overlayTranscript.value = ""
        overlayWindowManager.overlayAudioAmplitude.value = 0f

        val prefs = getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        val selectedModel = prefs.getString("selected_model", GeminiLiveWebSocketClient.DEFAULT_MODEL)
            ?: GeminiLiveWebSocketClient.DEFAULT_MODEL

        sessionManager.startSession(
            context = this,
            scope = serviceScope,
            apiKey = apiKey,
            model = selectedModel,
            smartMode = com.princeniithompson.rapcha.core.SmartModeSetting.isEnabled(this)
        )
    }

    private fun stopVoiceTyping(endedReason: String = "completed") {
        if (!sessionManager.isRecording) return
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false
        overlayWindowManager.resetInactivityTimer()

        sessionManager.stopSession(serviceScope, endedReason)
        downgradeMicrophoneForeground()
    }

    private fun downgradeMicrophoneForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fgsDowngradeJob?.cancel()
            fgsDowngradeJob = serviceScope.launch {
                delay(450L)
                dropMicrophoneForegroundType()
            }
        }
    }

    private fun dropMicrophoneForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            startForeground(
                FloatingNotificationManager.NOTIFICATION_ID,
                FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not revert FGS to specialUse: ${e.message}")
        }
    }

    /**
     * A fatal Live error while recording. Words already transcribed are still placed the
     * normal way; otherwise the session closes with a notification.
     */
    private fun onSessionError(err: String) {
        Log.w(TAG, "Session error: $err")
        if (!sessionManager.isRecording) return
        val partial = sessionManager.getFullTranscriptText()
        if (partial.isNotBlank()) {
            overlayWindowManager.overlayPendingFinalizing.value = true
            performInjectionAndClose(partial)
        } else {
            stopVoiceTyping(endedReason = "error")
            DictationAlerts.connectionDropped(this)
            resetAndCollapse()
        }
    }

    /** Complete: finish the SMART Live session, then inject. */
    private fun onConfirmClicked() {
        if (overlayWindowManager.overlayPendingFinalizing.value) return
        overlayWindowManager.overlayPendingFinalizing.value = true
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false

        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = serviceScope.launch {
            val textToInject = sessionManager.finishActivityAndAwaitTranscript()
            downgradeMicrophoneForeground()
            if (!isActive) return@launch
            performInjectionAndClose(textToInject)
        }
    }

    internal fun isBubbleSessionActive(): Boolean {
        if (!::sessionManager.isInitialized || !::overlayWindowManager.isInitialized) return false
        return sessionManager.isRecording ||
            overlayWindowManager.overlayPendingFinalizing.value ||
            false
    }

    private fun performInjectionAndClose(textOverride: String? = null) {
        val textToInject = textOverride?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sessionManager.getFullTranscriptText()
        if (sessionManager.isRecording) {
            stopVoiceTyping()
        }

        if (textToInject.isBlank()) {
            if (sessionManager.sessionBytesSent > 32_000L) {
                DictationAlerts.finalTextLost(this)
            }
            sessionLimitAutoFinish = false
            resetAndCollapse()
            return
        }

        val spokenSeconds = overlayWindowManager.overlayElapsedSeconds.value
        // Injection verifies off the main thread. Collapse only after it returns.
        serviceScope.launch {
            try {
                val placed = FloatingTextInjector.injectReporting(this@FloatingBubbleService, textToInject)
                // v26 Home: word/time counters and the memory-only recent list. Runs after
                // injection and can never change what was typed.
                if (placed !is RapchaTextInserter.InsertionResult.Failed) {
                    runCatching {
                        com.princeniithompson.rapcha.data.DictationStats.record(this@FloatingBubbleService, textToInject, spokenSeconds)
                        com.princeniithompson.rapcha.data.RecentDictations.add(textToInject)
                    }
                }
                if (sessionLimitAutoFinish) {
                    when (placed) {
                        is RapchaTextInserter.InsertionResult.SuccessClipboardFallback ->
                            DictationAlerts.sessionEnded(this@FloatingBubbleService, copied = true)
                        is RapchaTextInserter.InsertionResult.SuccessDirect ->
                            DictationAlerts.sessionEnded(this@FloatingBubbleService, copied = false)
                        is RapchaTextInserter.InsertionResult.Failed -> Unit
                    }
                }
                sessionLimitAutoFinish = false
            } finally {
                resetAndCollapse()
            }
        }
    }

    private fun onCancelClicked() {
        overlayWindowManager.overlayPendingFinalizing.value = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        if (sessionManager.isRecording) {
            stopVoiceTyping(endedReason = "user_cancelled")
        }
        Toast.makeText(this, "Voice typing cancelled", Toast.LENGTH_SHORT).show()
        resetAndCollapse()
    }

    private fun resetAndCollapse() {
        overlayWindowManager.overlayPendingFinalizing.value = false
        overlayWindowManager.overlayRecording.value = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        sessionManager.clearTranscripts()
        connectionLostAlertSent = false
        sessionLimitAutoFinish = false
        overlayWindowManager.collapsePanel()
        overlayWindowManager.updateOverlayVisibility(isSessionActive = false)
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean) {
        if (!componentsReady()) return
        overlayWindowManager.onKeyboardVisibilityChanged(isVisible, isBubbleSessionActive())
    }

    fun onFieldFocusChanged(isFocused: Boolean) {
        if (!componentsReady()) return
        if (isFocused) {
            if (fieldFocusRevealsBubble(FloatingBubbleManager.isKeyboardVisible.value)) {
                overlayWindowManager.showImmediately()
            }
            return
        }
        if (keyboardRecheckShouldHide(FloatingBubbleManager.isKeyboardVisible.value, isBubbleSessionActive())) {
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
        }
    }

    /**
     * Fired by the ~300ms keyboard re-check. A focused field is not a reason to stay visible.
     */
    fun onKeyboardWatch(imeVisible: Boolean) {
        if (!componentsReady()) return
        if (keyboardRecheckShouldHide(imeVisible, isBubbleSessionActive())) {
            FloatingBubbleManager.noteBubbleHideSignal()
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
        }
    }

    private fun getEffectiveApiKey(): String {
        return ApiConfig.resolveApiKey(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markUncleanShutdownPossible(this, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingBubbleService onDestroy")

        watchdogJob?.cancel()
        watchdogJob = null
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
            screenReceiverRegistered = false
        }

        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markServiceRunning(this, false)
        val stillEnabled = FloatingBubbleManager.isBubbleEnabledSync(this) &&
            FloatingBubbleManager.canDrawOverlays(this)
        if (stillEnabled) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_UNEXPECTED
            )
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordHideReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_SERVICE_DESTROYED
            )
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.MIN_LATENCY_MS)
        } else {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
        }

        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (::sessionManager.isInitialized) {
            stopVoiceTyping()
            sessionManager.release()
        }
        FloatingBubbleManager.setNetworkProblem(false)
        if (::overlayWindowManager.isInitialized) {
            overlayWindowManager.onDestroy()
        }
        serviceScope.cancel()

        if (instance == this) {
            instance = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val enabled = FloatingBubbleManager.isBubbleEnabledSync(this)
        val hasOverlay = FloatingBubbleManager.canDrawOverlays(this)
        val recording = ::sessionManager.isInitialized && sessionManager.isRecording
        Log.d(TAG, "FloatingBubbleService onTaskRemoved: enabled=$enabled recording=$recording")
        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
            context = this,
            type = DiagnosticType.TASK_REMOVED,
            message = "was_recording=$recording keep_alive=$enabled"
        )
        if (TaskRemovalPolicy.shouldReleaseSessionOnTaskRemoved(enabled)) {
            if (::sessionManager.isInitialized) {
                stopVoiceTyping()
                sessionManager.release()
            }
            FloatingBubbleManager.setNetworkProblem(false)
            return
        }
        if (!recording) {
            enterSpecialUseForeground()
        }
        if (TaskRemovalPolicy.shouldRebuildOverlayOnTaskRemoved(enabled, hasOverlay)) {
            rebuildOverlayIfNeeded("task_removed")
            serviceScope.launch(Dispatchers.Main) {
                delay(300L)
                if (FloatingBubbleManager.isBubbleEnabledSync(this@FloatingBubbleService) &&
                    FloatingBubbleManager.canDrawOverlays(this@FloatingBubbleService)
                ) {
                    rebuildOverlayIfNeeded("task_removed_settled")
                }
            }
        }
        BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
        if (!recording && hasOverlay) {
            FloatingBubbleManager.startBubbleService(this, source = "task_removed_keepalive")
        }
    }
}
