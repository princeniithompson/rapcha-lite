package com.princeniithompson.rapcha.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.princeniithompson.rapcha.BuildConfig
import com.princeniithompson.rapcha.data.ApiUsageRepository
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.data.UserProfileRepository
import com.princeniithompson.rapcha.ui.DrawerSectionHeading
import com.princeniithompson.rapcha.ui.SegmentPosition
import com.princeniithompson.rapcha.ui.SegmentedNavRow
import com.princeniithompson.rapcha.ui.SegmentedPermissionRow
import com.princeniithompson.rapcha.ui.SettingsApiKeyCard
import com.princeniithompson.rapcha.ui.components.DailyUsageCard
import com.princeniithompson.rapcha.ui.components.UserAvatarBadge
import com.princeniithompson.rapcha.ui.components.elevatedCardBorder
import com.princeniithompson.rapcha.ui.components.elevatedCardSurfaceColor

/**
 * Full-screen Account page opened when tapping the Profile Card in the Hamburger Drawer.
 * Modeled after Wispr Flow's clean hero Account screen, using Rapcha's dynamic Light/Dark theme
 * and real lifetime dictation stats.
 */
@Composable
fun AccountScreen(
    apiKey: String,
    apiKeyConfigured: Boolean,
    signedInEmail: String,
    isProvisioning: Boolean,
    apiKeyErrorReason: String,
    totals: DictationStats.Totals,
    onBack: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onAutoGetWithGoogle: () -> Unit,
    onSyncProfileAndLimits: () -> Unit,
    onDisconnectAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile by UserProfileRepository.profile.collectAsState()
    val usage by ApiUsageRepository.usage.collectAsState()
    val effectiveEmail = signedInEmail.ifBlank { profile.email }
    val effectiveName = profile.displayName.ifBlank {
        effectiveEmail.substringBefore("@")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Rapcha Account" }
    }
    val formattedWords = remember(totals.words) { "%,d".format(totals.words) }
    val formattedWpm = remember(totals.wpm) { "%,d".format(totals.wpm) }
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("account_screen")
    ) {
        SubScreenTopBar(
            title = "Account",
            onBack = onBack,
            backTestTag = "account_back_button"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Hero Profile Card (centered Google photo + name + email + clean divided stats + compact sync)
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = elevatedCardSurfaceColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = elevatedCardBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_hero_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UserAvatarBadge(
                        displayName = effectiveName,
                        email = effectiveEmail,
                        avatarFilePath = profile.avatarFilePath,
                        size = 72.dp,
                        showBorder = false,
                        modifier = Modifier.testTag("account_avatar")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = effectiveName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account_display_name")
                    )

                    if (effectiveEmail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = effectiveEmail,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("account_email")
                        )
                    }

                    // Tier badge — shown ONLY when genuinely confirmed Paid
                    if (usage.isConfirmedPaid) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testTag("account_paid_badge")
                        ) {
                            Text(
                                text = usage.tierLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Two equal-width stat blocks with a thin 1dp vertical divider
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account_stats_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccountStatBlock(
                            value = formattedWords,
                            label = "Words",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("account_stat_words")
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        )
                        AccountStatBlock(
                            value = formattedWpm,
                            label = "WPM",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("account_stat_wpm")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onSyncProfileAndLimits,
                        enabled = !profile.isSyncing,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("account_sync_button")
                    ) {
                        if (profile.isSyncing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Syncing profile & limits...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sync profile & API limits",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (profile.lastSyncMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = profile.lastSyncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Gemini API key connection management
            Column {
                DrawerSectionHeading(title = "Connection")
                SettingsApiKeyCard(
                    apiKey = apiKey,
                    apiKeyConfigured = apiKeyConfigured,
                    signedInEmail = effectiveEmail,
                    isProvisioning = isProvisioning,
                    apiKeyErrorReason = apiKeyErrorReason,
                    onSaveApiKey = onSaveApiKey,
                    onAutoGetWithGoogle = onAutoGetWithGoogle
                )
            }

            // Sign out / clear saved account info (visual look unchanged; tap opens confirmation dialog)
            if (apiKeyConfigured || effectiveEmail.isNotBlank()) {
                Surface(
                    onClick = { showSignOutDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    color = elevatedCardSurfaceColor(),
                    contentColor = MaterialTheme.colorScheme.error,
                    border = elevatedCardBorder(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("account_sign_out_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Sign out & clear API key",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { /* Strictly non-dismissible; only Cancel or Sign out may close. */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Sign out",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "This will sign you out and remove your Gemini API key from this device. You will need to add it again to use the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showSignOutDialog = false },
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("account_sign_out_cancel_button")
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Button(
                        onClick = {
                            showSignOutDialog = false
                            onDisconnectAccount()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("account_sign_out_confirm_button")
                    ) {
                        Text(
                            text = "Sign out",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            },
            modifier = Modifier.testTag("account_sign_out_dialog")
        )
    }
}

/**
 * Dedicated full-screen Daily Usage page opened when tapping the standalone usage lines
 * in the Hamburger Drawer (or the Daily usage row in Settings).
 * Single source of truth for used/remaining counts, local reset schedule, and manual sync.
 */
@Composable
fun UsageDetailsScreen(
    onBack: () -> Unit,
    onSyncProfileAndLimits: () -> Unit,
    modifier: Modifier = Modifier
) {
    val usage by ApiUsageRepository.usage.collectAsState()
    val profile by UserProfileRepository.profile.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("usage_details_screen")
    ) {
        SubScreenTopBar(
            title = "Daily usage",
            onBack = onBack,
            backTestTag = "usage_details_back_button"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            DailyUsageCard(
                snapshot = usage,
                isSyncing = profile.isSyncing,
                onSyncClick = onSyncProfileAndLimits
            )

            if (profile.lastSyncMessage.isNotBlank()) {
                Text(
                    text = profile.lastSyncMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Dedicated full-screen Settings page opened from the Hamburger Drawer's `Settings` row.
 * Houses a shortcut to Daily Usage details, system permissions, Keep Rapcha Awake, and
 * Welcome tour replay — keeping the main Hamburger Drawer clean and uncluttered.
 */
@Composable
fun AppSettingsScreen(
    hasOverlayPermission: Boolean,
    isAccessibilityConnected: Boolean,
    keepAwakeNeedsFix: Boolean,
    onBack: () -> Unit,
    onGrantOverlay: () -> Unit,
    onTurnOnAccessibility: () -> Unit,
    onOpenKeepAwake: () -> Unit,
    onOpenWelcomeTour: () -> Unit,
    onSyncProfileAndLimits: () -> Unit,
    onOpenUsageDetails: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLargeFont = LocalDensity.current.fontScale >= 1.55f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("app_settings_screen")
    ) {
        SubScreenTopBar(
            title = "Settings",
            onBack = onBack,
            backTestTag = "app_settings_back_button"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Section 1: Daily usage shortcut (opens the single source-of-truth UsageDetailsScreen)
            Column {
                DrawerSectionHeading(title = "Usage")
                SegmentedNavRow(
                    icon = Icons.Rounded.BarChart,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = "Daily usage",
                    subtitle = "View used count, remaining & reset time",
                    selected = false,
                    position = SegmentPosition.SINGLE,
                    isLargeFont = isLargeFont,
                    onClick = onOpenUsageDetails,
                    testTag = "settings_usage_row"
                )
            }

            // Section 2: Android system permissions & background protection
            Column {
                DrawerSectionHeading(title = "System access")
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    SegmentedPermissionRow(
                        icon = Icons.Rounded.Layers,
                        title = "Draw over other apps",
                        granted = hasOverlayPermission,
                        grantedText = "Permission granted",
                        missingText = "Required for the floating voice ring",
                        actionLabel = "Grant",
                        position = SegmentPosition.TOP,
                        isLargeFont = isLargeFont,
                        onAction = onGrantOverlay,
                        testTag = "settings_overlay_permission_row"
                    )
                    SegmentedPermissionRow(
                        icon = Icons.Rounded.AccessibilityNew,
                        title = "Keyboard & field detector",
                        granted = isAccessibilityConnected,
                        grantedText = "Service active",
                        missingText = "Required to detect the focused field and type",
                        actionLabel = "Turn on",
                        position = SegmentPosition.MIDDLE,
                        isLargeFont = isLargeFont,
                        onAction = onTurnOnAccessibility,
                        testTag = "settings_accessibility_permission_row"
                    )
                    SegmentedNavRow(
                        icon = Icons.Rounded.Shield,
                        iconContainerColor = if (keepAwakeNeedsFix) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        iconContentColor = if (keepAwakeNeedsFix) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        title = "Keep Rapcha awake",
                        subtitle = "Battery and background settings that keep the bubble alive",
                        badgeText = if (keepAwakeNeedsFix) "Needs fix" else "Ready",
                        badgeIcon = if (keepAwakeNeedsFix) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
                        badgeContainerColor = if (keepAwakeNeedsFix) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        badgeContentColor = if (keepAwakeNeedsFix) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        selected = false,
                        position = SegmentPosition.BOTTOM,
                        isLargeFont = isLargeFont,
                        onClick = onOpenKeepAwake,
                        testTag = "settings_keep_awake_row"
                    )
                }
            }

            // Section 3: Setup guide tucked neatly out of the main drawer
            Column {
                DrawerSectionHeading(title = "Setup")
                SegmentedNavRow(
                    icon = Icons.Rounded.Explore,
                    iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    title = "Welcome tour",
                    subtitle = "Replay the quick onboarding setup guide",
                    selected = false,
                    position = SegmentPosition.SINGLE,
                    isLargeFont = isLargeFont,
                    onClick = onOpenWelcomeTour,
                    testTag = "settings_welcome_tour_row"
                )
            }

            // Clean version footer
            Text(
                text = "APP VERSION ${BuildConfig.VERSION_NAME.uppercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("settings_app_version")
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    backTestTag: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .testTag(backTestTag)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun AccountStatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}
