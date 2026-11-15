package com.princeniithompson.rapcha.service

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.princeniithompson.rapcha.data.DiagnosticType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates communication between:
 * - The UI / Settings
 * - RapchaAccessibilityService (detects text field focus & injects text)
 * - FloatingBubbleService (renders the floating overlay & handles dictation)
 */
object FloatingBubbleManager {
    private const val TAG = "FloatingBubbleManager"
    private const val PREFS_NAME = "voxstream_bubble_prefs"
    /** Saved activeContainer: "words" (original bubble) or "mini" (compact pill). Same key as v24. */
    const val KEY_BUBBLE_CHROME = "bubble_chrome_style"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"

    private val _isBubbleEnabled = MutableStateFlow(false)
    val isBubbleEnabled: StateFlow<Boolean> = _isBubbleEnabled.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _hasOverlayPermission = MutableStateFlow(false)
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    private val _isFieldActive = MutableStateFlow(false)
    val isFieldActive: StateFlow<Boolean> = _isFieldActive.asStateFlow()

    private val _isKeyboardVisible = MutableStateFlow(false)
    val isKeyboardVisible: StateFlow<Boolean> = _isKeyboardVisible.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isNetworkProblem = MutableStateFlow(false)
    val isNetworkProblem: StateFlow<Boolean> = _isNetworkProblem.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _isClipboardFallbackIndicator = MutableStateFlow(false)
    val isClipboardFallbackIndicator: StateFlow<Boolean> = _isClipboardFallbackIndicator.asStateFlow()

    fun triggerClipboardFallbackIndicator() {
        _isClipboardFallbackIndicator.value = true
        mainHandler.postDelayed({
            _isClipboardFallbackIndicator.value = false
        }, 2500L)
    }

    fun resetClipboardFallbackIndicator() {
        _isClipboardFallbackIndicator.value = false
    }

    fun setNetworkProblem(hasProblem: Boolean) {
        _isNetworkProblem.value = hasProblem
    }

    private val _bubbleChrome = MutableStateFlow(com.princeniithompson.rapcha.service.floating.BubbleChromeStyle.ORIGINAL)
    /** The active floating bubble. Drives the live overlay and the Active chip on Home. */
    val bubbleChrome: StateFlow<com.princeniithompson.rapcha.service.floating.BubbleChromeStyle> = _bubbleChrome.asStateFlow()
    private var chromeLoaded = false

    /** Loads the saved bubble once per process. Called by the UI and by the bubble service. */
    fun loadBubbleChrome(context: Context) {
        if (chromeLoaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _bubbleChrome.value = com.princeniithompson.rapcha.service.floating.BubbleChromeStyle.fromStored(
            prefs.getString(KEY_BUBBLE_CHROME, null)
        )
        chromeLoaded = true
    }

    /** Only an explicit choice on Home calls this. Swiping or auto-scroll never does. */
    fun setBubbleChrome(context: Context, style: com.princeniithompson.rapcha.service.floating.BubbleChromeStyle) {
        chromeLoaded = true
        _bubbleChrome.value = style
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BUBBLE_CHROME, style.stored()).apply()
    }

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isBubbleEnabled.value = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
        loadBubbleChrome(context)
        initialized = true
        checkPermissions(context)

