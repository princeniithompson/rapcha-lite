package com.princeniithompson.rapcha.service

import android.content.Context

/**
 * Packages that are never a dictation target: the system, input methods, and Rapcha itself.
 * Same rules as the old AppResolutionEngine.isSystemOrIme, kept byte-for-byte so the
 * injection target search (getActiveEditableNode) behaves exactly as before.
 */
object IgnoredPackages {
    @Suppress("UNUSED_PARAMETER")
    fun isIgnoredPackage(context: Context?, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val lower = packageName.lowercase()
        return lower == "android" ||
            lower == "com.android.systemui" ||
            lower.contains("systemui") ||
            lower.contains("inputmethod") ||
            lower.contains(".keyboard") ||
            lower.endsWith(".ime") ||
            lower.contains("honeyboard") ||
            lower.contains("swiftkey") ||
            lower.startsWith("com.princeniithompson.rapcha")
    }
}
