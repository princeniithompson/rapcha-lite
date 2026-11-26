package com.princeniithompson.rapcha.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import com.princeniithompson.rapcha.data.ThemeRepository
import com.princeniithompson.rapcha.data.UserProfileRepository
import com.princeniithompson.rapcha.ui.DrawerSectionHeading
import com.princeniithompson.rapcha.ui.ExpressiveSwitch
import com.princeniithompson.rapcha.ui.SettingsApiKeyCard
import com.princeniithompson.rapcha.ui.TonalIconWell
import com.princeniithompson.rapcha.ui.components.DailyUsageCard
import com.princeniithompson.rapcha.ui.components.UserAvatarBadge
import com.princeniithompson.rapcha.ui.components.elevatedCardBorder
import com.princeniithompson.rapcha.ui.components.elevatedCardSurfaceColor
import java.util.Locale

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

            // Log out / clear saved account info
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
                            text = "Log out",
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { /* Strictly non-dismissible; only Cancel or Log out may close. */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconContentColor = MaterialTheme.colorScheme.error,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("account_sign_out_warning_icon")
                )
            },
            title = {
                Text(
                    text = "Log out?",
                    fontSize = 22.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "Your API key will be removed.",
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showSignOutDialog = false },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("account_sign_out_cancel_button")
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("account_sign_out_confirm_button")
                    ) {
                        Text(
                            text = "Log out",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
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
 * Dedicated full-screen Settings page opened from the pinned bottom row in the Hamburger Drawer.
 * Houses Controls (Smart mode, Voice ring, Always on, Color mode), Access (Draw over apps,
 * Keyboard detector, Stay awake), and More (Usage, Tour) with visual-first rounded cards.
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
    onOpenDiagnostics: () -> Unit = {},
    isSmartMode: Boolean = true,
    onSmartModeToggle: (Boolean) -> Unit = {},
    isBubbleEnabled: Boolean = false,
    onBubbleToggle: (Boolean) -> Unit = {},
    isAlwaysOnScreen: Boolean = false,
    onAlwaysOnScreenToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkTheme by ThemeRepository.isDarkTheme.collectAsState()

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

            // Group 1: Controls
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroupHeading(title = "Controls")
                SettingsSwitchCardRow(
                    icon = Icons.Rounded.AutoAwesome,
                    label = "Smart mode",
                    checked = isSmartMode,
                    onCheckedChange = onSmartModeToggle,
                    rowTestTag = "smart_mode_row",
                    switchTestTag = "smart_mode_switch"
                )
                SettingsSwitchCardRow(
                    icon = Icons.Rounded.TripOrigin,
                    label = "Voice ring",
                    checked = isBubbleEnabled,
                    onCheckedChange = onBubbleToggle,
                    rowTestTag = "floating_bubble_row",
                    switchTestTag = "floating_bubble_switch"
                )
                SettingsSwitchCardRow(
                    icon = Icons.Rounded.PushPin,
                    label = "Always on",
                    checked = isAlwaysOnScreen,
                    onCheckedChange = onAlwaysOnScreenToggle,
                    rowTestTag = "always_on_screen_row",
                    switchTestTag = "always_on_screen_switch"
                )
                ColorModeCardRow(
                    isDarkTheme = isDarkTheme,
                    onSelectDarkTheme = { dark -> ThemeRepository.setDarkTheme(context, dark) }
                )
            }

            // Group 2: Access
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroupHeading(title = "Access")
                SettingsAccessCardRow(
                    icon = Icons.Rounded.Layers,
                    label = "Draw over apps",
                    isGood = hasOverlayPermission,
                    showChevron = false,
                    onClick = if (hasOverlayPermission) null else onGrantOverlay,
                    rowTestTag = "settings_overlay_permission_row",
                    statusTestTag = "settings_overlay_status_icon"
                )
                SettingsAccessCardRow(
                    icon = Icons.Rounded.AccessibilityNew,
                    label = "Keyboard detector",
                    isGood = isAccessibilityConnected,
                    showChevron = false,
                    onClick = if (isAccessibilityConnected) null else onTurnOnAccessibility,
                    rowTestTag = "settings_accessibility_permission_row",
                    statusTestTag = "settings_accessibility_status_icon"
                )
                SettingsAccessCardRow(
                    icon = Icons.Rounded.Shield,
                    label = "Stay awake",
                    isGood = !keepAwakeNeedsFix,
                    showChevron = true,
                    onClick = onOpenKeepAwake,
                    rowTestTag = "settings_keep_awake_row",
                    statusTestTag = "settings_keep_awake_status_icon"
                )
            }

            // Group 3: More
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsGroupHeading(title = "More")
                SettingsNavCardRow(
                    icon = Icons.Rounded.BarChart,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    label = "Usage",
                    onClick = onOpenUsageDetails,
                    testTag = "settings_usage_row"
                )
                SettingsNavCardRow(
                    icon = Icons.Rounded.MonitorHeart,
                    iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    label = "Diagnostics",
                    onClick = onOpenDiagnostics,
                    testTag = "settings_diagnostics_row"
                )
                SettingsNavCardRow(
                    icon = Icons.Rounded.Explore,
                    iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    label = "Tour",
                    onClick = onOpenWelcomeTour,
                    testTag = "settings_welcome_tour_row"
                )
            }

            // Version footer (normal lowercase, left-aligned, gray)
            Text(
                text = "v ${BuildConfig.VERSION_NAME.lowercase(Locale.US)}",
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("settings_app_version")
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroupHeading(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun SettingsSwitchCardRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    rowTestTag: String,
    switchTestTag: String
) {
    val iconBg by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = 1600f),
        label = "${rowTestTag}_icon_bg"
    )
    val iconFg by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = 1600f),
        label = "${rowTestTag}_icon_fg"
    )

    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(20.dp),
        color = elevatedCardSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(rowTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TonalIconWell(
                icon = icon,
                containerColor = iconBg,
                contentColor = iconFg
            )
            Text(
                text = label,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                testTag = switchTestTag
            )
        }
    }
}

