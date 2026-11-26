package com.princeniithompson.rapcha.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import com.princeniithompson.rapcha.ui.components.RapchaIcons
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.data.ApiUsageRepository
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.UserProfileRepository
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager
import com.princeniithompson.rapcha.ui.screens.AccountScreen
import com.princeniithompson.rapcha.ui.screens.AppSettingsScreen
import com.princeniithompson.rapcha.ui.screens.DictionaryScreen
import com.princeniithompson.rapcha.ui.screens.HomeScreen
import com.princeniithompson.rapcha.ui.screens.KeepAwakeScreen
import com.princeniithompson.rapcha.ui.screens.ModulesScreen
import com.princeniithompson.rapcha.ui.screens.UsageDetailsScreen
import com.princeniithompson.rapcha.ui.screens.VocabularyReviewScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.AccessibilityDisclosureSheet
import com.princeniithompson.rapcha.ui.screens.onboarding.EmbeddedGoogleAuthBrowserContainer
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningStatus
import kotlinx.coroutines.launch
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.data.RecentDictations
import com.princeniithompson.rapcha.data.ToneRepository
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.VocabularySpeechHelper

enum class MainScreenTab {
    HOME,
    DICTIONARY,
    MODULES
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
    onOpenDestinationConsumed: () -> Unit = {},
    vocabCandidateId: String? = null,
    onVocabCandidateIdConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isBubbleEnabled by viewModel.isBubbleEnabled.collectAsState()
    val isAlwaysOnScreen by viewModel.isAlwaysOnScreen.collectAsState()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val hasOverlayPermissionFlow by viewModel.hasOverlayPermission.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val provisionedApiKey by viewModel.provisionedApiKey.collectAsState()
    val signedInGoogleEmail by viewModel.signedInGoogleEmail.collectAsState()
    val isApiKeyVerified by viewModel.isApiKeyVerified.collectAsState()
    val apiKeyErrorReason by viewModel.apiKeyErrorReason.collectAsState()
    val vocabularyList by CustomVocabularyRepository.vocabulary.collectAsState()
    val isSmartMode by viewModel.isSmartMode.collectAsState()
    val activeChrome by FloatingBubbleManager.bubbleChrome.collectAsState()
    val statsTotals by DictationStats.totals.collectAsState()
    val recentItems by RecentDictations.items.collectAsState()
    val profileState by UserProfileRepository.profile.collectAsState()
    LaunchedEffect(Unit) {
        CustomVocabularyRepository.init(context)
        ToneRepository.init(context)
        DictationStats.load(context)
        RecentDictations.load(context)
        ApiUsageRepository.init(context)
        UserProfileRepository.init(context)
    }
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
    var autoProfileSyncAttempted by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, automationEngine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumeTick += 1
                    viewModel.refreshPermissions()
                    viewModel.tryAutoInjectFromClipboard()
                    ApiUsageRepository.refreshIfDayRolledOver()
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
    var showAccountScreen by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showUsageDetailsScreen by remember { mutableStateOf(false) }
    var showDiagnosticsScreen by remember { mutableStateOf(false) }
    var showVocabReviewScreen by remember { mutableStateOf(false) }
    var activeVocabCandidateId by remember { mutableStateOf<String?>(null) }
    val deviceHealthStatus = remember(resumeTick, drawerState.isOpen, showSettingsScreen) {
        DeviceProtectionManager.collectStatus(context)
    }

    LaunchedEffect(openDestination, vocabCandidateId) {
        when (openDestination) {
            FloatingNotificationManager.DEST_KEEP_AWAKE -> {
                showKeepAwakeScreen = true
                onOpenDestinationConsumed()
            }
            FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE -> {
                showAccessibilityDisclosure = true
                onOpenDestinationConsumed()
            }
            FloatingNotificationManager.DEST_VOCAB_REVIEW -> {
                activeVocabCandidateId = vocabCandidateId
                showVocabReviewScreen = true
                onOpenDestinationConsumed()
                onVocabCandidateIdConsumed()
            }
        }
    }

    LaunchedEffect(drawerState.isOpen, showUsageDetailsScreen) {
        if (drawerState.isOpen || showUsageDetailsScreen) {
            if (drawerState.isOpen) {
                viewModel.tryAutoInjectFromClipboard()
            }
            ApiUsageRepository.refreshIfDayRolledOver()
        }
    }

