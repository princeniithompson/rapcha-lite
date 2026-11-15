package com.princeniithompson.rapcha.data

import android.content.Context

/**
 * v25 lean: logging is a no-op. Nothing is kept in memory and nothing is written to disk.
 * The method shapes stay so callers do not need to change; R8 also removes the calls
 * in release builds (see proguard-rules.pro).
 */
object AppLogRepository {
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun logEvent(source: DiagnosticSource, type: DiagnosticType, message: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun addLog(
        level: LogLevel,
        tag: String,
        message: String,
        payload: String? = null,
        source: DiagnosticSource = DiagnosticSource.APP
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun updateLiveStats(stats: LiveStats) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun updateLiveStats(transform: (LiveStats) -> LiveStats) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun updateConnectionState(state: ConnectionState, source: DiagnosticSource = DiagnosticSource.APP) = Unit
}
