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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val isLargeFont = LocalDensity.current.fontScale >= 1.55f
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

    val cardColor = elevatedCardSurfaceColor()
    val statusDotColor = when {
        allSystemsReady -> Color(0xFF16A34A)
        isApiHealthy -> Color(0xFFEA580C)
        else -> Color(0xFF8E9299)
    }
    val statusDotDescription = when {
        allSystemsReady -> "Ready to dictate"
        isApiHealthy -> "Needs attention"
        else -> "Not ready"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("settings_content")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
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
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (onCloseDrawer != null) {
                    FilledTonalIconButton(
                        onClick = onCloseDrawer,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = cardColor,
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

            // 1. Tappable Google Profile Card (leads to AccountScreen) with 14dp avatar status dot
            Surface(
                onClick = onOpenAccount,
                shape = RoundedCornerShape(22.dp),
                color = cardColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = elevatedCardBorder(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_profile_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.size(48.dp)) {
                        UserAvatarBadge(
                            displayName = effectiveName,
                            email = effectiveEmail,
                            avatarFilePath = profile.avatarFilePath,
                            size = 48.dp
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                                .border(width = 2.dp, color = cardColor, shape = CircleShape)
                                .semantics { contentDescription = statusDotDescription }
                                .testTag("drawer_avatar_status_dot")
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = effectiveName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Start,
                            maxLines = if (isLargeFont) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (effectiveEmail.isNotBlank()) {
                            Text(
                                text = effectiveEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Start,
                                maxLines = if (isLargeFont) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Open account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Standalone containerless usage lines (tap either line to view full Daily Usage details)
            DrawerStandaloneUsageSection(
                snapshot = usage,
                onClick = onOpenUsageDetails
            )
        }

        // Pinned bottom Settings row (respects navigation bar inset)
        Surface(
            onClick = onOpenSettings,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag("drawer_settings_row")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Settings",
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Start
                )
            }
        }
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
internal fun TonalIconWell(
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
internal fun ExpressiveSwitch(
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("api_key_icon_box")
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
                        },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    val badgeColor = if (isHealthy) {
                        Color(0xFF16A34A)
                    } else {
                        Color(0xFF8E9299)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                            .border(width = 2.dp, color = cardContainerColor, shape = CircleShape)
                            .semantics {
                                contentDescription = if (isHealthy) "Connected" else "Not connected"
                            }
                            .testTag("api_key_status_badge"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isHealthy) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(12.dp)
                                    .testTag("api_key_status_check")
                            )
                        }
                    }
                }
                Text(
                    text = "Gemini API key",
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = cardTitleColor,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
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
