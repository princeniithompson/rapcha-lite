package com.princeniithompson.rapcha.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sliding-window telemetry storm capper (Wispr BubbleVisibilityArbiter pattern).
 */
class BubbleHealthRateLimiter(
    private val capPerWindow: Int = STORM_CAP_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMs()
        while (timestamps.isNotEmpty() && now - timestamps.peekFirst() >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= capPerWindow) {
            return false
        }
        timestamps.addLast(now)
        return true
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }

    companion object {
        const val STORM_CAP_PER_WINDOW = 60
        const val WINDOW_MS = 60_000L
    }
}

/**
 * Thin diagnostic wrapper over [AppLogRepository] for bubble lifecycle, attach/hide reasons,
 * watchdog recovery, FGS refusals, accessibility health, and show-latency / miss tracking.
 */
object BubbleHealthLog {
    private const val TAG = "BubbleHealthLog"
    private const val PREFS_NAME = "rapcha_bubble_health"
    private const val KEY_STOP_REASON = "last_stop_reason"
    private const val KEY_RESTART_REASON = "last_restart_reason"
    private const val KEY_UNCLEAN_SHUTDOWN = "unclean_shutdown_possible"
    private const val KEY_SERVICE_RUNNING = "service_running_flag"
    private const val KEY_POST_UPDATE_GRACE_UNTIL_MS = "post_update_grace_until_ms"
    private const val KEY_CONSECUTIVE_JOB_SUCCESSES = "consecutive_job_successes"

    const val STOP_INTENTIONAL = "INTENTIONAL"
    const val STOP_UNEXPECTED = "UNEXPECTED"

    const val HIDE_NO_IME_AFTER_SETTLE = "no_ime_after_settle"
    const val HIDE_USER_HIDDEN = "user_hidden"
    const val HIDE_A11Y_LOST = "a11y_lost"
    const val HIDE_OVERLAY_PERMISSION_MISSING = "overlay_permission_missing"
    const val HIDE_SERVICE_DESTROYED = "service_destroyed"

    const val SHOW_OPPORTUNITY_DEADLINE_MS = 1_500L

    private val rateLimiter = BubbleHealthRateLimiter()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var opportunityT0Elapsed: Long = 0L

    @Volatile
    private var opportunityBlockingReason: String = "unknown"

    @Volatile
    private var opportunityTrigger: String = "unknown"

    private val opportunityTimeoutRunnable = Runnable {
        val t0 = opportunityT0Elapsed
        if (t0 <= 0L) return@Runnable
        val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
        opportunityT0Elapsed = 0L
        record(
            context = null,
            type = DiagnosticType.SHOW_MISSED,
            message = "trigger=$opportunityTrigger elapsed_ms=$elapsed blocking_reason=$opportunityBlockingReason"
        )
    }

    fun record(context: Context?, type: DiagnosticType, message: String) {
        if (!rateLimiter.tryAcquire()) {
            Log.w(TAG, "BubbleHealth storm cap reached; dropping $type")
            return
        }
        AppLogRepository.logEvent(DiagnosticSource.BUBBLE, type, message)
    }

    fun recordShowOpportunity(context: Context?, trigger: String, blockingReason: String = "pending_attach") {
        opportunityT0Elapsed = SystemClock.elapsedRealtime()
        opportunityTrigger = trigger
        opportunityBlockingReason = blockingReason
        mainHandler.removeCallbacks(opportunityTimeoutRunnable)
        mainHandler.postDelayed(opportunityTimeoutRunnable, SHOW_OPPORTUNITY_DEADLINE_MS)
    }

    fun updateOpportunityBlockingReason(reason: String) {
        if (opportunityT0Elapsed > 0L) {
            opportunityBlockingReason = reason
        }
    }

    fun hasPendingOpportunity(): Boolean = opportunityT0Elapsed > 0L

    fun confirmBubbleVisible(context: Context?, detail: String = "attached_visible") {
        mainHandler.removeCallbacks(opportunityTimeoutRunnable)
        val t0 = opportunityT0Elapsed
        opportunityT0Elapsed = 0L
        val latency = if (t0 > 0L) (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L) else 0L
        record(
            context = context,
            type = DiagnosticType.BUBBLE_ATTACHED,
            message = "show_latency_ms=$latency detail=$detail"
        )
    }

    fun cancelShowOpportunity(context: Context?, hideReason: String) {
        mainHandler.removeCallbacks(opportunityTimeoutRunnable)
        opportunityT0Elapsed = 0L
        recordHideReason(context, hideReason)
    }

    fun recordHideReason(context: Context?, reason: String) {
        record(context, DiagnosticType.BUBBLE_HIDE_REASON, "reason=$reason")
    }

    fun recordLastStopReason(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_STOP_REASON, reason).apply()
    }

    fun lastStopReason(context: Context): String? {
        return prefs(context).getString(KEY_STOP_REASON, null)
    }

    fun recordLastRestartReason(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_RESTART_REASON, reason).apply()
    }

    fun lastRestartReason(context: Context): String {
        return prefs(context).getString(KEY_RESTART_REASON, null) ?: "Normal start"
    }

    fun markUncleanShutdownPossible(context: Context, unclean: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNCLEAN_SHUTDOWN, unclean).apply()
    }

    fun consumeUncleanShutdownPossible(context: Context): Boolean {
        val p = prefs(context)
        val wasUnclean = p.getBoolean(KEY_UNCLEAN_SHUTDOWN, false)
        if (wasUnclean) {
            p.edit().putBoolean(KEY_UNCLEAN_SHUTDOWN, false).apply()
        }
        return wasUnclean
    }

    fun markServiceRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }

    fun isServiceRunningFlag(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SERVICE_RUNNING, false)
    }

    fun setPostUpdateGraceWindow(context: Context, graceMs: Long = 15_000L) {
        prefs(context).edit()
            .putLong(KEY_POST_UPDATE_GRACE_UNTIL_MS, System.currentTimeMillis() + graceMs)
            .apply()
    }

    fun isInPostUpdateGraceWindow(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_POST_UPDATE_GRACE_UNTIL_MS, 0L)
        return System.currentTimeMillis() < until
    }

    fun getConsecutiveJobSuccesses(context: Context): Int {
        return prefs(context).getInt(KEY_CONSECUTIVE_JOB_SUCCESSES, 0)
    }

    fun recordJobOutcome(context: Context, startedOrRunning: Boolean) {
        val p = prefs(context)
        val next = if (startedOrRunning) p.getInt(KEY_CONSECUTIVE_JOB_SUCCESSES, 0) + 1 else 0
        p.edit().putInt(KEY_CONSECUTIVE_JOB_SUCCESSES, next).apply()
    }

    fun resetJobSuccessStreak(context: Context) {
        prefs(context).edit().putInt(KEY_CONSECUTIVE_JOB_SUCCESSES, 0).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
