package com.princeniithompson.rapcha.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.princeniithompson.rapcha.BuildConfig
import com.princeniithompson.rapcha.data.ApiUsageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DictationSessionOutcome(val shortLabel: String) {
    LIVE_OK("Live"),
    BATCH_FALLBACK("Offline"),
    EMERGENCY_INTERIM("Interim"),
    SILENT_SKIPPED("Silent"),
    EMPTY_RESULT("Empty")
}

enum class DictationFallbackReason(val shortLabel: String) {
    NONE_LIVE_OK("Live settled cleanly"),
    LIVE_TIMEOUT_WITH_PREVIEW("Live timeout (had preview)"),
    LIVE_TIMEOUT_NO_PREVIEW("Live timeout (no preview)"),
    SOCKET_RECONNECTING_AT_COMPLETE("Socket reconnecting"),
    SOCKET_NOT_READY_AT_COMPLETE("Socket not ready"),
    SPLIT_COLLISION_AT_COMPLETE("Piece split collision"),
    LIVE_DISCONNECTED_DURING_FINALIZE("Socket dropped on Complete"),
    LIVE_SILENCE_GATE_DROPPED("Silence gate mismatch"),
    LIVE_EMPTY_SERVER_RESPONSE("Empty live response"),
    NETWORK_OFFLINE_AT_COMPLETE("No internet"),
    NO_SPEECH_IN_BACKUP("Silent / no speech")
}

data class DictationTelemetryEvent(
    val timestampMs: Long,
    val outcome: DictationSessionOutcome,
    val reason: DictationFallbackReason,
    val audioSec: Int,
    val smartMode: Boolean,
    val tailMs: Long,
    val liveMs: Long,
    val liveTimeoutBudgetMs: Long,
    val encodeMs: Long,
    val batchMs: Long,
    val totalMs: Long,
    val hadPreview: Boolean,
    val socketState: String,
    val wsReconnects: Int,
    val rmsMean: Int,
    val rmsMax: Int,
    val batchMime: String,
    val batchBytes: Int,
    val batchHttpCode: Int,
    val batchAttempts: Int,
    val networkType: String,
    val wsQueueAtEndBytes: Long = 0L,
    val wsQueueAtDoneBytes: Long = 0L,
    val lastInterimAgeMs: Long = -1L,
    val postEndFrames: String = "none"
)

data class DictationTelemetrySnapshot(
    val totalSessions: Int = 0,
    val liveOkCount: Int = 0,
    val batchFallbackCount: Int = 0,
    val emergencyInterimCount: Int = 0,
    val silentSkippedCount: Int = 0,
    val emptyResultCount: Int = 0,
    val reasonCounts: Map<DictationFallbackReason, Int> = emptyMap(),
    val recentEvents: List<DictationTelemetryEvent> = emptyList(),
    val lastUpdatedMs: Long = 0L
)

/**
 * Release-safe, privacy-safe "black box flight recorder" for Rapcha dictation sessions.
 *
 * Persists strictly technical telemetry (timings, socket state, RMS stats, reason codes,
 * HTTP status codes) into SharedPreferences so R8 release stripping never removes it.
 * Dictated transcript text is NEVER recorded here.
 */
object DictationTelemetryRepository {
    private const val PREFS_NAME = "rapcha_dictation_telemetry_v1"
    private const val KEY_TOTAL = "total_sessions"
    private const val KEY_LIVE_OK = "live_ok_count"
    private const val KEY_BATCH_FALLBACK = "batch_fallback_count"
    private const val KEY_EMERGENCY_INTERIM = "emergency_interim_count"
    private const val KEY_SILENT_SKIPPED = "silent_skipped_count"
    private const val KEY_EMPTY_RESULT = "empty_result_count"
    private const val KEY_REASON_COUNTS = "reason_counts_json"
    private const val KEY_EVENTS = "events_ring_json"
    private const val KEY_LAST_UPDATED = "last_updated_ms"

