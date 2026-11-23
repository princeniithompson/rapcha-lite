package com.princeniithompson.rapcha.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.DictationStats
import com.princeniithompson.rapcha.data.RecentDictations
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.service.floating.HomeCarouselPolicy
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

/**
 * v26 Home: the v24 layout again.
 * - Sticky header (menu, lifebuoy logo, "Rapcha"; no Lite badge).
 * - The Keep Awake warning card when protection needs attention.
 * - The top auto-scrolling carousel. Page 1 is the original Words bubble, page 2 the Mini
 *   pill, both rendered with the real live bubble composables; then Words spoken and WPM
 *   (the "apps you're flowing in" stat is gone with app detection).
 *   Tap a bubble, or its "Use this bubble" button, to make it the active floating bubble.
 *   Swiping and the 19 s idle auto-scroll never change the choice.
 * - The v24 day-grouped list of what you dictated, kept in memory only for this session.
 */
@Composable
fun HomeScreen(
    activeChrome: BubbleChromeStyle,
    onChooseChrome: (BubbleChromeStyle) -> Unit,
    totals: DictationStats.Totals,
    recentItems: List<RecentDictations.Item>,
    keepAwakeNeedsFix: Boolean,
    onOpenMenu: () -> Unit,
    onOpenKeepAwake: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteItem: (Long) -> Unit = {},
    autoScroll: Boolean = true,
    initialPage: Int = HomeCarouselPolicy.PAGE_WORDS_BUBBLE,
    apiKeyMissingOrInvalid: Boolean = false,
    isProvisioningKey: Boolean = false,
    onConnectApiKey: () -> Unit = {},
    onOpenVoiceReview: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val groupedHistory = remember(recentItems) { recentItems.groupBy { it.dateHeader() } }
    val pendingCandidates by CustomVocabularyRepository.pendingCandidates.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("home_screen")
    ) {
        // Fixed Sticky Top Header Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Open Settings Menu",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.Center)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.princeniithompson.rapcha.R.drawable.ic_lifebuoy_ring),
                    contentDescription = "Rapcha Logo",
                    modifier = Modifier
                        .size(34.dp)
                        .padding(end = 8.dp)
                )
                Text(
                    text = "Rapcha",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_list"),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            if (apiKeyMissingOrInvalid) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onConnectApiKey)
                            .testTag("home_api_key_banner"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFDC2626).copy(alpha = 0.12f)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.40f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = "Voice key setup needed",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isProvisioningKey) {
                                        "Activating your Rapcha 30frst key..."
                                    } else {
                                        "Voice typing key not active"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isProvisioningKey) {
                                        "Finishing automatic Google AI Studio setup"
                                    } else {
                                        "Tap here to connect your Google key automatically"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = onConnectApiKey,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_api_key_cta")
                            ) {
                                Text(
                                    text = if (isProvisioningKey) "Working..." else "Activate",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            if (keepAwakeNeedsFix) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onOpenKeepAwake)
                            .testTag("home_keep_awake_banner"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFDC2626).copy(alpha = 0.10f)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WarningAmber,
                                contentDescription = "Keep Rapcha Awake warning",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bubble protection needs attention",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tap to fix battery, permission, or service settings",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = onOpenKeepAwake,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("home_keep_awake_cta")
                            ) {
                                Text("Keep Awake", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Top carousel: two bubble pages, then the stats pages
            item {
                TopCarouselSection(
                    activeChrome = activeChrome,
                    onChooseChrome = onChooseChrome,
                    totals = totals,
                    isDark = isDark,
                    autoScroll = autoScroll,
                    initialPage = initialPage
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Pending Voice-First Learning Card (shown right above Today when a new word/shortcut is detected)
            val firstPending = pendingCandidates.firstOrNull()
            if (firstPending != null) {
                item(key = "pending_vocab_${firstPending.id}") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .testTag("home_pending_candidate_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = firstPending.spokenPrompt,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        CustomVocabularyRepository.approveCandidate(firstPending.id)
                                        FloatingNotificationManager.cancelVocabularyNotification(context)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_pending_candidate_yes")
                                ) {
                                    Text("Yes", fontWeight = FontWeight.Bold)
                                }
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        CustomVocabularyRepository.rejectCandidate(firstPending.id)
                                        FloatingNotificationManager.cancelVocabularyNotification(context)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_pending_candidate_no")
                                ) {
                                    Text("No", fontWeight = FontWeight.Bold)
                                }
                                FilledTonalButton(
                                    onClick = { onOpenVoiceReview(firstPending.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("home_pending_candidate_edit")
                                ) {
                                    Text("Edit", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // Day-grouped list of this session's dictations (memory only)
            if (groupedHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No dictations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .testTag("home_empty_history")
                        )
                    }
                }
            } else {
                groupedHistory.forEach { (dateHeader, itemsInGroup) ->
                    item(key = "header_$dateHeader") {
                        Text(
                            text = dateHeader,
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                    items(itemsInGroup, key = { it.id }) { item ->
                        HistoryCardItem(
                            item = item,
                            isDark = isDark,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Transcription", item.text))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onShare = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, item.text)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Transcription"))
                            },
                            onDelete = { onDeleteItem(item.id) }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

/** Card height grows with the system font size so stats text never clips. */
@Composable
private fun carouselHeight(): Dp {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    return 236.dp + 140.dp * (scale - 1f)
}

/**
 * Top carousel. Auto-advances every 19 s when idle (never mid-swipe). Pages 0 and 1 hold the
 * two real floating bubbles; only an explicit tap changes the active one.
 */
@Composable
private fun TopCarouselSection(
    activeChrome: BubbleChromeStyle,
    onChooseChrome: (BubbleChromeStyle) -> Unit,
    totals: DictationStats.Totals,
    isDark: Boolean,
    autoScroll: Boolean,
    initialPage: Int
) {
    val totalPages = HomeCarouselPolicy.PAGE_COUNT
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { totalPages })
    val formattedWords = remember(totals.words) { "%,d".format(totals.words) }
    val wpm = totals.wpm

    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(autoScroll, isResumed, pagerState.settledPage) {
        if (!autoScroll || !isResumed) return@LaunchedEffect
        delay(HomeCarouselPolicy.IDLE_PAGE_MS)
        if (HomeCarouselPolicy.shouldAutoAdvance(idle = true, userInteracting = pagerState.isScrollInProgress)) {
            val nextPage = HomeCarouselPolicy.nextPage(pagerState.settledPage)
            pagerState.animateScrollToPage(
                page = nextPage,
                animationSpec = tween(
                    durationMillis = if (nextPage == 0) 900 else 750,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(carouselHeight())
                .testTag("home_carousel")
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (page) {
                    HomeCarouselPolicy.PAGE_WORDS_BUBBLE -> BubbleChoicePage(
                        style = BubbleChromeStyle.ORIGINAL,
                        title = "Original bubble",
                        caption = "Shows your words as you speak",
                        active = activeChrome == BubbleChromeStyle.ORIGINAL,
                        animating = isResumed && pagerState.currentPage == page,
                        onChoose = { onChooseChrome(BubbleChromeStyle.ORIGINAL) },
                        isDark = isDark
                    )
                    HomeCarouselPolicy.PAGE_MINI_BUBBLE -> BubbleChoicePage(
                        style = BubbleChromeStyle.COMPACT,
                        title = "Mini bubble",
                        caption = "A small pill with a timer",
                        active = activeChrome == BubbleChromeStyle.COMPACT,
                        animating = isResumed && pagerState.currentPage == page,
                        onChoose = { onChooseChrome(BubbleChromeStyle.COMPACT) },
                        isDark = isDark
                    )
                    HomeCarouselPolicy.PAGE_WORDS_SPOKEN -> StatCardView(
                        statNumber = "$formattedWords words",
                        statColor = MaterialTheme.colorScheme.primary,
                        description = if (totals.words > 0) {
                            "spoken so far across your dictation sessions"
                        } else {
                            "spoken so far\nComplete a dictation session to start tracking"
                        },
                        pageIndex = page,
                        isDark = isDark
                    )
                    HomeCarouselPolicy.PAGE_WPM -> StatCardView(
                        statNumber = "$wpm wpm",
                        statColor = MaterialTheme.colorScheme.tertiary,
                        description = if (wpm > 0) {
                            "This is your words per minute speaking speed"
                        } else {
                            "Words per minute\nCalculated automatically from your voice typing"
                        },
                        pageIndex = page,
                        isDark = isDark
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        PagerDots(pagerState = pagerState, totalPages = totalPages)
    }
}

@Composable
private fun PagerDots(pagerState: PagerState, totalPages: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("home_carousel_dots")
    ) {
        repeat(totalPages) { index ->
            val isSelected = pagerState.currentPage == index
            val dotWidth by animateDpAsState(
                targetValue = if (isSelected) 22.dp else 6.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "dotWidth"
            )
            val dotColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = tween(durationMillis = 350),
                label = "dotColor"
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(dotWidth)
                    .clip(RoundedCornerShape(3.dp))
                    .background(dotColor)
            )
        }
    }
}

/**
 * One bubble page. The preview is the real live bubble composable (dark overlay theme,
 * font scale pinned to 1 exactly like the floating window). Tapping the bubble or the
 * button makes it the active bubble.
 */
@Composable
private fun BubbleChoicePage(
    style: BubbleChromeStyle,
    title: String,
    caption: String,
    active: Boolean,
    animating: Boolean,
    onChoose: () -> Unit,
    isDark: Boolean = false
) {
    val key = if (style == BubbleChromeStyle.COMPACT) "mini" else "words"
    Card(
        modifier = Modifier
            .fillMaxSize()
            .testTag("bubble_page_$key"),
        shape = RoundedCornerShape(26.dp),
        // v27: same card as the stats pages (white in light mode, no border); the active
        // bubble keeps its primary outline so the choice stays visible.
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = when {
            active -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            isDark -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            else -> null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 14.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (active) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("bubble_active_$key")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = onChoose,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("bubble_use_$key")
                    ) {
                        Text("Use this bubble", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                text = caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 2.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(
                        onClickLabel = "Use $title",
                        role = Role.Button,
                        onClick = onChoose
                    )
                    .testTag("bubble_preview_$key"),
                contentAlignment = Alignment.Center
            ) {
                val base = LocalDensity.current
                val previewContext = LocalContext.current
                val isLowPower = remember(previewContext) {
                    com.princeniithompson.rapcha.service.floating.DevicePerformanceTier.isLowPowerDevice(previewContext)
                }
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1f)) {
                    MyApplicationTheme(darkTheme = true, dynamicColor = true) {
                        // The previews only draw; the parent Box takes every tap.
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (style == BubbleChromeStyle.ORIGINAL) {
                                FloatingDictationPopup(
                                    transcriptText = "Hey, I'm on my way. See you in ten minutes with the notes ready.",
                                    isRecording = true,
                                    isPendingFinalizing = false,
                                    isPolishing = false,
                                    elapsedSeconds = 12,
                                    audioAmplitude = 0f,
                                    mimicTalkingWhenSilent = true,
                                    bubbleVisible = animating,
                                    lowPower = isLowPower,
                                    onCancelClick = onChoose,
                                    onPolishClick = onChoose,
                                    onCompleteClick = onChoose,
                                    onLifebuoyClick = onChoose
                                )
                            } else {
                                CompactRecordingBar(
                                    elapsedSeconds = 7,
                                    appName = "Listening",
                                    palette = rememberDynamicAuroraPalette(),
                                    audioAmplitude = 0f,
                                    mimicTalkingWhenSilent = true,
                                    bubbleVisible = animating,
                                    lowPower = isLowPower,
                                    onCancel = onChoose,
                                    onComplete = onChoose
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reusable clean stats card view (v24). */
@Composable
private fun StatCardView(
    statNumber: String,
    statColor: Color,
    description: String,
    pageIndex: Int,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stats_card_$pageIndex"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = if (isDark) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = statNumber,
                fontSize = 34.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                color = statColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

/** Daily history card (v24 style): text, time, Copy | Share | more (Copy text, Delete). */
@Composable
private fun HistoryCardItem(
    item: RecentDictations.Item,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isDark: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("history_card_${item.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = if (isDark) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = item.text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = item.formattedTime(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val btnBg = MaterialTheme.colorScheme.surfaceContainerHigh
                val btnInk = MaterialTheme.colorScheme.onSurfaceVariant
                HistoryPill(Icons.Filled.ContentCopy, "Copy", btnBg, btnInk, onCopy)
                HistoryPill(Icons.Filled.Share, "Share", btnBg, btnInk, onShare)
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            onClick = {
                                showMenu = false
                                onCopy()
                            },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    ink: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = label, tint = ink, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ink)
        }
    }
}