        if (shouldAutoStartBubbleFromInit(
                enabled = _isBubbleEnabled.value,
                canOverlay = canDrawOverlays(context),
                uiForeground = isUiForeground(context)
            )
        ) {
            startBubbleService(context, source = "init")
        } else if (_isBubbleEnabled.value &&
            canDrawOverlays(context) &&
            FloatingBubbleService.instance == null &&
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.lastStopReason(context) ==
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_UNEXPECTED
        ) {
            startBubbleService(context, source = "init_recover")
        }
    }

    /**
     * Background init must not call startForegroundService. The accessibility
     * service starts the bubble when it reconnects after an update.
     */
    internal fun shouldAutoStartBubbleFromInit(
        enabled: Boolean,
        canOverlay: Boolean,
        uiForeground: Boolean
    ): Boolean = enabled && canOverlay && uiForeground

    internal fun isUiForeground(context: Context): Boolean {
        if (context is Activity) return true
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val mine = activityManager.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
        return mine?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun isBubbleEnabledSync(context: Context): Boolean {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_BUBBLE_ENABLED, _isBubbleEnabled.value)
        } catch (_: Throwable) {
            _isBubbleEnabled.value
        }
    }

    fun startEnabledBubble(context: Context, source: String = "start_enabled") {
        if (!initialized) {
            init(context)
        }
        if (_isBubbleEnabled.value && canDrawOverlays(context)) {
            startBubbleService(context, source = source)
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val expectedClass = RapchaAccessibilityService::class.java.name
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.contains(context.packageName) && componentName.contains("AccessibilityService")) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    fun checkPermissions(context: Context) {
        val overlay = canDrawOverlays(context)
        _hasOverlayPermission.value = overlay
        val a11y = isAccessibilityServiceEnabled(context) || RapchaAccessibilityService.instance != null
        if (a11y) {
            _isAccessibilityConnected.value = true
        }
    }

    private val respawnThrottle = RespawnThrottle()

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        _isBubbleEnabled.value = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()

        if (enabled) {
            respawnThrottle.reset()
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.resetJobSuccessStreak(context)
            if (canDrawOverlays(context)) {
                startBubbleService(context, source = "user_enable")
            }
        } else {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                context,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
            BubbleRecoveryJobScheduler.cancel(context)
            com.princeniithompson.rapcha.service.floating.FloatingNotificationManager.cancelRestoreNotification(context)
            stopBubbleService(context)
        }
    }

    fun startBubbleService(context: Context, source: String = "manual", bypassThrottle: Boolean = false): Boolean {
        val app = context.applicationContext ?: context
        if (!canDrawOverlays(app)) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordHideReason(
                app,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_OVERLAY_PERMISSION_MISSING
            )
            return false
        }
        // v23 (Android 12+ background-start fix): when our accessibility service is connected,
        // bind the bubble service from it first. A bind from a running accessibility service is not
        // a background foreground-service start, so it cannot be refused, and the bubble then lives
        // as long as the accessibility binding. The foreground promotion below is a bonus.
        val bound = RapchaAccessibilityService.instance?.bindBubbleService(source) == true
        val uiForeground = isUiForeground(app)
        val decision = FgsStartupDecision.startRoute(
            sdkInt = Build.VERSION.SDK_INT,
            boundByAccessibility = bound,
            uiForeground = uiForeground,
            exemptSource = FgsStartupDecision.isExemptSource(source)
        )
        if (decision != FgsStartupDecision.StartRoute.START_FOREGROUND) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = app,
                type = DiagnosticType.FGS_FALLBACK,
                message = "source=$source route=$decision bound=$bound ui_foreground=$uiForeground"
            )
        }
        if (decision == FgsStartupDecision.StartRoute.BIND_ONLY) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(app, "Bound ($source)")
            return true
        }
        if (!bound && !bypassThrottle && FloatingBubbleService.instance == null && !respawnThrottle.tryAcquire()) {
            Log.d(TAG, "Throttled duplicate startBubbleService(source=$source)")
            return false
        }
        return try {
            val intent = Intent(app, FloatingBubbleService::class.java).apply {
                putExtra(FloatingBubbleService.EXTRA_START_SOURCE, source)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(app, "Started ($source)")
            true
        } catch (e: Throwable) {
            if (bound) {
                // The bubble is alive through the accessibility binding; nothing to recover.
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                    context = app,
                    type = DiagnosticType.FGS_FALLBACK,
                    message = "source=$source route=bound_unpromoted kind=${e.javaClass.simpleName}"
                )
                return true
            }
            val refusalClass = e.javaClass.simpleName
            val kind = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException -> "ForegroundServiceStartNotAllowedException"
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    e is android.app.BackgroundServiceStartNotAllowedException -> "BackgroundServiceStartNotAllowedException"
                e is IllegalStateException -> "IllegalStateException"
                e is SecurityException -> "SecurityException"
                else -> refusalClass
            }
            Log.e(TAG, "Failed to start FloatingBubbleService(source=$source, kind=$kind)", e)
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = app,
                type = DiagnosticType.FGS_REFUSED,
                message = "source=$source kind=$kind detail=${e.message?.take(140)}"
            )
            BubbleRecoveryJobScheduler.schedule(app, RecoveryJobPolicy.DEFAULT_DELAY_MS)
            com.princeniithompson.rapcha.service.floating.FloatingNotificationManager.postRestoreNotification(app)
            false
        }
    }

    fun stopBubbleService(context: Context) {
        try {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                context,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
            // v23: also drop the accessibility binding, otherwise stopService() leaves the
            // bound service (and the bubble) alive.
            RapchaAccessibilityService.instance?.unbindBubbleService("stop_bubble_service")
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingBubbleService", e)
        }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    @Volatile
    var bubbleSignalAtElapsed: Long = 0L
        private set

    @Volatile
    var bubbleHideSignalAtElapsed: Long = 0L
        private set


    fun noteBubbleHideSignal() {
        bubbleHideSignalAtElapsed = SystemClock.elapsedRealtime()
    }

    fun notifyFieldFocus(isFocused: Boolean) {
        val keyboardUp = _isKeyboardVisible.value
        if (isFocused && !_isFieldActive.value && keyboardUp) {
            bubbleSignalAtElapsed = SystemClock.elapsedRealtime()
        } else if (!isFocused && _isFieldActive.value && !keyboardUp) {
            noteBubbleHideSignal()
        }
        _isFieldActive.value = isFocused
        FloatingBubbleService.instance?.onFieldFocusChanged(isFocused)
    }

    fun notifyKeyboardVisibility(visible: Boolean) {
        if (visible && !_isKeyboardVisible.value) {
            bubbleSignalAtElapsed = SystemClock.elapsedRealtime()
        } else if (!visible && _isKeyboardVisible.value) {
            noteBubbleHideSignal()
        }
        _isKeyboardVisible.value = visible
        Log.d(TAG, "notifyKeyboardVisibility: $visible")
        FloatingBubbleService.instance?.onKeyboardVisibilityChanged(visible)
    }

    fun setRecordingState(recording: Boolean) {
        _isRecording.value = recording
    }

    /**
     * Injects text into the focused field. Copies to the clipboard only when no focused
     * editable field exists.
     */
    suspend fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        return com.princeniithompson.rapcha.service.floating.FloatingTextInjector.injectOrFallbackToClipboard(context, text)
    }
}
