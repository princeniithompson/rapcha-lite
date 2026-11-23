package com.princeniithompson.rapcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.data.ApiUsageRepository
import com.princeniithompson.rapcha.data.ThemeRepository
import com.princeniithompson.rapcha.data.UserProfileRepository
import com.princeniithompson.rapcha.ui.components.DrawerStandaloneUsageSection
import com.princeniithompson.rapcha.ui.components.UserAvatarBadge
import com.princeniithompson.rapcha.ui.components.elevatedCardBorder
import com.princeniithompson.rapcha.ui.components.elevatedCardSurfaceColor

internal enum class SegmentPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}

private fun segmentShape(position: SegmentPosition): Shape = when (position) {
    SegmentPosition.SINGLE -> RoundedCornerShape(20.dp)
    SegmentPosition.TOP -> RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 4.dp,
        bottomEnd = 4.dp
    )
    SegmentPosition.MIDDLE -> RoundedCornerShape(4.dp)
    SegmentPosition.BOTTOM -> RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 4.dp,
        bottomStart = 20.dp,
        bottomEnd = 20.dp
    )
}

private val SegmentedGap = 3.dp
private val SectionSpacing = 18.dp

/**
 * Premium Wispr-Flow-inspired Hamburger Drawer styled with Rapcha's dynamic Light/Dark theme.
 *
 * Hierarchy:
 * 1. Editorial brand bar ("Rapcha" + close)
 * 2. Tappable Google Profile Card (real photo + name + email + readiness pill -> AccountScreen)
 * 3. Daily API Usage Card (Modules & Offline push traffic-light bars + local reset time)
 * 4. Quick Controls (Smart mode, Floating voice ring, Always on screen, Dark theme)
 * 5. Navigation (Settings & Keep Rapcha awake)
 *
 * Stateless at the boundary so screenshot and font-scale tests can render it directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(
    apiKey: String,
    apiKeyConfigured: Boolean,
    signedInEmail: String,
    isProvisioning: Boolean,
    vocabularyCount: Int,
    dictionarySelected: Boolean,
    keepAwakeNeedsFix: Boolean,
    isBubbleEnabled: Boolean,
    hasOverlayPermission: Boolean,
    isAccessibilityConnected: Boolean,
    onSaveApiKey: (String) -> Unit,
    onAutoGetWithGoogle: () -> Unit,
    onOpenWelcomeTour: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenKeepAwake: () -> Unit,
    onBubbleToggle: (Boolean) -> Unit,
    onGrantOverlay: () -> Unit,
    onTurnOnAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
    isSmartMode: Boolean = true,
    onSmartModeToggle: (Boolean) -> Unit = {},
    isAlwaysOnScreen: Boolean = false,
    onAlwaysOnScreenToggle: (Boolean) -> Unit = {},
    apiKeyErrorReason: String = "",
    onCloseDrawer: (() -> Unit)? = null,
    onOpenAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSyncProfileAndLimits: () -> Unit = {},
    onOpenUsageDetails: () -> Unit = {}
) {
    val drawerContext = LocalContext.current
    val isLargeFont = LocalDensity.current.fontScale >= 1.55f
    val isDarkTheme by ThemeRepository.isDarkTheme.collectAsState()
    val profile by UserProfileRepository.profile.collectAsState()
    val usage by ApiUsageRepository.usage.collectAsState()

    val isApiHealthy = apiKeyConfigured && apiKeyErrorReason.isBlank()
    val permissionsNeedAttention = !hasOverlayPermission || !isAccessibilityConnected
    val allSystemsReady = isApiHealthy && !permissionsNeedAttention && !keepAwakeNeedsFix

    val effectiveEmail = signedInEmail.ifBlank { profile.email }
    val effectiveName = profile.displayName.ifBlank {
        effectiveEmail.substringBefore("@")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { if (isApiHealthy) "Rapcha Account" else "Connect Google account" }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .testTag("settings_content")
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top editorial brand header: Rapcha emblem + title + close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "Rapcha",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            if (onCloseDrawer != null) {
                FilledTonalIconButton(
                    onClick = onCloseDrawer,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = elevatedCardSurfaceColor(),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.testTag("settings_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close menu",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1. Tappable Google Profile Card (leads to AccountScreen)
        Surface(
            onClick = onOpenAccount,
            shape = RoundedCornerShape(22.dp),
            color = elevatedCardSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = elevatedCardBorder(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("drawer_profile_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UserAvatarBadge(
                        displayName = effectiveName,
                        email = effectiveEmail,
                        avatarFilePath = profile.avatarFilePath,
                        size = 48.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = effectiveName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (isLargeFont) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = effectiveEmail.ifBlank {
                                if (isApiHealthy) "Manage account & API key"
                                else "Tap to connect Google account"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isLargeFont) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Open account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill(
                        label = when {
                            isProvisioning -> "Connecting..."
                            allSystemsReady -> "Ready to dictate"
                            !isApiHealthy -> "Key needed"
                            else -> "Setup needs attention"
                        },
                        icon = if (allSystemsReady) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
                        containerColor = if (allSystemsReady) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColor = if (allSystemsReady) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }
        }

        // Compact inline setup banner only when API key is missing or broken
        if (!isApiHealthy) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                onClick = onOpenAccount,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_api_attention_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = apiKeyErrorReason.ifBlank {
                            "Connect your Google account or Gemini API key to start dictating."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Standalone containerless usage lines (tap either line to view full Daily Usage details)
        DrawerStandaloneUsageSection(
            snapshot = usage,
            onClick = onOpenUsageDetails
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Quick Controls (4 daily toggles)
        DrawerSectionHeading(title = "Quick controls")
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SegmentedGap)
        ) {
            SegmentedSwitchRow(
                icon = Icons.Rounded.AutoAwesome,
                title = "Smart mode",
                subtitle = if (isSmartMode) {
                    "Cleans up filler words and adds punctuation."
                } else {
                    "Raw verbatim transcript, fastest."
                },
                checked = isSmartMode,
                onCheckedChange = onSmartModeToggle,
                position = SegmentPosition.TOP,
                isLargeFont = isLargeFont,
                rowTestTag = "smart_mode_row",
                switchTestTag = "smart_mode_switch"
            )
            SegmentedSwitchRow(
                icon = Icons.Rounded.RecordVoiceOver,
                title = "Floating voice ring",
                subtitle = "Floats over other apps. Tap to dictate, hold to move.",
                checked = isBubbleEnabled,
                onCheckedChange = onBubbleToggle,
                position = SegmentPosition.MIDDLE,
                isLargeFont = isLargeFont,
                rowTestTag = "floating_bubble_row",
                switchTestTag = "floating_bubble_switch"
            )
            SegmentedSwitchRow(
                icon = Icons.Rounded.PushPin,
                title = "Always on screen",
                subtitle = "Keeps the ring docked even when the keyboard closes.",
                checked = isAlwaysOnScreen,
                onCheckedChange = onAlwaysOnScreenToggle,
                position = SegmentPosition.MIDDLE,
                isLargeFont = isLargeFont,
                rowTestTag = "always_on_screen_row",
                switchTestTag = "always_on_screen_switch"
            )
            SegmentedSwitchRow(
                icon = if (isDarkTheme) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                title = "Dark theme",
                subtitle = if (isDarkTheme) {
                    "Dark Material 3 surfaces matched to your wallpaper."
                } else {
                    "Light theme active. Turn on for dark colors."
                },
                checked = isDarkTheme,
                onCheckedChange = { ThemeRepository.setDarkTheme(drawerContext, it) },
                position = SegmentPosition.BOTTOM,
                isLargeFont = isLargeFont,
                rowTestTag = "dark_theme_row",
                switchTestTag = "dark_theme_switch"
            )
        }

        Spacer(modifier = Modifier.height(SectionSpacing))

        // 4. Clean 2-item Navigation (Settings + Keep Rapcha awake)
        DrawerSectionHeading(title = "More")
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SegmentedGap)
        ) {
            SegmentedNavRow(
                icon = Icons.Rounded.Settings,
                iconContainerColor = if (permissionsNeedAttention) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                iconContentColor = if (permissionsNeedAttention) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                title = "Settings",
                subtitle = "Permissions, usage details & welcome tour",
                badgeText = if (permissionsNeedAttention) "Action needed" else null,
                badgeIcon = if (permissionsNeedAttention) Icons.Rounded.ErrorOutline else null,
                badgeContainerColor = MaterialTheme.colorScheme.errorContainer,
                badgeContentColor = MaterialTheme.colorScheme.onErrorContainer,
                selected = false,
                position = SegmentPosition.TOP,
                isLargeFont = isLargeFont,
                onClick = onOpenSettings,
                testTag = "drawer_settings_row"
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
                subtitle = "Battery and background protection",
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
                testTag = "drawer_keep_awake_row"
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
internal fun DrawerSectionHeading(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun TonalIconWell(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        },
        modifier = modifier.testTag(testTag)
    )
}

@Composable
private fun SegmentedSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: SegmentPosition,
    isLargeFont: Boolean,
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
        label = "switch_icon_bg"
    )
    val iconFg by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = 1600f),
        label = "switch_icon_fg"
    )

    Surface(
        shape = segmentShape(position),
        color = elevatedCardSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(segmentShape(position))
            .clickable { onCheckedChange(!checked) }
            .testTag(rowTestTag)
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TonalIconWell(
                        icon = icon,
                        containerColor = iconBg,
                        contentColor = iconFg
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveSwitch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        testTag = switchTestTag
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TonalIconWell(
                    icon = icon,
                    containerColor = iconBg,
                    contentColor = iconFg
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ExpressiveSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    testTag = switchTestTag
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SegmentedNavRow(
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String,
    selected: Boolean,
    position: SegmentPosition,
    isLargeFont: Boolean,
    onClick: () -> Unit,
    testTag: String,
    badgeText: String? = null,
    badgeIcon: ImageVector? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val rowBg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        elevatedCardSurfaceColor()
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = segmentShape(position),
        color = rowBg,
        contentColor = titleColor,
        border = if (selected) null else elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(segmentShape(position))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TonalIconWell(
                        icon = icon,
                        containerColor = if (selected) MaterialTheme.colorScheme.surface else iconContainerColor,
                        contentColor = iconContentColor
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = subtitleColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (badgeText != null) {
                    StatusPill(
                        label = badgeText,
                        icon = badgeIcon,
                        containerColor = if (selected) MaterialTheme.colorScheme.surface else badgeContainerColor,
                        contentColor = badgeContentColor
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TonalIconWell(
                    icon = icon,
                    containerColor = if (selected) MaterialTheme.colorScheme.surface else iconContainerColor,
                    contentColor = iconContentColor
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        if (badgeText != null) {
                            StatusPill(
                                label = badgeText,
                                icon = badgeIcon,
                                containerColor = if (selected) MaterialTheme.colorScheme.surface else badgeContainerColor,
                                contentColor = badgeContentColor,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = subtitleColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun SegmentedPermissionRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    actionLabel: String,
    position: SegmentPosition,
    isLargeFont: Boolean,
    onAction: () -> Unit,
    testTag: String
) {
    val containerColor = if (granted) {
        elevatedCardSurfaceColor()
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val titleColor = if (granted) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val statusColor = if (granted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = segmentShape(position),
        color = containerColor,
        contentColor = titleColor,
        border = if (granted) elevatedCardBorder() else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TonalIconWell(
                    icon = icon,
                    containerColor = if (granted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (granted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = if (isLargeFont) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    if (!isLargeFont) {
                        PermissionStatusLabel(
                            granted = granted,
                            text = if (granted) grantedText else missingText,
                            color = statusColor
                        )
                    }
                }
            }

            if (isLargeFont) {
                PermissionStatusLabel(
                    granted = granted,
                    text = if (granted) grantedText else missingText,
                    color = statusColor
                )
            }

            if (!granted) {
                Button(
                    onClick = onAction,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusLabel(
    granted: Boolean,
    text: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = if (granted) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsApiKeyCard(
    apiKey: String,
    apiKeyConfigured: Boolean,
    signedInEmail: String,
    isProvisioning: Boolean,
    onSaveApiKey: (String) -> Unit,
    onAutoGetWithGoogle: () -> Unit,
    apiKeyErrorReason: String = ""
) {
    var draftKey by remember(apiKey) { mutableStateOf(apiKey) }
    var revealKey by remember { mutableStateOf(false) }
    val hasError = apiKeyErrorReason.isNotBlank()
    val isHealthy = apiKeyConfigured && !hasError

    // Stay calmly collapsed when already connected; auto-open whenever attention is needed
    var expanded by rememberSaveable { mutableStateOf(!isHealthy) }
    LaunchedEffect(isHealthy) {
        if (!isHealthy) {
            expanded = true
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "api_key_chevron"
    )

    val cardContainerColor = when {
        hasError -> MaterialTheme.colorScheme.errorContainer
        else -> elevatedCardSurfaceColor()
    }
    val cardTitleColor = when {
        hasError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val cardSubtextColor = when {
        hasError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = segmentShape(SegmentPosition.SINGLE),
        color = cardContainerColor,
        contentColor = cardTitleColor,
        border = if (hasError) null else elevatedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(segmentShape(SegmentPosition.SINGLE))
            .testTag("settings_api_key_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Tappable header row to expand or collapse key management
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("settings_api_key_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TonalIconWell(
                    icon = Icons.Rounded.Key,
                    containerColor = when {
                        hasError -> MaterialTheme.colorScheme.surface
                        isHealthy -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = when {
                        hasError -> MaterialTheme.colorScheme.error
                        isHealthy -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Gemini API key",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTitleColor,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        StatusPill(
                            label = when {
                                hasError -> "Key invalid"
                                isHealthy -> "Connected"
                                isProvisioning -> "Setting up..."
                                else -> "Required"
                            },
                            icon = when {
                                isHealthy -> Icons.Rounded.Check
                                isProvisioning -> null
                                else -> Icons.Rounded.ErrorOutline
                            },
                            containerColor = when {
                                isHealthy -> MaterialTheme.colorScheme.primaryContainer
                                isProvisioning -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                            contentColor = when {
                                isHealthy -> MaterialTheme.colorScheme.onPrimaryContainer
                                isProvisioning -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }

                    when {
                        hasError -> {
                            Text(
                                text = apiKeyErrorReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = cardSubtextColor
                            )
                        }
                        signedInEmail.isNotBlank() -> {
                            Text(
                                text = signedInEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = cardSubtextColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        !apiKeyConfigured -> {
                            Text(
                                text = "Required for voice typing",
                                style = MaterialTheme.typography.bodySmall,
                                color = cardSubtextColor
                            )
                        }
                        else -> {
                            Text(
                                text = if (expanded) "Hide key options" else "Tap to manage key",
                                style = MaterialTheme.typography.bodySmall,
                                color = cardSubtextColor
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse API key options" else "Expand API key options",
                    tint = cardSubtextColor,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 1600f)
                ) + expandVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                ),
                exit = fadeOut(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 1600f)
                ) + shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!apiKeyConfigured || hasError) {
                        Button(
                            onClick = onAutoGetWithGoogle,
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("settings_auto_get_key_button")
                        ) {
                            Text(
                                text = if (hasError) "Refresh via Google" else "Get via Google",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        label = { Text("API key") },
                        placeholder = { Text("AIzaSy... or AQ....") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        visualTransformation = if (revealKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(
                                    imageVector = if (revealKey) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = if (revealKey) "Hide API key" else "Show API key"
                                )
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_api_key_input")
                    )

                    // Stacked pill buttons guarantee zero wrapping or clipping up to 200% font scale
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!apiKeyConfigured || hasError) {
                            FilledTonalButton(
                                onClick = { onSaveApiKey(draftKey) },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_save_api_key_button")
                            ) {
                                Text(
                                    text = "Save key",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Button(
                                onClick = { onSaveApiKey(draftKey) },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_save_api_key_button")
                            ) {
                                Text(
                                    text = "Save key",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        if (apiKeyConfigured) {
                            OutlinedButton(
                                onClick = {
                                    draftKey = ""
                                    onSaveApiKey("")
                                },
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("settings_clear_api_key_button")
                            ) {
                                Text(
                                    text = "Clear key",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