    androidx.activity.compose.BackHandler(enabled = selectedTab != MainScreenTab.HOME) {
        selectedTab = MainScreenTab.HOME
    }
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }
    androidx.activity.compose.BackHandler(enabled = showAccountScreen) {
        showAccountScreen = false
    }
    androidx.activity.compose.BackHandler(enabled = showUsageDetailsScreen) {
        showUsageDetailsScreen = false
    }
    androidx.activity.compose.BackHandler(enabled = showDiagnosticsScreen) {
        showDiagnosticsScreen = false
    }
    androidx.activity.compose.BackHandler(enabled = showKeepAwakeScreen) {
        showKeepAwakeScreen = false
    }
    androidx.activity.compose.BackHandler(enabled = showVocabReviewScreen) {
        VocabularySpeechHelper.stop()
        showVocabReviewScreen = false
    }

    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    var showOnboardingManual by remember { mutableStateOf(false) }

    LaunchedEffect(isOnboardingCompleted, apiKeyConfigured, isApiKeyVerified, apiKeyErrorReason) {
        if (isOnboardingCompleted && apiKeyConfigured && isApiKeyVerified && apiKeyErrorReason.isBlank()) {
            val needsInitialProfileOrLimitSync = !autoProfileSyncAttempted &&
                (UserProfileRepository.profile.value.avatarFilePath.isBlank() ||
                    ApiUsageRepository.usage.value.lastSyncedAtMs == 0L)
            if (needsInitialProfileOrLimitSync) {
                autoProfileSyncAttempted = true
                automationEngine.syncProfileAndRateLimitsSilently()
            } else if (!UserProfileRepository.profile.value.isSyncing) {
                automationEngine.tearDownWebView()
            }
        } else if (isOnboardingCompleted && apiKeyConfigured && !isApiKeyVerified && apiKeyErrorReason.isBlank()) {
            viewModel.verifyCurrentApiKeyIfNeeded()
        } else if (isOnboardingCompleted && !apiKeyConfigured) {
            automationEngine.resumeSilentProvisioningIfPossible()
        }
    }

    if (!isOnboardingCompleted || showOnboardingManual) {
        com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingScreen(
            viewModel = viewModel,
            onFinishOnboarding = {
                showOnboardingManual = false
                if (viewModel.hasWorkingVerifiedKey()) {
                    automationEngine.tearDownWebView()
                }
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

    val syncProfileAndLimits: () -> Unit = {
        automationEngine.syncProfileAndRateLimitsSilently { message ->
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val needsKeyProvisioning = !apiKeyConfigured || !isApiKeyVerified || apiKeyErrorReason.isNotBlank()
        if (activeWebView != null && (provisioningState.showBrowserToUser || profileState.isSyncing || (needsKeyProvisioning && (
                provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                    provisioningState.status == ProvisioningStatus.SIGNED_IN_HANDOFF ||
                    provisioningState.status == ProvisioningStatus.SIGNING_IN ||
                    provisioningState.status == ProvisioningStatus.NEEDS_AUTH_RECONFIRM
                )))
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

        if (showVocabReviewScreen) {
            VocabularyReviewScreen(
                initialCandidateId = activeVocabCandidateId,
                onClose = {
                    VocabularySpeechHelper.stop()
                    showVocabReviewScreen = false
                    activeVocabCandidateId = null
                },
                modifier = modifier.fillMaxSize()
            )
        } else if (showKeepAwakeScreen) {
            KeepAwakeScreen(
                isOnboarding = false,
                onBack = { showKeepAwakeScreen = false },
                onOpenAccessibilityGuide = { showAccessibilityDisclosure = true }
            )
        } else if (showUsageDetailsScreen) {
            UsageDetailsScreen(
                onBack = { showUsageDetailsScreen = false },
                onSyncProfileAndLimits = syncProfileAndLimits,
                modifier = modifier.fillMaxSize()
            )
        } else if (showDiagnosticsScreen) {
            com.princeniithompson.rapcha.ui.screens.DiagnosticsScreen(
                onBack = { showDiagnosticsScreen = false },
                modifier = modifier.fillMaxSize()
            )
        } else if (showAccountScreen) {
            AccountScreen(
                apiKey = activeApiKey,
                apiKeyConfigured = apiKeyConfigured,
                signedInEmail = signedInGoogleEmail,
                isProvisioning = provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND,
                apiKeyErrorReason = apiKeyErrorReason,
                totals = statsTotals,
                onBack = { showAccountScreen = false },
                onSaveApiKey = { newKey ->
                    viewModel.setCustomApiKey(newKey)
                    Toast.makeText(
                        context,
                        if (newKey.isBlank()) "API key cleared" else "Gemini API key saved",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onAutoGetWithGoogle = {
                    showAccountScreen = false
                    automationEngine.createOrReuseWebView(forceRecovery = true)
                    automationEngine.startGoogleSignIn(signedInGoogleEmail)
                },
                onSyncProfileAndLimits = syncProfileAndLimits,
                onDisconnectAccount = {
                    viewModel.clearProvisionedCredentialsForAccountSwitch()
                    UserProfileRepository.clearProfile(context)
                    ApiUsageRepository.resetForAccountChange()
                    autoProfileSyncAttempted = false
                    Toast.makeText(context, "Signed out and API key cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = modifier.fillMaxSize()
            )
        } else if (showSettingsScreen) {
            AppSettingsScreen(
                hasOverlayPermission = hasOverlayPermission,
                isAccessibilityConnected = isAccessibilityConnected,
                keepAwakeNeedsFix = deviceHealthStatus.hasAnyRed,
                onBack = { showSettingsScreen = false },
                onGrantOverlay = openOverlaySettings,
                onTurnOnAccessibility = { showAccessibilityDisclosure = true },
                onOpenKeepAwake = { showKeepAwakeScreen = true },
                onOpenWelcomeTour = {
                    showSettingsScreen = false
                    showOnboardingManual = true
                },
                onSyncProfileAndLimits = syncProfileAndLimits,
                onOpenUsageDetails = { showUsageDetailsScreen = true },
                onOpenDiagnostics = { showDiagnosticsScreen = true },
                isSmartMode = isSmartMode,
                onSmartModeToggle = { viewModel.setSmartMode(it) },
                isBubbleEnabled = isBubbleEnabled,
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
                isAlwaysOnScreen = isAlwaysOnScreen,
                onAlwaysOnScreenToggle = { enabled ->
                    if (enabled && !isBubbleEnabled) {
                        if (!viewModel.canDrawOverlays()) {
                            openOverlaySettings()
                            Toast.makeText(
                                context,
                                "Please grant 'Draw over other apps' permission",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        viewModel.setBubbleEnabled(true)
                    }
                    viewModel.setAlwaysOnScreen(enabled)
                },
                modifier = modifier.fillMaxSize()
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        drawerContentColor = MaterialTheme.colorScheme.onBackground,
                        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .widthIn(max = 360.dp)
                    ) {
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
                                    automationEngine.createOrReuseWebView(forceRecovery = true)
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
                            isAlwaysOnScreen = isAlwaysOnScreen,
                            onAlwaysOnScreenToggle = { enabled ->
                                if (enabled && !isBubbleEnabled) {
                                    if (!viewModel.canDrawOverlays()) {
                                        openOverlaySettings()
                                        Toast.makeText(
                                            context,
                                            "Please grant 'Draw over other apps' permission",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    viewModel.setBubbleEnabled(true)
                                }
                                viewModel.setAlwaysOnScreen(enabled)
                            },
                            onGrantOverlay = openOverlaySettings,
                            onTurnOnAccessibility = { showAccessibilityDisclosure = true },
                            isSmartMode = isSmartMode,
                            onSmartModeToggle = { viewModel.setSmartMode(it) },
                            apiKeyErrorReason = apiKeyErrorReason,
                            onCloseDrawer = { scope.launch { drawerState.close() } },
                            onOpenAccount = {
                                scope.launch {
                                    drawerState.close()
                                    showAccountScreen = true
                                }
                            },
                            onOpenSettings = {
                                scope.launch {
                                    drawerState.close()
                                    showSettingsScreen = true
                                }
                            },
                            onSyncProfileAndLimits = syncProfileAndLimits,
                            onOpenUsageDetails = {
                                scope.launch {
                                    drawerState.close()
                                    showUsageDetailsScreen = true
                                }
                            }
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
                                apiKeyMissingOrInvalid = !apiKeyConfigured || apiKeyErrorReason.isNotBlank(),
                                isProvisioningKey = provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                                    provisioningState.status == ProvisioningStatus.SIGNING_IN,
                                onConnectApiKey = {
                                    if (!automationEngine.resumeSilentProvisioningIfPossible()) {
                                        automationEngine.createOrReuseWebView(forceRecovery = true)
                                        automationEngine.startGoogleSignIn(signedInGoogleEmail)
                                    }
                                },
                                onOpenVoiceReview = { candidateId ->
                                    activeVocabCandidateId = candidateId
                                    showVocabReviewScreen = true
                                },
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
                                onOpenVoiceReview = { candidateId ->
                                    activeVocabCandidateId = candidateId
                                    showVocabReviewScreen = true
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }
                        MainScreenTab.MODULES -> {
                            ModulesScreen(
                                onOpenMenu = openMenu,
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
            BottomNavItem(
                icon = RapchaIcons.Modules,
                label = "Modules",
                isSelected = selectedTab == MainScreenTab.MODULES,
                onClick = { onTabSelected(MainScreenTab.MODULES) },
                testTag = "nav_tab_modules",
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
