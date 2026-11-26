package com.princeniithompson.rapcha.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.WorkOutline
import com.princeniithompson.rapcha.data.ToneRepository
import com.princeniithompson.rapcha.ui.components.RapchaIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class CuratedIconOption(
    val key: String,
    val label: String,
    val vector: ImageVector
)

data class IconAccentDot(
    val key: String,
    val label: String,
    val color: Color? // null = Dynamic Theme default
)

/**
 * Lightweight, zero-asset catalog of native Unicode emojis and curated Material vector icons
 * for custom Tones and Modules.
 */
object CuratedIconCatalog {
    val EMOJIS: List<String> = listOf(
        // Moods & people
        "\uD83D\uDCBC", "\uD83C\uDF39", "\uD83D\uDE42", "\uD83D\uDE02", "\uD83D\uDE03", "\uD83D\uDE44",
        "\uD83C\uDFAF", "\uD83D\uDE0E", "\uD83E\uDD73", "\uD83E\uDD14", "\uD83D\uDE0C", "\uD83D\uDE24",
        "\uD83E\uDD7A", "\uD83E\uDD1D", "\uD83D\uDE4C", "\uD83D\uDC4F", "\uD83D\uDE0D", "\uD83E\uDD17",
        // Hearts & sparkles
        "\uD83D\uDC96", "\u2764\uFE0F", "\uD83D\uDD25", "\u2728", "\u2B50", "\uD83D\uDCAB",
        "\uD83C\uDF89", "\uD83D\uDCAC", "\uD83D\uDC8C", "\uD83E\uDEF6", "\uD83C\uDF1F", "\uD83D\uDC4D",
        // Work, writing & symbols
        "\u270D\uFE0F", "\uD83D\uDCDD", "\uD83D\uDCA1", "\u26A1", "\uD83E\uDDE0", "\uD83D\uDCDA",
        "\uD83D\uDD0D", "\uD83D\uDCCC", "\u2705", "\uD83D\uDE80", "\uD83D\uDCBB", "\uD83D\uDCF1",
        "\uD83C\uDF99\uFE0F", "\uD83D\uDDE3\uFE0F", "\uD83C\uDF0D", "\uD83C\uDFC6", "\uD83C\uDFA8", "\uD83C\uDFB5"
    )

    val VECTOR_ICONS: List<CuratedIconOption> = listOf(
        CuratedIconOption("record_voice_over", "Speak", Icons.Rounded.RecordVoiceOver),
        CuratedIconOption("auto_fix", "Modules", RapchaIcons.Modules),
        CuratedIconOption("auto_awesome", "Sparkle", Icons.Rounded.AutoAwesome),
        CuratedIconOption("edit", "Edit", Icons.Rounded.Edit),
        CuratedIconOption("translate", "Translate", Icons.Filled.Translate),
        CuratedIconOption("chat", "Chat", Icons.Rounded.ChatBubbleOutline),
        CuratedIconOption("work", "Work", Icons.Rounded.WorkOutline),
        CuratedIconOption("favorite", "Heart", Icons.Rounded.Favorite),
        CuratedIconOption("star", "Star", Icons.Rounded.Star),
        CuratedIconOption("bolt", "Bolt", Icons.Rounded.Bolt),
        CuratedIconOption("lightbulb", "Idea", Icons.Rounded.Lightbulb),
        CuratedIconOption("book", "Book", Icons.Rounded.MenuBook),
        CuratedIconOption("check", "Check", Icons.Rounded.CheckCircle),
        CuratedIconOption("sentiment", "Friendly", Icons.Rounded.SentimentSatisfiedAlt),
        CuratedIconOption("rocket", "Launch", Icons.Rounded.RocketLaunch),
        CuratedIconOption("code", "Code", Icons.Rounded.Code),
        CuratedIconOption("summarize", "Summary", Icons.Rounded.Subject),
        CuratedIconOption("campaign", "Announce", Icons.Rounded.Campaign)
    )

