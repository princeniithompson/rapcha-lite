package com.princeniithompson.rapcha.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.ui.protection.AccessibilityHealthState
import com.princeniithompson.rapcha.ui.protection.DeviceHealthStatus
import com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StatusGreen = Color(0xFF16A34A)
private val StatusRed = Color(0xFFDC2626)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAwakeScreen(
    isOnboarding: Boolean = false,
    onContinue: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenAccessibilityGuide: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (!isOnboarding) {
        BackHandler(onBack = onBack)
    }

    var status by remember { mutableStateOf(DeviceProtectionManager.collectStatus(context)) }
    var notifPromptAttempts by remember { mutableIntStateOf(0) }

    val refreshStatus = remember(context) {
        {
            FloatingBubbleManager.checkPermissions(context)
            status = DeviceProtectionManager.collectStatus(context)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && notifPromptAttempts >= 2) {
            DeviceProtectionManager.openNotificationSettings(context)
        }
        refreshStatus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val containerBg = if (isOnboarding) OnboardingCreamBackground else MaterialTheme.colorScheme.background
    val titleColor = if (isOnboarding) OnboardingInkBlack else MaterialTheme.colorScheme.onBackground
    val subtitleColor = if (isOnboarding) OnboardingMutedText else MaterialTheme.colorScheme.onSurfaceVariant

    val onFixNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notifPromptAttempts >= 2) {
                DeviceProtectionManager.openNotificationSettings(context)
            } else {
                notifPromptAttempts += 1
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            DeviceProtectionManager.openNotificationSettings(context)
        }
    }

    val onFixAccessibility: () -> Unit = {
        when (status.accessibilityState) {
            AccessibilityHealthState.CONNECTED -> {
                DeviceProtectionManager.openAccessibilitySettings(context)
            }
            AccessibilityHealthState.ENABLED_PAUSED -> {
                if (FloatingBubbleManager.canDrawOverlays(context)) {
                    FloatingBubbleManager.startBubbleService(
                        context = context,
                        source = "keep_awake_a11y_repair",
                        bypassThrottle = true
                    )
                }
                DeviceProtectionManager.openAccessibilitySettings(context)
            }
            AccessibilityHealthState.OFF -> {
                if (isOnboarding) {
                    DeviceProtectionManager.openAccessibilitySettings(context)
                } else {
                    onOpenAccessibilityGuide()
                }
            }
        }
    }

    val contentColumn: @Composable (PaddingValues) -> Unit = { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(containerBg)
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = 20.dp,
                        vertical = if (isOnboarding) 12.dp else 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        if (isOnboarding) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Keep Rapcha Awake",
                                    fontFamily = EditorialSerifFontFamily,
                                    fontSize = 30.sp,
                                    lineHeight = 36.sp,
                                    color = OnboardingInkBlack,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Budget and Samsung phones put apps to sleep to save battery. Check these items so your floating bubble never vanishes.",
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    color = OnboardingMutedText,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        } else {
                            KeepAwakeSummaryBanner(
                                hasAnyRed = status.hasAnyRed,
                                onRefresh = refreshStatus
                            )
                        }
                    }

                    item {
                        ProtectionStatusCard(
                            status = status,
                            isOnboarding = isOnboarding,
                            titleColor = titleColor,
                            subtitleColor = subtitleColor,
                            onFixOverlay = { DeviceProtectionManager.openOverlayPermissionSettings(context) },
                            onFixAccessibility = onFixAccessibility,
                            onFixNotifications = onFixNotifications,
                            onFixBattery = { DeviceProtectionManager.openBatteryOptimizationPrompt(context) },
                            onFixBackground = { DeviceProtectionManager.openAppDetailsSettings(context) },
                            onFixOem = { DeviceProtectionManager.openOemBatterySettings(context, status.oemGuide) }
                        )
                    }

                    item {
                        ServiceHealthCard(
                            status = status,
                            isOnboarding = isOnboarding,
                            onRestartBubble = {
                                if (!FloatingBubbleManager.canDrawOverlays(context)) {
                                    DeviceProtectionManager.openOverlayPermissionSettings(context)
                                } else {
                                    FloatingBubbleManager.setBubbleEnabled(context, true)
                                    FloatingBubbleManager.startBubbleService(
                                        context = context,
                                        source = "keep_awake_manual",
                                        bypassThrottle = true
                                    )
                                    Toast.makeText(
                                        context,
                                        "Bubble service restart requested",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshStatus()
                                }
                            }
                        )
                    }

                    if (!isOnboarding) {
                        item {
                            OutlinedButton(
                                onClick = refreshStatus,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("keep_awake_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh status",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refresh status")
                            }
                        }
                    }
                }

                if (isOnboarding) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onContinue,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .testTag("btn_keep_awake_continue")
                        ) {
                            Text(
                                text = "Continue",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }

    if (isOnboarding) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("screen_keep_awake_onboarding")
        ) {
            contentColumn(PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("screen_keep_awake"),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Keep Rapcha Awake",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("keep_awake_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = refreshStatus,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("keep_awake_top_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh status"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            contentColumn(innerPadding)
        }
    }
}

