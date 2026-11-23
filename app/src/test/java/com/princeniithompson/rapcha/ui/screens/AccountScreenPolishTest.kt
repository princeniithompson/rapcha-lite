package com.princeniithompson.rapcha.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun `sign out row opens confirmation dialog and only confirms on Sign out click`() {
        var disconnectCount = 0
        composeRule.setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
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
        composeRule.waitForIdle()

        // Tapping sign-out opens dialog without signing out immediately
        composeRule.onNodeWithTag("account_sign_out_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, disconnectCount)
        composeRule.onNodeWithTag("account_sign_out_dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This will sign you out and remove your Gemini API key from this device. You will need to add it again to use the app."
        ).assertIsDisplayed()

        // Cancel closes dialog without calling onDisconnectAccount
        composeRule.onNodeWithTag("account_sign_out_cancel_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_dialog").assertDoesNotExist()
        assertEquals(0, disconnectCount)

        // Re-open and confirm Sign out
        composeRule.onNodeWithTag("account_sign_out_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_confirm_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_sign_out_dialog").assertDoesNotExist()
        assertEquals(1, disconnectCount)
        assertFalse(disconnectCount > 1)
    }
}
