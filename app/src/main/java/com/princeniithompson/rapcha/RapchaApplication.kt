package com.princeniithompson.rapcha

import android.app.Application
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

class RapchaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        com.princeniithompson.rapcha.core.ApiKeyVault.init(this)
        ensureBackgroundInit(this)
        if (com.princeniithompson.rapcha.service.FloatingBubbleManager.isBubbleEnabledSync(this) &&
            com.princeniithompson.rapcha.service.FloatingBubbleService.instance == null &&
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.lastStopReason(this) ==
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_UNEXPECTED &&
            com.princeniithompson.rapcha.service.FloatingBubbleManager.canDrawOverlays(this)
        ) {
            // Application.onCreate is almost always a background start (the system is
            // re-creating the process for the accessibility service or a sticky restart), so a
            // startForegroundService here is refused on Android 12+. Ask the recovery job instead;
            // the accessibility service binds the bubble as soon as it reconnects.
            com.princeniithompson.rapcha.service.BubbleRecoveryJobScheduler.schedule(
                this,
                com.princeniithompson.rapcha.service.RecoveryJobPolicy.MIN_LATENCY_MS
            )
        }
    }

    companion object {
        private val backgroundInitStarted = AtomicBoolean(false)

        /**
         * Idempotent, process-wide startup. Safe to call from Application, services and the
         * activity. v25 lean: the only background step is the one-time cleanup of files left
         * by removed features.
         */
        fun ensureBackgroundInit(context: Context) {
            val app = context.applicationContext ?: context
            if (!backgroundInitStarted.compareAndSet(false, true)) return
            com.princeniithompson.rapcha.data.LegacyDataCleanup.runOnce(app)
        }
    }
}
