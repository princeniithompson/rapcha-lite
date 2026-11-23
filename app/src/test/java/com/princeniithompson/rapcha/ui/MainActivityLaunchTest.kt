package com.princeniithompson.rapcha.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.data.OnboardingRepository
import com.princeniithompson.rapcha.data.ThemeRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class MainActivityLaunchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_launchesInLightTheme_andTogglesDarkThemeCleanly() {
        // Verify MainActivity starts cleanly (including onboarding if fresh) and transitions to Home + Drawer.
        rule.runOnIdle {
            OnboardingRepository.completeOnboarding()
        }
        rule.waitForIdle()
        assertFalse(ThemeRepository.isDarkTheme.value)

        // Open the Hamburger menu drawer so SettingsContent (including FlowRow & Dark theme switch) renders.
        rule.onNodeWithContentDescription("Open Settings Menu").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("dark_theme_switch", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        rule.waitForIdle()

        assertTrue(ThemeRepository.isDarkTheme.value)
        rule.onNodeWithTag("dark_theme_switch", useUnmergedTree = true).assertIsOn()

        // Toggle back to Light theme to verify reverse MaterialKolor transition works cleanly too.
        rule.onNodeWithTag("dark_theme_switch", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(ThemeRepository.isDarkTheme.value)
        rule.onNodeWithTag("dark_theme_switch", useUnmergedTree = true).assertIsOff()
    }

    @Test
    fun dynamicColorScheme_preservesRichTintedBackgroundAndContrastingCardsInLightAndDark() {
        val context = rule.activity
        val light = com.princeniithompson.rapcha.ui.theme.getAppDynamicColorScheme(context, darkTheme = false)
        val dark = com.princeniithompson.rapcha.ui.theme.getAppDynamicColorScheme(context, darkTheme = true)

        // Light mode: crisp pure-white cards floating over a noticeably tinted background.
        org.junit.Assert.assertEquals(androidx.compose.ui.graphics.Color.White, light.surface)
        org.junit.Assert.assertEquals(androidx.compose.ui.graphics.Color.White, light.surfaceContainerLowest)
        org.junit.Assert.assertNotEquals(androidx.compose.ui.graphics.Color.White, light.background)

        // Dark mode: ultra-deep tinted background with visibly lighter lifted cards.
        assertTrue(dark.background != dark.surfaceContainer)
        assertTrue(dark.surfaceContainer.luminance() > dark.background.luminance())
    }

    @Test
    fun hamburgerDrawer_showsStandaloneUsageLines_andOpensUsageAccountAndSettingsScreens() {
        rule.runOnIdle {
            OnboardingRepository.completeOnboarding()
        }
        rule.waitForIdle()

        // Open Hamburger drawer
        rule.onNodeWithContentDescription("Open Settings Menu").performClick()
        rule.waitForIdle()

        // Verify Profile card & standalone containerless usage lines render cleanly
        rule.onNodeWithTag("drawer_profile_card").performScrollTo()
        rule.onNodeWithTag("drawer_usage_section").performScrollTo()
        rule.onNodeWithTag("usage_bar_modules", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("usage_bar_offline", useUnmergedTree = true).assertExists()

        // Tap standalone usage section -> opens UsageDetailsScreen with full counts & no Paid badge on Free tier
        rule.onNodeWithTag("drawer_usage_section").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("usage_details_screen").assertExists()
        rule.onNodeWithTag("usage_details_card").assertExists()
        rule.onNodeWithTag("usage_reset_time").assertExists()
        rule.onNodeWithTag("usage_paid_badge").assertDoesNotExist()
        rule.onNodeWithTag("usage_details_back_button").performClick()
        rule.waitForIdle()

        // Re-open Hamburger drawer and tap Profile Card -> opens AccountScreen
        rule.onNodeWithContentDescription("Open Settings Menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_profile_card").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("account_screen").assertExists()
        rule.onNodeWithTag("account_paid_badge").assertDoesNotExist()
        rule.onNodeWithTag("account_back_button").performClick()
        rule.waitForIdle()

        // Re-open Hamburger drawer and tap Settings -> opens AppSettingsScreen with Daily usage row
        rule.onNodeWithContentDescription("Open Settings Menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_settings_row").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("app_settings_screen").assertExists()
        rule.onNodeWithTag("settings_usage_row").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("usage_details_screen").assertExists()
        rule.onNodeWithTag("usage_details_back_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings_welcome_tour_row").performScrollTo()
        rule.onNodeWithTag("app_settings_back_button").performClick()
        rule.waitForIdle()
    }
}
