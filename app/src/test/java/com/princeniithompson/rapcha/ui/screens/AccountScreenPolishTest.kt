package com.princeniithompson.rapcha.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountScreenPolishTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `account hero card polish holds in light mode with long name`() {
        verifyAccountHeroCardPolish(darkTheme = false)
    }

    @Test
    fun `account hero card polish holds in dark mode with long name`() {
        verifyAccountHeroCardPolish(darkTheme = true)
    }

    private fun verifyAccountHeroCardPolish(darkTheme: Boolean) {
        composeRule.setContent {
            MyApplicationTheme(darkTheme = darkTheme, dynamicColor = false) {
                Box(modifier = Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    AccountScreen(
                        apiKey = "AIzaSyTestKey1234567890ABCDEFGHIJKLMNOP",
                        apiKeyConfigured = true,
                        signedInEmail = "very.long.email.address.for.testing.ellipsis.behavior@example.com",
                        isProvisioning = false,
                        apiKeyErrorReason = "",
                        totals = DictationStats.Totals(words = 33790L, seconds = 13982L),
                        onBack = {},
                        onSaveApiKey = {},
                        onAutoGetWithGoogle = {},
                        onSyncProfileAndLimits = {},
                        onDisconnectAccount = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Avatar is 72dp
        val avatarBounds = composeRule.onNodeWithTag("account_avatar").getUnclippedBoundsInRoot()
        val avatarWidth = avatarBounds.right - avatarBounds.left
        assertEquals(72f, avatarWidth.value, 1.5f)

        // Long derived name stays strictly single-line (no multi-line wrapping)
        val nameBounds = composeRule.onNodeWithTag("account_display_name").getUnclippedBoundsInRoot()
        val nameHeight = nameBounds.bottom - nameBounds.top
        assertTrue("Expected single-line name height <= 36.dp, got $nameHeight", nameHeight <= 36.dp)

        // Divided stats row displays comma-formatted numbers + labels
        composeRule.onNodeWithTag("account_stats_row").assertIsDisplayed()
        composeRule.onNodeWithText("33,790").assertIsDisplayed()
        composeRule.onNodeWithText("Words").assertIsDisplayed()
        composeRule.onNodeWithText("145").assertIsDisplayed()
        composeRule.onNodeWithText("WPM").assertIsDisplayed()

        // Sync button is compact (not full width of hero card) and 40dp tall
        val cardBounds = composeRule.onNodeWithTag("account_hero_card").getUnclippedBoundsInRoot()
        val syncBounds = composeRule.onNodeWithTag("account_sync_button").getUnclippedBoundsInRoot()
        val cardInnerWidth = (cardBounds.right - cardBounds.left) - 40.dp
        val syncWidth = syncBounds.right - syncBounds.left
        val syncHeight = syncBounds.bottom - syncBounds.top
        assertTrue(
            "Expected compact sync button narrower than card ($syncWidth vs $cardInnerWidth)",
            syncWidth < cardInnerWidth
        )
        assertEquals(40f, syncHeight.value, 1.5f)
    }

    @Test
    fun `api key card shows visual badge without Connected pill or email when connected in light mode`() {
        verifyApiKeyCard(darkTheme = false, connected = true)
    }

    @Test
    fun `api key card shows visual badge without Connected pill or email when connected in dark mode`() {
        verifyApiKeyCard(darkTheme = true, connected = true)
    }

    @Test
    fun `api key card shows gray badge without check icon when disconnected in light mode`() {
        verifyApiKeyCard(darkTheme = false, connected = false)
    }

    @Test
    fun `api key card shows gray badge without check icon when disconnected in dark mode`() {
        verifyApiKeyCard(darkTheme = true, connected = false)
    }

    private fun verifyApiKeyCard(darkTheme: Boolean, connected: Boolean) {
        composeRule.setContent {
            MyApplicationTheme(darkTheme = darkTheme, dynamicColor = false) {
                Box(modifier = Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    AccountScreen(
                        apiKey = if (connected) "AIzaSyTestKey1234567890ABCDEFGHIJKLMNOP" else "",
                        apiKeyConfigured = connected,
                        signedInEmail = "prince@example.com",
                        isProvisioning = false,
                        apiKeyErrorReason = "",
                        totals = DictationStats.Totals(words = 33790L, seconds = 13982L),
                        onBack = {},
                        onSaveApiKey = {},
                        onAutoGetWithGoogle = {},
                        onSyncProfileAndLimits = {},
                        onDisconnectAccount = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Title is visible; Connected text pill is gone
        composeRule.onNodeWithText("Gemini API key").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()

        // Email appears only once (in the profile card, not repeated in the API key card)
        val emailNodes = composeRule.onAllNodesWithText("prince@example.com").fetchSemanticsNodes()
        assertEquals(1, emailNodes.size)

        // 20dp status badge is shown on the key icon box
        val badgeBounds = composeRule.onNodeWithTag("api_key_status_badge", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(20f, (badgeBounds.right - badgeBounds.left).value, 1.5f)
        assertEquals(20f, (badgeBounds.bottom - badgeBounds.top).value, 1.5f)

        if (connected) {
            composeRule.onNodeWithTag("api_key_status_check", useUnmergedTree = true).assertIsDisplayed()
            // Tap header to expand and verify API key field + Save key + Clear key remain intact
            composeRule.onNodeWithTag("settings_api_key_header").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings_api_key_input").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_save_api_key_button").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("settings_clear_api_key_button").performScrollTo().assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("api_key_status_check", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `log out row and dialog work in light mode`() {
        verifyLogOutRowAndDialog(darkTheme = false)
    }

    @Test
    fun `log out row and dialog work in dark mode`() {
        verifyLogOutRowAndDialog(darkTheme = true)
    }

    private fun verifyLogOutRowAndDialog(darkTheme: Boolean) {
        var disconnectCount = 0
        composeRule.setContent {
            MyApplicationTheme(darkTheme = darkTheme, dynamicColor = false) {
                Box(modifier = Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    AccountScreen(
                        apiKey = "AIzaSyTestKey1234567890ABCDEFGHIJKLMNOP",
                        apiKeyConfigured = true,
                        signedInEmail = "prince@example.com",
                        isProvisioning = false,
                        apiKeyErrorReason = "",
                        totals = DictationStats.Totals(words = 33790L, seconds = 13982L),
                        onBack = {},
                        onSaveApiKey = {},
                        onAutoGetWithGoogle = {},
                        onSyncProfileAndLimits = {},
                        onDisconnectAccount = { disconnectCount++ }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Row reads "Log out" and old label is gone
        composeRule.onNodeWithText("Sign out & clear API key").assertDoesNotExist()
        composeRule.onNodeWithText("Log out").assertIsDisplayed()

        // Tapping Log out opens confirmation dialog without signing out immediately
        composeRule.onNodeWithTag("account_sign_out_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, disconnectCount)
        composeRule.onNodeWithTag("account_sign_out_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Log out?").assertIsDisplayed()
        composeRule.onNodeWithText("Your API key will be removed.").assertIsDisplayed()

        // Warning icon is 40dp
        val iconBounds = composeRule.onNodeWithTag("account_sign_out_warning_icon", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(40f, (iconBounds.right - iconBounds.left).value, 1.5f)
        assertEquals(40f, (iconBounds.bottom - iconBounds.top).value, 1.5f)

        // Cancel and Log out buttons are side by side, 48dp tall, 12dp gap
        val cancelBounds = composeRule.onNodeWithTag("account_sign_out_cancel_button").getUnclippedBoundsInRoot()
        val confirmBounds = composeRule.onNodeWithTag("account_sign_out_confirm_button").getUnclippedBoundsInRoot()
        assertEquals(48f, (cancelBounds.bottom - cancelBounds.top).value, 1.5f)
        assertEquals(48f, (confirmBounds.bottom - confirmBounds.top).value, 1.5f)
        assertEquals(12f, (confirmBounds.left - cancelBounds.right).value, 1.5f)

        // Cancel closes dialog without calling onDisconnectAccount
        composeRule.onNodeWithTag("account_sign_out_cancel_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_dialog").assertDoesNotExist()
        assertEquals(0, disconnectCount)

        // Re-open and confirm Log out
        composeRule.onNodeWithTag("account_sign_out_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_confirm_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_dialog").assertDoesNotExist()
        assertEquals(1, disconnectCount)
        assertFalse(disconnectCount > 1)
    }
}
