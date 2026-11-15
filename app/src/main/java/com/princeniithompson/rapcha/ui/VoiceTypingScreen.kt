package com.princeniithompson.rapcha.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager
import com.princeniithompson.rapcha.ui.screens.DictionaryScreen
import com.princeniithompson.rapcha.ui.screens.HomeScreen
import com.princeniithompson.rapcha.ui.screens.KeepAwakeScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.AccessibilityDisclosureSheet
import com.princeniithompson.rapcha.ui.screens.onboarding.EmbeddedGoogleAuthBrowserContainer
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningStatus
import kotlinx.coroutines.launch
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.data.RecentDictations
import com.princeniithompson.rapcha.service.FloatingBubbleManager

enum class MainScreenTab {
    HOME,
    DICTIONARY
}

/**
 * v25 lean main screen: Home + Dictionary tabs, and a Settings drawer with only
 * the API key, Welcome Tour, Custom Vocabulary, Keep Awake, the Floating Voice Ring
 * switch and the two permission status rows. Every surface scrolls and wraps so
 * it works at large font sizes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTypingScreen(
    viewModel: VoiceTypingViewModel,
    modifier: Modifier = Modifier,
    dictionaryDraft: String? = null,
    onDictionaryDraftConsumed: () -> Unit = {},
    openDestination: String? = null,
    onOpenDestinationConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isBubbleEnabled by viewModel.isBubbleEnabled.collectAsState()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val hasOverlayPermissionFlow by viewModel.hasOverlayPermission.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val provisionedApiKey by viewModel.provisionedApiKey.collectAsState()
    val signedInGoogleEmail by viewModel.signedInGoogleEmail.collectAsState()
    val vocabularyList by CustomVocabularyRepository.vocabulary.collectAsState()
    val isSmartMode by viewModel.isSmartMode.collectAsState()
    val activeChrome by FloatingBubbleManager.bubbleChrome.collectAsState()
    val statsTotals by DictationStats.totals.collectAsState()
    val recentItems by RecentDictations.items.collectAsState()
    LaunchedEffect(Unit) { DictationStats.load(context) }
    val activeApiKey = remember(customApiKey, provisionedApiKey) {
        customApiKey.ifBlank { provisionedApiKey }.trim()
    }
    val apiKeyConfigured = remember(activeApiKey) {
        !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(activeApiKey)
    }

    val automationEngine = remember { AiStudioAutomationEngine(context) }
    val provisioningState by automationEngine.state.collectAsState()
    val activeWebView by automationEngine.activeWebView.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var resumeTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, automationEngine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumeTick += 1
                    viewModel.refreshPermissions()
                    viewModel.tryAutoInjectFromClipboard()
                    automationEngine.onHostResumed()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    automationEngine.onHostPaused()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            automationEngine.tearDownWebView()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedTab by remember {
        mutableStateOf(
            if (!dictionaryDraft.isNullOrBlank()) MainScreenTab.DICTIONARY else MainScreenTab.HOME
        )
    }
    LaunchedEffect(dictionaryDraft) {
        if (!dictionaryDraft.isNullOrBlank()) {
            selectedTab = MainScreenTab.DICTIONARY
        }
    }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var showKeepAwakeScreen by remember { mutableStateOf(false) }
    val deviceHealthStatus = remember(resumeTick, drawerState.isOpen) {
        DeviceProtectionManager.collectStatus(context)
    }

    LaunchedEffect(openDestination) {
        when (openDestination) {
            FloatingNotificationManager.DEST_KEEP_AWAKE -> {
                showKeepAwakeScreen = true
                onOpenDestinationConsumed()
            }
            FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE -> {
                showAccessibilityDisclosure = true
                onOpenDestinationConsumed()
            }
        }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            viewModel.tryAutoInjectFromClipboard()
        }
    }

    androidx.activity.compose.BackHandler(enabled = selectedTab != MainScreenTab.HOME) {
        selectedTab = MainScreenTab.HOME
    }
    androidx.activity.compose.BackHandler(enabled = showKeepAwakeScreen) {
        showKeepAwakeScreen = false
    }

    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    var showOnboardingManual by remember { mutableStateOf(false) }

    if (!isOnboardingCompleted || showOnboardingManual) {
        com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingScreen(
            viewModel = viewModel,
            onFinishOnboarding = {
                showOnboardingManual = false
            },
            sharedAutomationEngine = automationEngine
        )
        return
    }

    // Safety net: onboarding's voice test asks for the microphone; if it was skipped or the
    // permission was later removed, ask once each time the app opens (Android 11 and 13+).
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    val openOverlaySettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
    val hasOverlayPermission = hasOverlayPermissionFlow || viewModel.canDrawOverlays()

    Box(modifier = modifier.fillMaxSize()) {
        if (activeWebView != null && (
                provisioningState.showBrowserToUser ||
                    provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                    provisioningState.status == ProvisioningStatus.SIGNED_IN_HANDOFF ||
                    provisioningState.status == ProvisioningStatus.SIGNING_IN ||
                    provisioningState.status == ProvisioningStatus.NEEDS_AUTH_RECONFIRM
                )
        ) {
            EmbeddedGoogleAuthBrowserContainer(
                webView = activeWebView,
                visible = provisioningState.showBrowserToUser,
                pageTitle = provisioningState.pageTitle,
                currentUrl = provisioningState.currentUrl,
                isLoading = provisioningState.isPageLoading,
                progress = provisioningState.loadProgress,
                onClose = { automationEngine.dismissVisibleBrowser() },
                onRefresh = { automationEngine.reloadCurrentPage() }
            )
        }

        if (showKeepAwakeScreen) {
            KeepAwakeScreen(
                isOnboarding = false,
                onBack = { showKeepAwakeScreen = false },
                onOpenAccessibilityGuide = { showAccessibilityDisclosure = true }
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        SettingsContent(
                            apiKey = activeApiKey,
                            apiKeyConfigured = apiKeyConfigured,
                            signedInEmail = signedInGoogleEmail,
                            isProvisioning = provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND,
                            vocabularyCount = vocabularyList.size,
                            dictionarySelected = selectedTab == MainScreenTab.DICTIONARY,
                            keepAwakeNeedsFix = deviceHealthStatus.hasAnyRed,
                            isBubbleEnabled = isBubbleEnabled,
                            hasOverlayPermission = hasOverlayPermission,
                            isAccessibilityConnected = isAccessibilityConnected,
                            onSaveApiKey = { newKey ->
                                viewModel.setCustomApiKey(newKey)
                                Toast.makeText(
                                    context,
                                    if (newKey.isBlank()) "API key cleared" else "Gemini API key saved",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onAutoGetWithGoogle = {
                                scope.launch {
                                    drawerState.close()
                                    automationEngine.createOrReuseWebView()
                                    automationEngine.startGoogleSignIn()
                                }
                            },
                            onOpenWelcomeTour = {
                                scope.launch {
                                    drawerState.close()
                                    showOnboardingManual = true
                                }
                            },
                            onOpenDictionary = {
                                scope.launch {
                                    drawerState.close()
                                    selectedTab = MainScreenTab.DICTIONARY
                                }
                            },
                            onOpenKeepAwake = {
                                scope.launch {
                                    drawerState.close()
                                    showKeepAwakeScreen = true
                                }
                            },
                            onBubbleToggle = { enabled ->
                                if (enabled && !viewModel.canDrawOverlays()) {
                                    openOverlaySettings()
                                    Toast.makeText(
                                        context,
                                        "Please grant 'Draw over other apps' permission",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                viewModel.setBubbleEnabled(enabled)
                            },
                            onGrantOverlay = openOverlaySettings,
                            onTurnOnAccessibility = { showAccessibilityDisclosure = true },
                            isSmartMode = isSmartMode,
                            onSmartModeToggle = { viewModel.setSmartMode(it) }
                        )
                    }
                },
                modifier = modifier.fillMaxSize()
            ) {
                Scaffold(
                    bottomBar = {
                        LeanBottomNav(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    },
                    modifier = modifier.fillMaxSize()
                ) { innerPadding ->
                    val openMenu: () -> Unit = {
                        scope.launch {
                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                        }
                    }
                    when (selectedTab) {
                        MainScreenTab.HOME -> {
                            HomeScreen(
                                activeChrome = activeChrome,
                                onChooseChrome = { style ->
                                    FloatingBubbleManager.setBubbleChrome(context, style)
                                },
                                totals = statsTotals,
                                recentItems = recentItems,
                                keepAwakeNeedsFix = deviceHealthStatus.hasAnyRed,
                                onOpenMenu = openMenu,
                                onOpenKeepAwake = { showKeepAwakeScreen = true },
                                onDeleteItem = { RecentDictations.delete(it) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }
                        MainScreenTab.DICTIONARY -> {
                            DictionaryScreen(
                                onOpenMenu = openMenu,
                                draftTerm = dictionaryDraft,
                                onDraftConsumed = onDictionaryDraftConsumed,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureSheet(
            onAgree = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    context,
                    "Find 'Rapcha Voice Typing Assistant' and turn it ON",
                    Toast.LENGTH_LONG
                ).show()
            },
            onDecline = { showAccessibilityDisclosure = false }
        )
    }
}

/**
 * The Settings drawer body. Stateless so it can be rendered in screenshot tests.
 */
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
    onSmartModeToggle: (Boolean) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .testTag("settings_content")
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Smart Polish",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSmartModeToggle(!isSmartMode) }
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("smart_mode_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Smart Mode",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "On: cleans up filler words and adds punctuation. Off: raw verbatim transcript, fastest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = isSmartMode,
                onCheckedChange = onSmartModeToggle,
                modifier = Modifier.testTag("smart_mode_switch")
            )
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        SettingsApiKeyCard(
            apiKey = apiKey,
            apiKeyConfigured = apiKeyConfigured,
            signedInEmail = signedInEmail,
            isProvisioning = isProvisioning,
            onSaveApiKey = onSaveApiKey,
            onAutoGetWithGoogle = onAutoGetWithGoogle
        )
        Spacer(modifier = Modifier.height(8.dp))

        SettingsNavRow(
            icon = Icons.Default.AutoAwesome,
            iconBackground = MaterialTheme.colorScheme.primary,
            iconTint = MaterialTheme.colorScheme.onPrimary,
            title = "Welcome Tour",
            subtitle = "Replay the setup guide",
            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            onClick = onOpenWelcomeTour,
            testTag = "drawer_welcome_tour_row"
        )
        Spacer(modifier = Modifier.height(4.dp))

        SettingsNavRow(
            icon = Icons.Filled.Description,
            iconBackground = MaterialTheme.colorScheme.secondary,
            iconTint = MaterialTheme.colorScheme.onSecondary,
            title = "Custom Vocabulary",
            badge = "$vocabularyCount",
            badgeColor = MaterialTheme.colorScheme.secondary,
            subtitle = "Add custom words, names & email addresses",
            background = if (dictionarySelected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
            onClick = onOpenDictionary,
            testTag = "drawer_vocabulary_row"
        )
        Spacer(modifier = Modifier.height(4.dp))

        val warn = Color(0xFFDC2626)
        val ok = Color(0xFF16A34A)
        SettingsNavRow(
            icon = Icons.Filled.Shield,
            iconBackground = if (keepAwakeNeedsFix) warn else ok,
            iconTint = Color.White,
            title = "Keep Rapcha Awake",
            badge = if (keepAwakeNeedsFix) "Needs fix" else "Ready",
            badgeColor = if (keepAwakeNeedsFix) warn else ok,
            subtitle = "Battery and background settings that keep the bubble alive",
            background = (if (keepAwakeNeedsFix) warn else ok).copy(alpha = 0.10f),
            onClick = onOpenKeepAwake,
            testTag = "drawer_keep_awake_row"
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            text = "Everywhere Voice Bubble",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Floating Voice Ring",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Floats over other apps. Tap to dictate, long-touch to drag anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = isBubbleEnabled,
                onCheckedChange = onBubbleToggle,
                modifier = Modifier.testTag("floating_bubble_switch")
            )
        }

        PermissionStatusRow(
            title = "Draw Over Other Apps",
            granted = hasOverlayPermission,
            grantedText = "✓ Permission granted",
            missingText = "Required for floating bubble",
            actionLabel = "Grant",
            onAction = onGrantOverlay
        )
        PermissionStatusRow(
            title = "Keyboard & Field Detector",
            granted = isAccessibilityConnected,
            grantedText = "✓ Service active",
            missingText = "Required to detect the focused field and type",
            actionLabel = "Turn On",
            onAction = onTurnOnAccessibility
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    iconBackground: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    background: Color,
    onClick: () -> Unit,
    testTag: String,
    badge: String? = null,
    badgeColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (badge != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = if (granted) grantedText else missingText,
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (!granted) {
            Spacer(modifier = Modifier.height(4.dp))
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun SettingsApiKeyCard(
    apiKey: String,
    apiKeyConfigured: Boolean,
    signedInEmail: String,
    isProvisioning: Boolean,
    onSaveApiKey: (String) -> Unit,
    onAutoGetWithGoogle: () -> Unit
) {
    var draftKey by remember(apiKey) { mutableStateOf(apiKey) }
    var revealKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("settings_api_key_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (apiKeyConfigured) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (apiKeyConfigured) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Gemini API Key",
                        tint = if (apiKeyConfigured) Color(0xFF059669) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Gemini API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = when {
                        apiKeyConfigured -> "Connected"
                        isProvisioning -> "Setting up..."
                        else -> "Required"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (apiKeyConfigured) Color(0xFF059669) else MaterialTheme.colorScheme.error
                )
            }

            if (signedInEmail.isNotBlank()) {
                Text(
                    text = "Google: $signedInEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!apiKeyConfigured) {
                Text(
                    text = "An API key is required for voice typing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                placeholder = { Text("AIzaSy... or AQ....") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            imageVector = if (revealKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealKey) "Hide API key" else "Show API key"
                        )
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_api_key_input")
            )

            // v25: buttons stack so they never clip at large font sizes.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onSaveApiKey(draftKey) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_save_api_key_button")
                ) {
                    Text("Save Key", textAlign = TextAlign.Center)
                }
                if (apiKeyConfigured) {
                    OutlinedButton(
                        onClick = {
                            draftKey = ""
                            onSaveApiKey("")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_clear_api_key_button")
                    ) {
                        Text("Clear", textAlign = TextAlign.Center)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAutoGetWithGoogle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_auto_get_key_button")
                    ) {
                        Text("Get via Google", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}


@Composable
private fun LeanBottomNav(
    selectedTab: MainScreenTab,
    onTabSelected: (MainScreenTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wispr_bottom_nav")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Filled.Home,
                label = "Home",
                isSelected = selectedTab == MainScreenTab.HOME,
                onClick = { onTabSelected(MainScreenTab.HOME) },
                testTag = "nav_tab_home",
                modifier = Modifier.weight(1f)
            )
            BottomNavItem(
                icon = Icons.Filled.Description,
                label = "Dictionary",
                isSelected = selectedTab == MainScreenTab.DICTIONARY,
                onClick = { onTabSelected(MainScreenTab.DICTIONARY) },
                testTag = "nav_tab_dictionary",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
