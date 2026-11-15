package com.princeniithompson.rapcha.data

import android.content.Context
import java.io.File

/**
 * v25 lean: features that wrote to disk are gone (offline retry WAVs, saved recordings,
 * transcript history, diagnostics logs, crash file, vocab learning, app detection).
 * Their leftovers are deleted once, off the main thread, after the upgrade.
 */
object LegacyDataCleanup {
    private const val PREFS = "rapcha_lean_cleanup"
    private const val KEY_DONE = "v25_done"

    val FILES_DIR_TARGETS = listOf(
        "retry",
        "recordings",
        "diagnostics_history.jsonl",
        "diagnostics_history.1.jsonl",
        "diagnostics_notes.jsonl",
        "crash_pending.txt"
    )

    val PREFS_TARGETS = listOf(
        "voxstream_app_detection_logs",
        "history_repository_prefs",
        "voxstream_injection_logs",
        "rapcha_offline_retry",
        "vocab_suggestion_prefs",
        "rapcha_exit_reasons",
        "voxstream_learned_apps",
        "rapcha_polish_quota_monitor"
    )

    fun runOnce(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        Thread({
            try {
                deleteLeftovers(app.filesDir)
                for (name in PREFS_TARGETS) {
                    try {
                        app.deleteSharedPreferences(name)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            prefs.edit().putBoolean(KEY_DONE, true).apply()
        }, "rapcha-lean-cleanup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Deletes the legacy files and folders under [filesDir]. Returns how many were removed. */
    fun deleteLeftovers(filesDir: File?): Int {
        if (filesDir == null) return 0
        var removed = 0
        for (name in FILES_DIR_TARGETS) {
            val f = File(filesDir, name)
            if (f.exists() && f.deleteRecursively()) removed++
        }
        return removed
    }
}
