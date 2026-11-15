package com.princeniithompson.rapcha.service

import android.content.Context
import android.os.SystemClock
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class A11yRepairKind {
    NONE,
    REBINDING,
    PERMISSION_REVOKED
}

/**
 * Pure evaluator for Accessibility ghost / pause / revocation detection.
 */
object A11yWatchdogPolicy {
    const val WATCHDOG_INTERVAL_MS = 10_000L
    const val POST_UPDATE_GRACE_MS = 15_000L
    const val GHOST_THRESHOLD_MS = 15_000L
    const val REBIND_ESCALATION_MS = 60_000L

    fun evaluateA11yHealth(
        bubbleEnabled: Boolean,
        settingsEnabled: Boolean,
        serviceConnected: Boolean,
        inPostUpdateGrace: Boolean,
        unhealthySinceElapsedMs: Long,
        nowElapsedMs: Long
    ): A11yRepairKind {
        if (!bubbleEnabled) return A11yRepairKind.NONE
        if (!settingsEnabled) return A11yRepairKind.PERMISSION_REVOKED
        if (serviceConnected) return A11yRepairKind.NONE
        if (inPostUpdateGrace) return A11yRepairKind.NONE
        if (unhealthySinceElapsedMs <= 0L) return A11yRepairKind.NONE
        return if ((nowElapsedMs - unhealthySinceElapsedMs) >= GHOST_THRESHOLD_MS) {
            A11yRepairKind.REBINDING
        } else {
            A11yRepairKind.NONE
        }
    }
}

object A11yWatchdog {
    private val _repairKind = MutableStateFlow(A11yRepairKind.NONE)
    val repairKind: StateFlow<A11yRepairKind> = _repairKind.asStateFlow()

    @Volatile
    private var disconnectedSinceElapsedMs: Long = 0L

    @Volatile
    private var ghostDetectedAtElapsedMs: Long = 0L

    @Volatile
    private var notificationPostedForEpisode = false

    @Volatile
    private var escalatedForEpisode = false

    @Volatile
    private var lastReportedKind = A11yRepairKind.NONE

    fun onServiceBound(context: Context?) {
        val now = SystemClock.elapsedRealtime()
        val hadEpisode = lastReportedKind != A11yRepairKind.NONE || disconnectedSinceElapsedMs > 0L
        val durationMs = if (ghostDetectedAtElapsedMs > 0L) {
            (now - ghostDetectedAtElapsedMs).coerceAtLeast(0L)
        } else if (disconnectedSinceElapsedMs > 0L) {
            (now - disconnectedSinceElapsedMs).coerceAtLeast(0L)
        } else 0L

        disconnectedSinceElapsedMs = 0L
        ghostDetectedAtElapsedMs = 0L
        notificationPostedForEpisode = false
        escalatedForEpisode = false
        _repairKind.value = A11yRepairKind.NONE

        if (hadEpisode) {
            lastReportedKind = A11yRepairKind.NONE
            context?.let {
                FloatingNotificationManager.cancelAttentionNotification(it)
                BubbleHealthLog.record(
                    context = it,
                    type = DiagnosticType.A11Y_REBOUND,
                    message = "recovered_after_ms=$durationMs"
                )
            }
        }
    }

    fun onServiceUnbound(context: Context?) {
        if (disconnectedSinceElapsedMs == 0L) {
            disconnectedSinceElapsedMs = SystemClock.elapsedRealtime()
        }
        context?.let {
            BubbleHealthLog.record(
                context = it,
                type = DiagnosticType.A11Y_UNBOUND,
                message = "settings_enabled=${FloatingBubbleManager.isAccessibilityServiceEnabled(it)}"
            )
        }
    }

    fun check(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val bubbleEnabled = FloatingBubbleManager.isBubbleEnabledSync(context)
        val settingsEnabled = FloatingBubbleManager.isAccessibilityServiceEnabled(context)
        val connected = RapchaAccessibilityService.instance != null
        val inGrace = BubbleHealthLog.isInPostUpdateGraceWindow(context)

        if (connected && settingsEnabled) {
            onServiceBound(context)
            return
        }

        if (!connected && disconnectedSinceElapsedMs == 0L) {
            disconnectedSinceElapsedMs = now
        }

        val kind = A11yWatchdogPolicy.evaluateA11yHealth(
            bubbleEnabled = bubbleEnabled,
            settingsEnabled = settingsEnabled,
            serviceConnected = connected,
            inPostUpdateGrace = inGrace,
            unhealthySinceElapsedMs = disconnectedSinceElapsedMs,
            nowElapsedMs = now
        )
        _repairKind.value = kind

        if (kind == A11yRepairKind.NONE) {
            return
        }

        if (ghostDetectedAtElapsedMs == 0L) {
            ghostDetectedAtElapsedMs = now
        }
        val episodeAgeMs = (now - ghostDetectedAtElapsedMs).coerceAtLeast(0L)

        if (lastReportedKind != kind) {
            lastReportedKind = kind
            BubbleHealthLog.record(
                context = context,
                type = DiagnosticType.A11Y_GHOST,
                message = "kind=${kind.name} settings_enabled=$settingsEnabled in_grace=$inGrace"
            )
        }

        if (!notificationPostedForEpisode) {
            notificationPostedForEpisode = true
            val body = if (kind == A11yRepairKind.PERMISSION_REVOKED) {
                "Accessibility service is turned off. Tap to re-enable."
            } else {
                "Your device paused Rapcha's accessibility service. Tap to repair."
            }
            FloatingNotificationManager.postAttentionNotification(
                context = context,
                body = body,
                destination = FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE
            )
        } else if (!escalatedForEpisode && episodeAgeMs >= A11yWatchdogPolicy.REBIND_ESCALATION_MS) {
            escalatedForEpisode = true
            val body = "Rapcha's voice bubble is paused (over 1 minute). Tap to fix battery & accessibility settings."
            FloatingNotificationManager.postAttentionNotification(
                context = context,
                body = body,
                destination = FloatingNotificationManager.DEST_KEEP_AWAKE,
                urgent = true
            )
        }
    }
}