    const val MAX_EVENTS = 150

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val _snapshot = MutableStateFlow(DictationTelemetrySnapshot())
    val snapshot: StateFlow<DictationTelemetrySnapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                loadLocked()
            }
        }
    }

    fun recordSession(context: Context?, event: DictationTelemetryEvent) {
        synchronized(lock) {
            if (prefs == null && context != null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                loadLocked()
            }
            val current = _snapshot.value
            val updatedReasons = current.reasonCounts.toMutableMap()
            if (event.outcome != DictationSessionOutcome.LIVE_OK &&
                event.reason != DictationFallbackReason.NONE_LIVE_OK
            ) {
                updatedReasons[event.reason] = (updatedReasons[event.reason] ?: 0) + 1
            }

            val updatedEvents = ArrayList<DictationTelemetryEvent>(minOf(current.recentEvents.size + 1, MAX_EVENTS))
            updatedEvents.add(event)
            val takeExisting = (MAX_EVENTS - 1).coerceAtLeast(0)
            if (current.recentEvents.isNotEmpty() && takeExisting > 0) {
                updatedEvents.addAll(current.recentEvents.take(takeExisting))
            }

            val next = DictationTelemetrySnapshot(
                totalSessions = current.totalSessions + 1,
                liveOkCount = current.liveOkCount + if (event.outcome == DictationSessionOutcome.LIVE_OK) 1 else 0,
                batchFallbackCount = current.batchFallbackCount + if (event.outcome == DictationSessionOutcome.BATCH_FALLBACK) 1 else 0,
                emergencyInterimCount = current.emergencyInterimCount + if (event.outcome == DictationSessionOutcome.EMERGENCY_INTERIM) 1 else 0,
                silentSkippedCount = current.silentSkippedCount + if (event.outcome == DictationSessionOutcome.SILENT_SKIPPED) 1 else 0,
                emptyResultCount = current.emptyResultCount + if (event.outcome == DictationSessionOutcome.EMPTY_RESULT) 1 else 0,
                reasonCounts = updatedReasons,
                recentEvents = updatedEvents,
                lastUpdatedMs = event.timestampMs
            )
            persistAndEmitLocked(next)
        }
    }

    fun clearAll(context: Context? = null) {
        synchronized(lock) {
            if (prefs == null && context != null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val empty = DictationTelemetrySnapshot()
            _snapshot.value = empty
            prefs?.edit()?.clear()?.apply()
            context?.let { cleanupExportCache(it) }
        }
    }

    fun buildReportText(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        init(context)
        ApiUsageRepository.init(context)
        val snap = _snapshot.value
        val usage = ApiUsageRepository.usage.value
        val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        return buildString {
            appendLine("=== RAPCHA DIAGNOSTIC REPORT ===")
            appendLine("Privacy: Technical telemetry only. Zero dictated text is recorded.")
            appendLine("Exported: ${tsFormat.format(Date(nowMs))}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine(
                "Quota snapshot: Offline=${usage.offlineUsed}/${if (usage.offlineUnlimited) "Unlimited" else usage.offlineLimit} " +
                    "| Modules=${usage.modulesUsed}/${if (usage.modulesUnlimited) "Unlimited" else usage.modulesLimit} " +
                    "| Tier=${usage.tierLabel}"
            )
            appendLine()

            appendLine("--- SUMMARY ---")
            appendLine("Total sessions recorded: ${snap.totalSessions}")
            appendLine("Live OK: ${snap.liveOkCount}")
            appendLine("Offline batch fallback: ${snap.batchFallbackCount}")
            appendLine("Emergency interim: ${snap.emergencyInterimCount}")
            appendLine("Silent skipped: ${snap.silentSkippedCount}")
            appendLine("Empty result: ${snap.emptyResultCount}")
            val nonSilent = (snap.totalSessions - snap.silentSkippedCount).coerceAtLeast(0)
            if (nonSilent > 0) {
                val fallbackPct = (snap.batchFallbackCount * 100.0) / nonSilent
                appendLine(String.format(Locale.US, "Offline fallback rate (non-silent): %.1f%% (%d/%d)", fallbackPct, snap.batchFallbackCount, nonSilent))
            }
            appendLine()

            appendLine("--- FALLBACK & NON-LIVE REASONS ---")
            if (snap.reasonCounts.isEmpty()) {
                appendLine("None recorded.")
            } else {
                snap.reasonCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (reason, count) ->
                        appendLine("  ${reason.name}: $count (${reason.shortLabel})")
                    }
            }
            appendLine()

            if (snap.recentEvents.isNotEmpty()) {
                val liveTimes = snap.recentEvents.filter { it.liveMs > 0 }.map { it.liveMs }.sorted()
                val batchTimes = snap.recentEvents.filter { it.batchMs > 0 }.map { it.batchMs }.sorted()
                val totalTimes = snap.recentEvents.filter { it.totalMs > 0 }.map { it.totalMs }.sorted()
                appendLine("--- TIMING STATS (LAST ${snap.recentEvents.size} EVENTS) ---")
                if (liveTimes.isNotEmpty()) {
                    appendLine("  live_ms: median=${median(liveTimes)}ms, max=${liveTimes.last()}ms")
                }
                if (batchTimes.isNotEmpty()) {
                    appendLine("  batch_ms: median=${median(batchTimes)}ms, max=${batchTimes.last()}ms")
                }
                if (totalTimes.isNotEmpty()) {
                    appendLine("  total_ms: median=${median(totalTimes)}ms, max=${totalTimes.last()}ms")
                }
                appendLine()
            }

            appendLine("--- EVENT RING BUFFER (NEWEST FIRST, MAX $MAX_EVENTS) ---")
            if (snap.recentEvents.isEmpty()) {
                appendLine("No dictation sessions recorded yet.")
            } else {
                snap.recentEvents.forEachIndexed { idx, e ->
                    val mode = if (e.smartMode) "SMART" else "RAW"
                    appendLine(
                        "[${idx + 1}] ${tsFormat.format(Date(e.timestampMs))} | ${e.outcome.name} | ${e.reason.name} | " +
                            "audio=${e.audioSec}s mode=$mode net=${e.networkType} sock=${e.socketState} reconn=${e.wsReconnects} preview=${if (e.hadPreview) 1 else 0} | " +
                            "tail=${e.tailMs}ms live=${e.liveMs}ms(budget=${e.liveTimeoutBudgetMs}ms) enc=${e.encodeMs}ms batch=${e.batchMs}ms total=${e.totalMs}ms | " +
                            "rms=${e.rmsMean}/${e.rmsMax} | ws_q=${e.wsQueueAtEndBytes}->${e.wsQueueAtDoneBytes}B interim_age=${e.lastInterimAgeMs}ms post_end=${e.postEndFrames} | " +
                            "batch_http=${e.batchHttpCode}(try=${e.batchAttempts}) mime=${e.batchMime} bytes=${e.batchBytes}"
                    )
                }
            }
        }
    }

    fun writeReportToCacheFile(context: Context, nowMs: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        cleanupExportCache(context)
        val fileTs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val file = File(dir, "rapcha_diagnostics_$fileTs.txt")
        file.writeText(buildReportText(context, nowMs), Charsets.UTF_8)
        return file
    }

    fun suggestedFileName(nowMs: Long = System.currentTimeMillis()): String {
        val fileTs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "rapcha_diagnostics_$fileTs.txt"
    }

    private fun cleanupExportCache(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "diagnostics")
            if (dir.exists()) {
                dir.listFiles()?.forEach { runCatching { it.delete() } }
            }
        }
    }

    private fun median(sorted: List<Long>): Long {
        if (sorted.isEmpty()) return 0L
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2L
    }

    private fun loadLocked() {
        val sp = prefs ?: return
        val reasonCounts = linkedMapOf<DictationFallbackReason, Int>()
        val rawReasons = sp.getString(KEY_REASON_COUNTS, null)
        if (!rawReasons.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(rawReasons)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val reason = runCatching { DictationFallbackReason.valueOf(key) }.getOrNull()
                    val count = obj.optInt(key, 0)
                    if (reason != null && count > 0) {
                        reasonCounts[reason] = count
                    }
                }
            }
        }

        val events = mutableListOf<DictationTelemetryEvent>()
        val rawEvents = sp.getString(KEY_EVENTS, null)
        if (!rawEvents.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(rawEvents)
                for (i in 0 until minOf(arr.length(), MAX_EVENTS)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val outcome = runCatching {
                        DictationSessionOutcome.valueOf(o.optString("outcome"))
                    }.getOrNull() ?: continue
                    val reason = runCatching {
                        DictationFallbackReason.valueOf(o.optString("reason"))
                    }.getOrNull() ?: DictationFallbackReason.NONE_LIVE_OK
                    events.add(
                        DictationTelemetryEvent(
                            timestampMs = o.optLong("ts", 0L),
                            outcome = outcome,
                            reason = reason,
                            audioSec = o.optInt("audioSec", 0),
                            smartMode = o.optBoolean("smart", true),
                            tailMs = o.optLong("tailMs", 0L),
                            liveMs = o.optLong("liveMs", 0L),
                            liveTimeoutBudgetMs = o.optLong("budgetMs", 0L),
                            encodeMs = o.optLong("encMs", 0L),
                            batchMs = o.optLong("batchMs", 0L),
                            totalMs = o.optLong("totalMs", 0L),
                            hadPreview = o.optBoolean("preview", false),
                            socketState = o.optString("sock", "unknown"),
                            wsReconnects = o.optInt("reconn", 0),
                            rmsMean = o.optInt("rmsMean", 0),
                            rmsMax = o.optInt("rmsMax", 0),
                            batchMime = o.optString("mime", "none"),
                            batchBytes = o.optInt("bytes", 0),
                            batchHttpCode = o.optInt("http", 0),
                            batchAttempts = o.optInt("tries", 0),
                            networkType = o.optString("net", "UNKNOWN"),
                            wsQueueAtEndBytes = o.optLong("wsQEnd", 0L),
                            wsQueueAtDoneBytes = o.optLong("wsQDone", 0L),
                            lastInterimAgeMs = o.optLong("interimAgeMs", -1L),
                            postEndFrames = o.optString("postEnd", "none")
                        )
                    )
                }
            }
        }

        _snapshot.value = DictationTelemetrySnapshot(
            totalSessions = sp.getInt(KEY_TOTAL, 0),
            liveOkCount = sp.getInt(KEY_LIVE_OK, 0),
            batchFallbackCount = sp.getInt(KEY_BATCH_FALLBACK, 0),
            emergencyInterimCount = sp.getInt(KEY_EMERGENCY_INTERIM, 0),
            silentSkippedCount = sp.getInt(KEY_SILENT_SKIPPED, 0),
            emptyResultCount = sp.getInt(KEY_EMPTY_RESULT, 0),
            reasonCounts = reasonCounts,
            recentEvents = events,
            lastUpdatedMs = sp.getLong(KEY_LAST_UPDATED, 0L)
        )
    }

    private fun persistAndEmitLocked(snapshot: DictationTelemetrySnapshot) {
        _snapshot.value = snapshot
        val sp = prefs ?: return
        val reasonObj = JSONObject()
        snapshot.reasonCounts.forEach { (k, v) -> reasonObj.put(k.name, v) }

        val eventsArr = JSONArray()
        snapshot.recentEvents.take(MAX_EVENTS).forEach { e ->
            eventsArr.put(
                JSONObject().apply {
                    put("ts", e.timestampMs)
                    put("outcome", e.outcome.name)
                    put("reason", e.reason.name)
                    put("audioSec", e.audioSec)
                    put("smart", e.smartMode)
                    put("tailMs", e.tailMs)
                    put("liveMs", e.liveMs)
                    put("budgetMs", e.liveTimeoutBudgetMs)
                    put("encMs", e.encodeMs)
                    put("batchMs", e.batchMs)
                    put("totalMs", e.totalMs)
                    put("preview", e.hadPreview)
                    put("sock", e.socketState)
                    put("reconn", e.wsReconnects)
                    put("rmsMean", e.rmsMean)
                    put("rmsMax", e.rmsMax)
                    put("mime", e.batchMime)
                    put("bytes", e.batchBytes)
                    put("http", e.batchHttpCode)
                    put("tries", e.batchAttempts)
                    put("net", e.networkType)
                    put("wsQEnd", e.wsQueueAtEndBytes)
                    put("wsQDone", e.wsQueueAtDoneBytes)
                    put("interimAgeMs", e.lastInterimAgeMs)
                    put("postEnd", e.postEndFrames)
                }
            )
        }

        sp.edit()
            .putInt(KEY_TOTAL, snapshot.totalSessions)
            .putInt(KEY_LIVE_OK, snapshot.liveOkCount)
            .putInt(KEY_BATCH_FALLBACK, snapshot.batchFallbackCount)
            .putInt(KEY_EMERGENCY_INTERIM, snapshot.emergencyInterimCount)
            .putInt(KEY_SILENT_SKIPPED, snapshot.silentSkippedCount)
            .putInt(KEY_EMPTY_RESULT, snapshot.emptyResultCount)
            .putString(KEY_REASON_COUNTS, reasonObj.toString())
            .putString(KEY_EVENTS, eventsArr.toString())
            .putLong(KEY_LAST_UPDATED, snapshot.lastUpdatedMs)
            .apply()
    }
}
