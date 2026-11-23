package com.princeniithompson.rapcha.ui.screens.onboarding

/**
 * Ordered stages of Rapcha onboarding.
 *
 * Permissions (Overlay, Keep Awake, Accessibility) run BEFORE the two interactive practice
 * screens (Voice Dictation Sandbox + Hold & Highlight Telescope Practice) so the silent
 * background Gemini API key provisioning gets a natural 20–40s head start and users try
 * the real Gemini Live + Gemini Flash-Lite engines during practice.
 */
enum class OnboardingStage(val progressIndex: Int, val showsProgressBar: Boolean) {
    WELCOME_HERO(progressIndex = -1, showsProgressBar = false),
    GOOGLE_SIGN_IN(progressIndex = -1, showsProgressBar = false),
    GOOGLE_AUTH_HANDOFF(progressIndex = -1, showsProgressBar = false),
    INTRO(progressIndex = 0, showsProgressBar = true),
    HOW_IT_WORKS(progressIndex = 1, showsProgressBar = true),
    APPS_SHOWCASE(progressIndex = 2, showsProgressBar = true),
    OVERLAY_PERMISSION(progressIndex = 3, showsProgressBar = true),
    KEEP_AWAKE(progressIndex = 4, showsProgressBar = true),
    ACCESSIBILITY_GUIDE(progressIndex = 5, showsProgressBar = true),
    SANDBOX(progressIndex = 6, showsProgressBar = true),
    TELESCOPE_PRACTICE(progressIndex = 7, showsProgressBar = true),
    NOTIFICATION_PERMISSION(progressIndex = 8, showsProgressBar = true),
    ALL_SET(progressIndex = 8, showsProgressBar = true);

    companion object {
        const val TOTAL_PROGRESS_SEGMENTS = 9
    }
}
