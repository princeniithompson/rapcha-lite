package com.princeniithompson.rapcha.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.data.ApiUsageRepository
import com.princeniithompson.rapcha.data.ApiUsageSnapshot
import java.io.File

@Composable
internal fun elevatedCardSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) {
        scheme.surfaceContainer
    } else {
        scheme.surfaceContainerLowest
    }
}

@Composable
internal fun elevatedCardBorder(): BorderStroke? {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.background.luminance() < 0.5f) {
        BorderStroke(1.dp, scheme.outlineVariant)
    } else {
        null
    }
}

/**
 * Renders the user's real cached Google profile photo when available, or a crisp monogram circle
 * derived from their display name or email.
 */
@Composable
fun UserAvatarBadge(
    displayName: String,
    email: String,
    avatarFilePath: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    showBorder: Boolean = true
) {
    val bitmap: ImageBitmap? = remember(avatarFilePath) {
        if (avatarFilePath.isBlank()) {
            null
        } else {
            try {
                val file = File(avatarFilePath)
                if (file.exists() && file.length() > 128L) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    val initial = remember(displayName, email) {
        val source = displayName.trim().ifBlank { email.trim() }
        source.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "R"
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(
                if (showBorder) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Google account photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Traffic-light semantic colors for daily API quota bars:
 * - Green (< 70% used): plenty left
 * - Yellow / Amber (70% - 94% used): getting close
 * - Red (>= 95% used or HTTP 429): limit reached / nearly exhausted
 */
@Composable
private fun quotaStatusColor(progress: Float, rateLimited: Boolean, unlimitedOrOverflow: Boolean): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        rateLimited || (!unlimitedOrOverflow && progress >= 0.95f) ->
            if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
        !unlimitedOrOverflow && progress >= 0.70f ->
            if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        else ->
            if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    }
}

/**
 * Minimal, containerless Wispr-Flow-style usage lines for the Hamburger Drawer.
 * Sits directly on the drawer background with zero white box, no numbers, no reset clutter,
 * and no tier badge — tapping anywhere on the lines opens the full Daily Usage details page.
 */
@Composable
fun DrawerStandaloneUsageSection(
    snapshot: ApiUsageSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val modulesTitle = if (snapshot.isConfirmedPaid) {
        "Your daily modules usage"
    } else {
        "Your free modules usage"
    }
    val offlineTitle = if (snapshot.isConfirmedPaid) {
        "Your daily offline usage"
    } else {
        "Your free offline usage"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag("drawer_usage_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StandaloneUsageLine(
            label = modulesTitle,
            progress = snapshot.modulesProgress,
            rateLimited = snapshot.modulesRateLimited,
            unlimitedOrOverflow = snapshot.modulesUnlimited || snapshot.modulesPaidOverflow,
            trackColor = trackColor,
            testTag = "usage_bar_modules"
        )
        StandaloneUsageLine(
            label = offlineTitle,
            progress = snapshot.offlineProgress,
            rateLimited = snapshot.offlineRateLimited,
            unlimitedOrOverflow = snapshot.offlineUnlimited || snapshot.offlinePaidOverflow,
            trackColor = trackColor,
            testTag = "usage_bar_offline"
        )
    }
}

@Composable
private fun StandaloneUsageLine(
    label: String,
    progress: Float,
    rateLimited: Boolean,
    unlimitedOrOverflow: Boolean,
    trackColor: Color,
    testTag: String
) {
    val barColor by animateColorAsState(
        targetValue = quotaStatusColor(
            progress = progress,
            rateLimited = rateLimited,
            unlimitedOrOverflow = unlimitedOrOverflow
        ),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "${testTag}_color"
    )
    val visualFillTarget = when {
        rateLimited -> 1f
        unlimitedOrOverflow -> 0.14f
        progress <= 0f -> 0.04f
        else -> progress.coerceIn(0.04f, 1f)
    }
    val animatedFill by animateFloatAsState(
        targetValue = visualFillTarget,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 360f),
        label = "${testTag}_fill"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFill)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

/**
 * Full daily usage breakdown card displayed on the dedicated Daily Usage details screen.
 * Shows exact used/remaining counts, traffic-light bars, local reset schedule, and a Sync button.
 * Only displays a tier badge when the account is genuinely confirmed Paid (e.g., exceeded Free
 * tier limits and kept succeeding, or higher limits synced from Google AI Studio).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyUsageCard(
    snapshot: ApiUsageSnapshot,
    modifier: Modifier = Modifier,
    isSyncing: Boolean = false,
    onSyncClick: (() -> Unit)? = null
) {
    val localResetTime = remember(snapshot.pacificDayKey) {
        ApiUsageRepository.formatLocalResetTime()
    }
    val countdown = remember(snapshot.pacificDayKey, snapshot.modulesUsed, snapshot.offlineUsed) {
        ApiUsageRepository.formatResetCountdown()
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = elevatedCardSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = elevatedCardBorder(),
        modifier = modifier
            .fillMaxWidth()
            .testTag("usage_details_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card header: title + Paid badge ONLY when genuinely confirmed Paid
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Your daily usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                if (snapshot.isConfirmedPaid) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("usage_paid_badge")
                    ) {
                        Text(
                            text = snapshot.tierLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Bar 1: Modules daily usage
            UsageDetailRow(
                label = "Modules daily usage",
                used = snapshot.modulesUsed,
                limit = snapshot.modulesLimit,
                remaining = snapshot.modulesRemaining,
                progress = snapshot.modulesProgress,
                rateLimited = snapshot.modulesRateLimited,
                unlimited = snapshot.modulesUnlimited,
                paidOverflow = snapshot.modulesPaidOverflow,
                barTestTag = "usage_detail_bar_modules"
            )

            // Bar 2: Offline push daily usage
            UsageDetailRow(
                label = "Offline push daily usage",
                used = snapshot.offlineUsed,
                limit = snapshot.offlineLimit,
                remaining = snapshot.offlineRemaining,
                progress = snapshot.offlineProgress,
                rateLimited = snapshot.offlineRateLimited,
                unlimited = snapshot.offlineUnlimited,
                paidOverflow = snapshot.offlinePaidOverflow,
                barTestTag = "usage_detail_bar_offline"
            )

            // Local reset schedule footer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Resets at $localResetTime (in $countdown)",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("usage_reset_time")
                )
            }

            if (onSyncClick != null) {
                TextButton(
                    onClick = onSyncClick,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("usage_sync_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing...", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync limits", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageDetailRow(
    label: String,
    used: Int,
    limit: Int,
    remaining: Int,
    progress: Float,
    rateLimited: Boolean,
    unlimited: Boolean,
    paidOverflow: Boolean,
    barTestTag: String
) {
    val barColor by animateColorAsState(
        targetValue = quotaStatusColor(
            progress = progress,
            rateLimited = rateLimited,
            unlimitedOrOverflow = unlimited || paidOverflow
        ),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "${barTestTag}_color"
    )
    val visualFillTarget = when {
        rateLimited -> 1f
        unlimited || paidOverflow -> 0.14f
        progress <= 0f -> 0.04f
        else -> progress.coerceIn(0.04f, 1f)
    }
    val animatedFill by animateFloatAsState(
        targetValue = visualFillTarget,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 360f),
        label = "${barTestTag}_fill"
    )

    val primaryCount = when {
        rateLimited -> "Limit reached"
        unlimited -> "$used used"
        paidOverflow -> "$used used today"
        else -> "$used / $limit used"
    }
    val secondaryCount = when {
        rateLimited -> "0 left until reset"
        unlimited -> "Unlimited"
        paidOverflow -> "Paid tier active"
        else -> "$remaining left"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(barTestTag),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = primaryCount,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (rateLimited) barColor else MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFill)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }

        Text(
            text = secondaryCount,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = if (rateLimited) barColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
