package com.princeniithompson.rapcha.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.princeniithompson.rapcha.data.ToneRepository

internal const val DUPLICATE_NAME_ERROR = "Already exists, please try another one"

/**
 * Reusable Dynamic-Color editor modal for Modules, Tones, and Target Languages.
 * Enforces live 19-char Name counter, 500-char Query/Instruction counter,
 * case-insensitive duplicate Name validation, and an optional tappable Icon/Emoji badge.
 */
@Composable
internal fun ItemEditorModalDialog(
    title: String,
    nameLabel: String = "Name",
    namePlaceholder: String = "Name",
    bodyLabel: String,
    bodyPlaceholder: String,
    bodyRequired: Boolean,
    initialName: String = "",
    initialBody: String = "",
    showIconPicker: Boolean = false,
    initialEmoji: String = "",
    initialIconKey: String = "",
    initialColorKey: String = "",
    confirmLabel: String,
    isDuplicateName: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        body: String,
        iconEmoji: String,
        iconKey: String,
        iconColorKey: String
    ) -> Unit
) {
    var name by remember { mutableStateOf(initialName.take(ToneRepository.MAX_NAME_LENGTH)) }
    var body by remember { mutableStateOf(initialBody.take(ToneRepository.MAX_QUERY_LENGTH)) }
    var iconEmoji by remember { mutableStateOf(initialEmoji) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    var iconColorKey by remember { mutableStateOf(initialColorKey) }
    var showPicker by remember { mutableStateOf(false) }

    val trimmedName = ToneRepository.sanitizeName(name)
    val trimmedBody = ToneRepository.sanitizeQuery(body)
    val duplicateError = trimmedName.isNotEmpty() && isDuplicateName(trimmedName)
    val canSave = trimmedName.isNotEmpty() &&
        (!bodyRequired || trimmedBody.isNotEmpty()) &&
        !duplicateError

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
                .testTag("item_editor_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Name field + optional tappable Icon / Emoji badge beside Name input
                Text(
                    text = nameLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showIconPicker) {
                        val hasCustomBadge = iconEmoji.isNotBlank() || iconKey.isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (hasCustomBadge) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (hasCustomBadge) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { showPicker = true }
                                .testTag("editor_icon_badge_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasCustomBadge) {
                                ItemBadgeGlyph(
                                    iconEmoji = iconEmoji,
                                    iconKey = iconKey,
                                    iconColorKey = iconColorKey,
                                    defaultTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    iconSize = 22.dp,
                                    emojiSize = 22.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.AddReaction,
                                    contentDescription = "Choose emoji or icon",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (name.isEmpty()) {
                                Text(
                                    text = namePlaceholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 15.sp
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { raw ->
                                    name = raw.replace("\n", " ").replace("\r", " ")
                                        .take(ToneRepository.MAX_NAME_LENGTH)
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_name_input")
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${name.length}/${ToneRepository.MAX_NAME_LENGTH}",
                            color = if (duplicateError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 13.sp,
                            modifier = Modifier.testTag("editor_name_counter")
                        )
                    }
                }

                if (duplicateError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("editor_duplicate_error")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = DUPLICATE_NAME_ERROR,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Query / Instruction field with bottom-right counter (X/500)
                Text(
                    text = bodyLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 128.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 84.dp)
                    ) {
                        if (body.isEmpty()) {
                            Text(
                                text = bodyPlaceholder,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                        BasicTextField(
                            value = body,
                            onValueChange = { raw ->
                                body = raw.take(ToneRepository.MAX_QUERY_LENGTH)
                            },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp)
                                .testTag("editor_body_input")
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${body.length}/${ToneRepository.MAX_QUERY_LENGTH}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("editor_body_counter")
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Pill action buttons: Cancel (outlined) | Save / Update (primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("editor_cancel_button")
                    ) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                    Button(
                        onClick = { onConfirm(trimmedName, trimmedBody, iconEmoji, iconKey, iconColorKey) },
                        enabled = canSave,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("editor_save_button")
                    ) {
                        Text(
                            text = confirmLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        IconEmojiPickerDialog(
            initialEmoji = iconEmoji,
            initialIconKey = iconKey,
            initialColorKey = iconColorKey,
            onDismiss = { showPicker = false },
            onClear = {
                iconEmoji = ""
                iconKey = ""
                iconColorKey = ""
                showPicker = false
            },
            onSelectEmoji = { emoji ->
                iconEmoji = emoji
                iconKey = ""
                iconColorKey = ""
                showPicker = false
            },
            onSelectIcon = { key, colorKey ->
                iconEmoji = ""
                iconKey = key
                iconColorKey = colorKey
                showPicker = false
            }
        )
    }
}

@Composable
internal fun ConfirmDeleteItemDialog(
    itemType: String,
    itemName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Delete $itemType?", fontWeight = FontWeight.Bold) },
        text = { Text("Remove \"$itemName\" from your Rapcha floating bar?") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}