    val ACCENT_DOTS: List<IconAccentDot> = listOf(
        IconAccentDot("", "Dynamic Theme", null),
        IconAccentDot("coral", "Coral", Color(0xFFFB7185)),
        IconAccentDot("amber", "Amber", Color(0xFFF59E0B)),
        IconAccentDot("emerald", "Emerald", Color(0xFF10B981)),
        IconAccentDot("sky", "Sky", Color(0xFF38BDF8)),
        IconAccentDot("violet", "Violet", Color(0xFFA78BFA))
    )

    private val vectorByKey = VECTOR_ICONS.associateBy { it.key }
    private val colorByKey = ACCENT_DOTS.associateBy { it.key }

    fun resolveVector(key: String): ImageVector? = when (key) {
        "spellcheck" -> Icons.Filled.Spellcheck
        "reply" -> Icons.AutoMirrored.Filled.Reply
        else -> vectorByKey[key]?.vector
    }

    fun builtInModuleIconKey(moduleId: String): String = when (moduleId) {
        ToneRepository.MODULE_GRAMMAR -> "spellcheck"
        ToneRepository.MODULE_TRANSLATE -> "translate"
        ToneRepository.MODULE_REPLY -> "reply"
        ToneRepository.MODULE_TONE -> "record_voice_over"
        else -> "auto_fix"
    }

    fun resolveAccentColor(key: String): Color? = colorByKey[key]?.color
}

/**
 * Renders either a chosen native emoji or a chosen Material vector icon (respecting optional
 * accent tint or Dynamic Theme default). Renders nothing if both are empty.
 */
@Composable
fun ItemBadgeGlyph(
    iconEmoji: String,
    iconKey: String,
    iconColorKey: String,
    defaultTint: Color,
    iconSize: Dp = 16.dp,
    emojiSize: TextUnit = 15.sp
) {
    if (iconEmoji.isNotBlank()) {
        Text(
            text = iconEmoji,
            fontSize = emojiSize,
            maxLines = 1
        )
    } else if (iconKey.isNotBlank()) {
        val vector = CuratedIconCatalog.resolveVector(iconKey) ?: return
        val tint = CuratedIconCatalog.resolveAccentColor(iconColorKey) ?: defaultTint
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Lightweight two-tab (Emoji | Icon) picker styled with Material 3 Dynamic Color.
 */
@Composable
internal fun IconEmojiPickerDialog(
    initialEmoji: String,
    initialIconKey: String,
    initialColorKey: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSelectEmoji: (String) -> Unit,
    onSelectIcon: (iconKey: String, colorKey: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(if (initialIconKey.isNotEmpty()) 1 else 0) }
    var chosenColorKey by remember { mutableStateOf(initialColorKey) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("icon_emoji_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Choose Icon",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("icon_picker_clear_button")
                    ) {
                        Text(
                            text = "None / Clear",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Two-tab segmented toggle: Emoji | Icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PickerTabPill(
                        label = "Emoji",
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        testTag = "icon_picker_tab_emoji",
                        modifier = Modifier.weight(1f)
                    )
                    PickerTabPill(
                        label = "Icon",
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        testTag = "icon_picker_tab_icon",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        items(CuratedIconCatalog.EMOJIS) { emoji ->
                            val isSelected = emoji == initialEmoji
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { onSelectEmoji(emoji) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 21.sp)
                            }
                        }
                    }
                } else {
                    // Compact row of optional accent color dots (first = Dynamic Theme)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CuratedIconCatalog.ACCENT_DOTS.forEach { dot ->
                            val dotColor = dot.color ?: MaterialTheme.colorScheme.primary
                            val isSelected = chosenColorKey == dot.key
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                        },
                                        shape = CircleShape
                                    )
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .clickable { chosenColorKey = dot.key }
                                    .testTag("icon_color_dot_${dot.key.ifEmpty { "dynamic" }}")
                            )
                        }
                    }

                    val previewTint = CuratedIconCatalog.resolveAccentColor(chosenColorKey)
                        ?: MaterialTheme.colorScheme.primary

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                    ) {
                        items(CuratedIconCatalog.VECTOR_ICONS) { option ->
                            val isSelected = option.key == initialIconKey
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                    )
                                    .clickable { onSelectIcon(option.key, chosenColorKey) }
                                    .testTag("icon_option_${option.key}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = option.vector,
                                    contentDescription = option.label,
                                    tint = previewTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
