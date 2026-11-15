package com.princeniithompson.rapcha.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager

class BubbleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val trigger = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }

        val app = context.applicationContext ?: context
        AppLogRepository.init(app)

        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BubbleHealthLog.setPostUpdateGraceWindow(app, graceMs = 15_000L)
        }

        val enabled = FloatingBubbleManager.isBubbleEnabledSync(app)
        if (!enabled) {
            BubbleHealthLog.record(
                context = app,
                type = DiagnosticType.BOOT_RECONCILE,
                message = "trigger=$trigger a11y_enabled=${FloatingBubbleManager.isAccessibilityServiceEnabled(app)} outcome=skipped_disabled"
            )
            return
        }

        val a11yEnabled = FloatingBubbleManager.isAccessibilityServiceEnabled(app)
        val hasOverlay = FloatingBubbleManager.canDrawOverlays(app)

        val outcome = when {
            !a11yEnabled -> {
                FloatingNotificationManager.postAttentionNotification(
                    context = app,
                    body = "Accessibility service was turned off. Tap to re-enable.",
                    destination = FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE
                )
                "a11y_disabled"
            }
            !hasOverlay -> {
                FloatingNotificationManager.postRestoreNotification(app)
                "overlay_missing"
            }
            else -> {
                val started = FloatingBubbleManager.startBubbleService(app, source = trigger)
                if (!started) {
                    BubbleRecoveryJobScheduler.schedule(app, RecoveryJobPolicy.DEFAULT_DELAY_MS)
                    "refused_job_scheduled"
                } else {
                    BubbleHealthLog.recordLastRestartReason(app, "Reconciled after $trigger")
                    "started"
                }
            }
        }

        BubbleHealthLog.record(
            context = app,
            type = DiagnosticType.BOOT_RECONCILE,
            message = "trigger=$trigger a11y_enabled=$a11yEnabled overlay=$hasOverlay outcome=$outcome"
        )
    }
}