@Composable
private fun KeepAwakeSummaryBanner(
    hasAnyRed: Boolean,
    onRefresh: () -> Unit
) {
    val bgColor = if (hasAnyRed) {
        StatusRed.copy(alpha = 0.10f)
    } else {
        StatusGreen.copy(alpha = 0.12f)
    }
    val borderColor = if (hasAnyRed) {
        StatusRed.copy(alpha = 0.35f)
    } else {
        StatusGreen.copy(alpha = 0.35f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("keep_awake_summary_banner"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (hasAnyRed) Icons.Filled.Shield else Icons.Filled.CheckCircle,
                contentDescription = if (hasAnyRed) "Protection needs attention" else "All protections active",
                tint = if (hasAnyRed) StatusRed else StatusGreen,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasAnyRed) "Action needed to prevent bubble sleep" else "All protections look healthy",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (hasAnyRed) {
                        "Tap any red item below to fix it, then return here to confirm it turned green."
                    } else {
                        "Your overlay, accessibility, and battery settings are ready for reliable dictation."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh device checks"
                )
            }
        }
    }
}

@Composable
private fun ProtectionStatusCard(
    status: DeviceHealthStatus,
    isOnboarding: Boolean,
    titleColor: Color,
    subtitleColor: Color,
    onFixOverlay: () -> Unit,
    onFixAccessibility: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
    onFixBackground: () -> Unit,
    onFixOem: () -> Unit
) {
    val cardColor = if (isOnboarding) Color.White else MaterialTheme.colorScheme.surfaceContainerLow
    val cardBorder = if (isOnboarding) {
        BorderStroke(1.dp, Color(0xFFE5DFD5))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = cardBorder
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Display over other apps
            ProtectionCheckRow(
                title = "Display over other apps",
                subtitle = if (status.overlayGranted) {
                    "Allowed — bubble can float over other apps"
                } else {
                    "Required — tap to allow Rapcha over other apps"
                },
                isHealthy = status.overlayGranted,
                actionLabel = if (status.overlayGranted) "Settings" else "Fix",
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                testTag = "keep_awake_row_overlay",
                onClick = onFixOverlay
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 2. Accessibility service
            val a11yHealthy = status.accessibilityState == AccessibilityHealthState.CONNECTED
            val a11ySubtitle = when (status.accessibilityState) {
                AccessibilityHealthState.CONNECTED -> "Connected — watching active text fields"
                AccessibilityHealthState.ENABLED_PAUSED -> "Enabled but paused by system — tap to repair"
                AccessibilityHealthState.OFF -> "Off — tap to enable Rapcha Voice Typing Assistant"
            }
            ProtectionCheckRow(
                title = "Accessibility service",
                subtitle = a11ySubtitle,
                isHealthy = a11yHealthy,
                actionLabel = if (a11yHealthy) "Settings" else "Fix",
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                testTag = "keep_awake_row_accessibility",
                onClick = onFixAccessibility
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 3. Notifications (API 33+)
            val notifHealthy = !status.notificationsRequired || status.notificationsGranted
            val notifSubtitle = when {
                !status.notificationsRequired -> "Allowed on this Android version"
                status.notificationsGranted -> "Allowed — bubble & recovery alerts active"
                else -> "Off — tap to allow notification alerts"
            }
            ProtectionCheckRow(
                title = "Notifications",
                subtitle = notifSubtitle,
                isHealthy = notifHealthy,
                actionLabel = if (notifHealthy) "Settings" else "Allow",
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                testTag = "keep_awake_row_notifications",
                onClick = onFixNotifications
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 4. Battery optimization exemption
            ProtectionCheckRow(
                title = "Battery optimization",
                subtitle = if (status.ignoringBatteryOptimizations) {
                    "Unrestricted — exempted from Doze limits"
                } else {
                    "Optimized — tap to set Rapcha to Unrestricted"
                },
                isHealthy = status.ignoringBatteryOptimizations,
                actionLabel = if (status.ignoringBatteryOptimizations) "Settings" else "Fix",
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                testTag = "keep_awake_row_battery",
                onClick = onFixBattery
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 5. Background restriction
            ProtectionCheckRow(
                title = "Background restriction",
                subtitle = if (!status.backgroundRestricted) {
                    "Not restricted — allowed to stay alive"
                } else {
                    "Restricted — tap App Info > Battery > Unrestricted"
                },
                isHealthy = !status.backgroundRestricted,
                actionLabel = if (!status.backgroundRestricted) "App info" else "Fix",
                titleColor = titleColor,
                subtitleColor = subtitleColor,
                testTag = "keep_awake_row_background",
                onClick = onFixBackground
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 6. OEM Step (Samsung Sleeping Apps / Xiaomi Autostart / etc.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onFixOem)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("keep_awake_row_oem"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = status.oemGuide.actionTitle,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.oemGuide.actionTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = status.oemGuide.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor
                    )
                }
                FilledTonalButton(
                    onClick = onFixOem,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Open", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ProtectionCheckRow(
    title: String,
    subtitle: String,
    isHealthy: Boolean,
    actionLabel: String,
    titleColor: Color,
    subtitleColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isHealthy) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (isHealthy) "$title ready" else "$title needs fixing",
            tint = if (isHealthy) StatusGreen else StatusRed,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isHealthy) subtitleColor else StatusRed
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isHealthy) subtitleColor else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = subtitleColor,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun ServiceHealthCard(
    status: DeviceHealthStatus,
    isOnboarding: Boolean,
    onRestartBubble: () -> Unit
) {
    val cardColor = if (isOnboarding) Color.White else MaterialTheme.colorScheme.surfaceContainerLow
    val cardBorder = if (isOnboarding) {
        BorderStroke(1.dp, Color(0xFFE5DFD5))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("keep_awake_service_health_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (status.serviceRunningNow) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        contentDescription = "Bubble service status",
                        tint = if (status.serviceRunningNow) StatusGreen else StatusRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Bubble Service Health",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnboarding) OnboardingInkBlack else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (status.serviceRunningNow) "Running now: Yes" else "Running now: No",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (status.serviceRunningNow) StatusGreen else StatusRed
                )
            }

            Text(
                text = "Last restart reason: ${status.lastRestartReason}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOnboarding) OnboardingMutedText else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!status.serviceRunningNow) {
                Button(
                    onClick = onRestartBubble,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("keep_awake_restart_bubble_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restart bubble now")
                }
            }

            HorizontalDivider()

            Text(
                text = "Recent Bubble Health Events",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isOnboarding) OnboardingInkBlack else MaterialTheme.colorScheme.onSurface
            )

            if (status.recentEvents.isEmpty()) {
                Text(
                    text = "No bubble recovery events recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOnboarding) OnboardingMutedText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    status.recentEvents.forEach { entry ->
                        val formattedTime = remember(entry.timestamp) {
                            timeFormatter.format(Date(entry.timestamp))
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isOnboarding) {
                                Color(0xFFF6F4EF)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    text = "$formattedTime · ${entry.type.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOnboarding) OnboardingInkBlack else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = if (isOnboarding) OnboardingMutedText else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
