package com.princeniithompson.rapcha.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.data.RecentDictations
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.FloatingOverlayContent
import com.princeniithompson.rapcha.ui.screens.HomeScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingSandboxScreen
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v26: the restored v24 Home (both bubble pages with the Active marker), the onboarding voice
 * test (microphone step), and Settings with the Smart Mode switch, at font scale 1.0, 1.6 and
 * 2.0. Images go to /workspace/rapcha-v27-shots when run with -Proborazzi.test.record=true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi")
class FontScaleScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val shots = System.getProperty("rapcha.shots.dir") ?: "/workspace/rapcha-v27-shots"

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun settings(fontScale: Float, smart: Boolean = true, onToggle: (Boolean) -> Unit = {}) {
        show(fontScale) {
            var on by remember { mutableStateOf(smart) }
            SettingsContent(
                apiKey = "",
                apiKeyConfigured = true,
                signedInEmail = "",
                isProvisioning = false,
                vocabularyCount = 3,
                dictionarySelected = false,
                keepAwakeNeedsFix = true,
                isBubbleEnabled = true,
                hasOverlayPermission = true,
                isAccessibilityConnected = false,
                onSaveApiKey = {},
                onAutoGetWithGoogle = {},
                onOpenWelcomeTour = {},
                onOpenDictionary = {},
                onOpenKeepAwake = {},
                onBubbleToggle = {},
                onGrantOverlay = {},
                onTurnOnAccessibility = {},
                isSmartMode = on,
                onSmartModeToggle = { on = it; onToggle(it) }
            )
        }
    }

    private val sampleItems = listOf(
        RecentDictations.Item(1, "Running ten minutes late, start without me.", System.currentTimeMillis()),
        RecentDictations.Item(2, "Remember to send the invoice to Ama before Friday.", System.currentTimeMillis() - 60_000)
    )

    private fun home(
        fontScale: Float,
        active: BubbleChromeStyle,
        page: Int,
        items: List<RecentDictations.Item> = sampleItems,
        onChoose: (BubbleChromeStyle) -> Unit = {}
    ) {
        show(fontScale) {
            var chrome by remember { mutableStateOf(active) }
            HomeScreen(
                activeChrome = chrome,
                onChooseChrome = { chrome = it; onChoose(it) },
                totals = DictationStats.Totals(words = 1240, seconds = 600),
                recentItems = items,
                keepAwakeNeedsFix = false,
                onOpenMenu = {},
                onOpenKeepAwake = {},
                autoScroll = false,
                initialPage = page
            )
        }
    }

    // ---- Settings: Smart Mode switch ----

    private fun settingsShots(scale: String, fontScale: Float) {
        settings(fontScale)
        rule.onRoot().captureRoboImage("$shots/settings_font_$scale.png")
        rule.onNodeWithTag("smart_mode_row").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("smart_mode_switch").assertIsDisplayed().assertIsOn()
        rule.onRoot().captureRoboImage("$shots/settings_smart_toggle_font_$scale.png")
    }

    @Test fun settings_smart_font_1_0() = settingsShots("1.0", 1.0f)
    @Test fun settings_smart_font_1_6() = settingsShots("1.6", 1.6f)
    @Test fun settings_smart_font_2_0() = settingsShots("2.0", 2.0f)

    @Test
    fun smart_switch_defaults_on_and_turns_off() {
        var last: Boolean? = null
        settings(1.0f, smart = true) { last = it }
        rule.onNodeWithTag("smart_mode_switch").performScrollTo().assertIsOn().performClick()
        rule.onNodeWithTag("smart_mode_switch").assertIsOff()
        assertEquals(false, last)
        rule.onNodeWithTag("smart_mode_row").performClick()
        rule.onNodeWithTag("smart_mode_switch").assertIsOn()
        assertEquals(true, last)
    }

    // ---- Home: carousel with the two real bubbles ----

    private fun homeShots(scale: String, fontScale: Float) {
        // Page 1 (original Words bubble) while it is the active one.
        home(fontScale, BubbleChromeStyle.ORIGINAL, page = 0)
        rule.onNodeWithTag("bubble_active_words").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/home_words_bubble_active_font_$scale.png")
    }

    private fun homeMiniShots(scale: String, fontScale: Float) {
        home(fontScale, BubbleChromeStyle.COMPACT, page = 1)
        rule.onNodeWithTag("bubble_active_mini").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/home_mini_bubble_active_font_$scale.png")
    }

    @Test fun home_words_font_1_0() = homeShots("1.0", 1.0f)
    @Test fun home_words_font_1_6() = homeShots("1.6", 1.6f)
    @Test fun home_words_font_2_0() = homeShots("2.0", 2.0f)
    @Test fun home_mini_font_1_0() = homeMiniShots("1.0", 1.0f)
    @Test fun home_mini_font_1_6() = homeMiniShots("1.6", 1.6f)
    @Test fun home_mini_font_2_0() = homeMiniShots("2.0", 2.0f)

    @Test
    fun home_mini_page_not_active_shows_use_button_and_choosing_marks_it_active() {
        var chosen: BubbleChromeStyle? = null
        home(1.0f, BubbleChromeStyle.ORIGINAL, page = 1) { chosen = it }
        rule.onRoot().captureRoboImage("$shots/home_mini_bubble_choose_font_1.0.png")
        rule.onNodeWithTag("bubble_use_mini").assertIsDisplayed().performClick()
        assertEquals(BubbleChromeStyle.COMPACT, chosen)
        rule.onNodeWithTag("bubble_active_mini").assertIsDisplayed()
    }

    @Test
    fun home_tapping_bubble_preview_chooses_it() {
        var chosen: BubbleChromeStyle? = null
        home(1.0f, BubbleChromeStyle.COMPACT, page = 0) { chosen = it }
        rule.onNodeWithTag("bubble_preview_words").performClick()
        assertEquals(BubbleChromeStyle.ORIGINAL, chosen)
    }

    @Test
    fun home_stats_pages_font_1_6() {
        home(1.6f, BubbleChromeStyle.ORIGINAL, page = 2)
        rule.onNodeWithText("1,240", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/home_stats_words_font_1.6.png")
    }

    @Test
    fun home_empty_history_font_1_6() {
        home(1.6f, BubbleChromeStyle.ORIGINAL, page = 0, items = emptyList())
        rule.onNodeWithTag("home_empty_history").performScrollTo().assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/home_empty_font_1.6.png")
    }

    // ---- Onboarding voice test: microphone step ----

    private fun onboardingMicShots(scale: String, fontScale: Float) {
        show(fontScale) { OnboardingSandboxScreen(onComplete = {}) }
        rule.onNodeWithTag("sandbox_native_input").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$shots/onboarding_mic_step_font_$scale.png")
        rule.onNodeWithTag("onboarding_docked_bubble").assertIsDisplayed()
    }

    @Test fun onboarding_mic_font_1_0() = onboardingMicShots("1.0", 1.0f)
    @Test fun onboarding_mic_font_1_6() = onboardingMicShots("1.6", 1.6f)
    @Test fun onboarding_mic_font_2_0() = onboardingMicShots("2.0", 2.0f)

    @Test
    fun onboarding_bubble_tap_requests_record_audio() {
        show(1.0f) { OnboardingSandboxScreen(onComplete = {}) }
        rule.onNodeWithTag("sandbox_native_input").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("onboarding_docked_bubble").performClick()
        rule.waitForIdle()
        val request = shadowOf(rule.activity).lastRequestedPermission
        assertEquals(true, request?.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun onboarding_with_mic_granted_opens_the_real_bubble() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        show(1.6f) { OnboardingSandboxScreen(onComplete = {}) }
        rule.onNodeWithTag("sandbox_native_input").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("onboarding_docked_bubble").performClick()
        rule.waitForIdle()
        assertNull(shadowOf(rule.activity).lastRequestedPermission)
        rule.onNodeWithTag("onboarding_floating_container").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/onboarding_voice_test_open_font_1.6.png")
    }

    // ---- Live overlay ----

    @Test
    fun overlay_compact_pill_ignores_font_scale_2_0() {
        show(2.0f) {
            Box {
                FloatingOverlayContent(
                    recording = true,
                    pendingFinalizing = false,
                    isExpanded = true,
                    isShrunk = false,
                    isSnappedToRight = false,
                    elapsedSeconds = 84,
                    chrome = BubbleChromeStyle.COMPACT,
                    onRingClick = {},
                    onCancelClick = {},
                    onCompleteClick = {},
                    onLongPressBubble = {},
                    onDragStart = {},
                    onDrag = { _, _ -> },
                    onDragEnd = {}
                )
            }
        }
        rule.onNodeWithText("01:24").assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/overlay_mini_font_2.0.png")
    }

    @Test
    fun overlay_original_words_bubble_2_0() {
        show(2.0f) {
            Box {
                FloatingOverlayContent(
                    recording = true,
                    pendingFinalizing = false,
                    isExpanded = true,
                    isShrunk = false,
                    isSnappedToRight = false,
                    elapsedSeconds = 12,
                    transcript = "Hello this is the original bubble",
                    audioAmplitude = 0.6f,
                    chrome = BubbleChromeStyle.ORIGINAL,
                    onRingClick = {},
                    onCancelClick = {},
                    onCompleteClick = {},
                    onLongPressBubble = {},
                    onDragStart = {},
                    onDrag = { _, _ -> },
                    onDragEnd = {}
                )
            }
        }
        rule.onNodeWithText("Hello this is the original bubble", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("$shots/overlay_words_font_2.0.png")
    }
}
