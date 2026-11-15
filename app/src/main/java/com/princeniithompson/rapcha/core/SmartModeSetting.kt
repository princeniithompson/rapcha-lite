package com.princeniithompson.rapcha.core

import android.content.Context

/**
 * Smart Mode (Smart polish) switch. ON (the default) asks gemini-3.5-transcribe-live for its
 * SMART transcription: punctuation, capitals and filler words cleaned up. OFF sends no mode, so
 * the raw verbatim transcript is typed.
 *
 * A new key is used on purpose: v24 stored "smart_mode" with a default of false, and the owner
 * wants Smart on by default after the update.
 */
object SmartModeSetting {
    const val PREFS = "voxstream_settings"
    const val KEY = "smart_polish_enabled"
    const val DEFAULT = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