@Composable
private fun ColorModeCardRow(
    isDarkTheme: Boolean,
    onSelectDarkTheme: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = elevatedCardSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("color_mode_row")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TonalIconWell(
                icon = Icons.Rounded.DarkMode,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "Color mode",
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhoneThemePreviewTile(
                    isDarkPreview = false,
                    isSelected = !isDarkTheme,
                    onClick = { onSelectDarkTheme(false) },
                    testTag = "color_mode_light_tile"
                )
                PhoneThemePreviewTile(
                    isDarkPreview = true,
                    isSelected = isDarkTheme,
                    onClick = { onSelectDarkTheme(true) },
                    testTag = "color_mode_dark_tile"
                )
            }
        }
    }
}

@Composable
private fun PhoneThemePreviewTile(
    isDarkPreview: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val tileShape = RoundedCornerShape(12.dp)
    val screenBg = if (isDarkPreview) Color(0xFF0F1923) else Color(0xFFEAF1F8)
    val cardBarColor = if (isDarkPreview) Color(0xFF243547) else Color.White
    val notchColor = if (isDarkPreview) Color(0xFF3E5369) else Color(0xFFB8C7D9)
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isSelected) 3.dp else 1.dp

    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 54.dp)
            .clip(tileShape)
            .background(screenBg)
            .border(width = borderWidth, color = borderColor, shape = tileShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isDarkPreview) "Dark mode" else "Light mode"
                role = Role.RadioButton
                selected = isSelected
            }
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mini phone status pill
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(notchColor)
            )
            // Mini hero card preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(cardBarColor)
            )
            // Mini secondary row preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(cardBarColor)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("${testTag}_check"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsAccessCardRow(
    icon: ImageVector,
    label: String,
    isGood: Boolean,
    showChevron: Boolean,
    onClick: (() -> Unit)?,
    rowTestTag: String,
    statusTestTag: String
) {
    val cardShape = RoundedCornerShape(20.dp)
    val surfaceModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .testTag(rowTestTag)

    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TonalIconWell(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = label,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccessStatusBadge(
                    isGood = isGood,
                    testTag = statusTestTag
                )
                if (showChevron) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = cardShape,
            color = elevatedCardSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = elevatedCardBorder(),
            modifier = surfaceModifier,
            content = content
        )
    } else {
        Surface(
            shape = cardShape,
            color = elevatedCardSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = elevatedCardBorder(),
            modifier = surfaceModifier,
            content = content
        )
    }
}

@Composable
private fun AccessStatusBadge(
    isGood: Boolean,
    testTag: String
) {
    val badgeColor = if (isGood) Color(0xFF16A34A) else Color(0xFFEA580C)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(badgeColor)
            .semantics {
                contentDescription = if (isGood) "Active" else "Needs action"
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isGood) Icons.Rounded.Check else Icons.Rounded.PriorityHigh,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SettingsNavCardRow(
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = elevatedCardSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TonalIconWell(
                icon = icon,
                containerColor = iconContainerColor,
                contentColor = iconContentColor
            )
            Text(
                text = label,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
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
