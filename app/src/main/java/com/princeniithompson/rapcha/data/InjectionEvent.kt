package com.princeniithompson.rapcha.data

/**
 * Result of one injection attempt from RapchaAccessibilityService.injectTextDetailed.
 * v25: kept in memory only (the injection log repository was removed).
 */
data class InjectionEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val targetPackage: String = "Unknown",
    val targetAppName: String = "App",
    val targetNodeClass: String? = null,
    val isFocused: Boolean = false,
    val isEditable: Boolean = false,
    val windowId: Int = -1,
    val textLength: Int = 0,
    val wordCount: Int = 0,
    val injectionMethod: String = "DIRECT_ACTION_SET_TEXT", // "ACCESSIBILITY_INPUT_CONNECTION", "COMPANION_IME", "DIRECT_ACTION_SET_TEXT", "NO_TARGET_NODE"
    val resultDetails: String = "SUCCESS",
    val finalOutcome: String = "SUCCESS", // "SUCCESS" or "FAILED"
    val durationMs: Long = 0L,
    val rawTextPreview: String = ""
)
