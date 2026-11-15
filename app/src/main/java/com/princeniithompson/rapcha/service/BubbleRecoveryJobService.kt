package com.princeniithompson.rapcha.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager

object BubbleRecoveryJobScheduler {
    private const val TAG = "BubbleRecoveryJob"

    fun schedule(context: Context, delayMs: Long = RecoveryJobPolicy.DEFAULT_DELAY_MS) {
        val app = context.applicationContext ?: context
        try {
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val latency = RecoveryJobPolicy.clampLatencyMs(delayMs)
            val deadline = RecoveryJobPolicy.deadlineMs(latency)
            val jobInfo = JobInfo.Builder(
                RecoveryJobPolicy.JOB_ID,
                ComponentName(app, BubbleRecoveryJobService::class.java)
            )
                .setMinimumLatency(latency)
                .setOverrideDeadline(deadline)
                .setPersisted(true)
                .build()
            scheduler.schedule(jobInfo)
            Log.d(TAG, "Scheduled recovery job latency=${latency}ms deadline=${deadline}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule BubbleRecoveryJobService", t)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext ?: context
        try {
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            scheduler.cancel(RecoveryJobPolicy.JOB_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cancel BubbleRecoveryJobService", t)
        }
    }

    fun scheduleIfMissing(context: Context, delayMs: Long = RecoveryJobPolicy.REPEAT_LATENCY_MS) {
        val app = context.applicationContext ?: context
        try {
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (scheduler.getPendingJob(RecoveryJobPolicy.JOB_ID) == null) {
                schedule(app, delayMs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed checking pending recovery job", t)
        }
    }
}

class BubbleRecoveryJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        AppLogRepository.init(app)

        val enabled = FloatingBubbleManager.isBubbleEnabledSync(app)
        val hasOverlay = FloatingBubbleManager.canDrawOverlays(app)
        val running = FloatingBubbleService.instance != null

        val outcome = when {
            !enabled -> "skipped_disabled"
            !hasOverlay -> {
                FloatingNotificationManager.postRestoreNotification(app)
                "skipped_no_overlay"
            }
            running -> {
                FloatingBubbleService.instance?.onRecoveryJobPing()
                BubbleHealthLog.recordJobOutcome(app, startedOrRunning = true)
                "already_running"
            }
            else -> {
                val started = FloatingBubbleManager.startBubbleService(app, source = "recovery_job")
                BubbleHealthLog.recordJobOutcome(app, startedOrRunning = started)
                if (started) "started" else "refused"
            }
        }

        val successes = BubbleHealthLog.getConsecutiveJobSuccesses(app)
        BubbleHealthLog.record(
            context = app,
            type = DiagnosticType.JOB_RECOVERY,
            message = "outcome=$outcome enabled=$enabled overlay=$hasOverlay running=$running consecutive_ok=$successes"
        )

        if (RecoveryJobPolicy.shouldReschedule(enabled, hasOverlay, successes)) {
            BubbleRecoveryJobScheduler.schedule(app, RecoveryJobPolicy.REPEAT_LATENCY_MS)
        } else if (!enabled) {
            BubbleRecoveryJobScheduler.cancel(app)
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
