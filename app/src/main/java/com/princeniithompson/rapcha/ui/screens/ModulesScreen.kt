package com.princeniithompson.rapcha.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.data.ModuleItem
import com.princeniithompson.rapcha.data.TargetLanguageItem
import com.princeniithompson.rapcha.data.ToneItem
import com.princeniithompson.rapcha.data.ToneRepository

private enum class ModulesSubScreen {
    MAIN,
    CONFIGURE_TONE,
    CONFIGURE_LANGUAGE
}

@Composable
fun ModulesScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subScreen by remember { mutableStateOf(ModulesSubScreen.MAIN) }

    when (subScreen) {
        ModulesSubScreen.MAIN -> {
            ModulesMainContent(
                onOpenMenu = onOpenMenu,
                onConfigureModule = { moduleId ->
                    subScreen = when (moduleId) {
                        ToneRepository.MODULE_TONE -> ModulesSubScreen.CONFIGURE_TONE
                        ToneRepository.MODULE_TRANSLATE -> ModulesSubScreen.CONFIGURE_LANGUAGE
                        else -> ModulesSubScreen.MAIN
                    }
                },
                modifier = modifier
            )
        }
        ModulesSubScreen.CONFIGURE_TONE -> {
            BackHandler { subScreen = ModulesSubScreen.MAIN }
            ConfigureToneScreen(
                onClose = { subScreen = ModulesSubScreen.MAIN },
                modifier = modifier
            )
        }
        ModulesSubScreen.CONFIGURE_LANGUAGE -> {
            BackHandler { subScreen = ModulesSubScreen.MAIN }
            ConfigureTargetLanguageScreen(
                onClose = { subScreen = ModulesSubScreen.MAIN },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun ModulesMainContent(
    onOpenMenu: () -> Unit,
    onConfigureModule: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val allModules by ToneRepository.modules.collectAsState()
    var workingOrderIds by remember { mutableStateOf(allModules.map { it.id }) }
    val reorderHistory = remember { ReorderHistoryStack() }
    var orderBeforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModuleId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingModule by remember { mutableStateOf<ModuleItem?>(null) }
    var deletingModule by remember { mutableStateOf<ModuleItem?>(null) }

    // Page-scoped Undo/Redo: while on Modules page, user can undo/redo freely.
    // Once user leaves Modules page (switches tab or opens sub-page), auto-save & clear history.
    DisposableEffect(Unit) {
        onDispose {
            ToneRepository.reorderModules(workingOrderIds)
            reorderHistory.clear()
        }
    }

    LaunchedEffect(allModules) {
        val currentIds = allModules.map { it.id }.toSet()
        val kept = workingOrderIds.filter { it in currentIds }
        val added = allModules.map { it.id }.filterNot { it in kept }
        val merged = kept + added
        if (merged != workingOrderIds) {
            workingOrderIds = merged
        }
    }

    val orderedModules = remember(allModules, workingOrderIds) {
        val byId = allModules.associateBy { it.id }
        workingOrderIds.mapNotNull { byId[it] }
    }
    val selectedModule = remember(orderedModules, selectedModuleId) {
        orderedModules.firstOrNull { it.id == selectedModuleId }
    }
    val chipItems = remember(orderedModules) {
        orderedModules.map { mod ->
            val effectiveIconKey = when {
                mod.iconEmoji.isNotBlank() -> ""
                mod.iconKey.isNotBlank() -> mod.iconKey
                else -> CuratedIconCatalog.builtInModuleIconKey(mod.id)
            }
            CloudChipItem(
                id = mod.id,
                label = mod.name,
                enabled = mod.enabled,
                iconEmoji = mod.iconEmoji,
                iconKey = effectiveIconKey,
                iconColorKey = mod.iconColorKey
            )
        }
    }

    val canUndo = reorderHistory.canUndo
    val canRedo = reorderHistory.canRedo

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                selectedModuleId = null
            }
            .padding(horizontal = 20.dp)
            .testTag("modules_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Rapcha top app bar with compact top-right Undo / Redo arrows
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
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
            UndoRedoActionRow(
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = {
                    val restored = reorderHistory.undo(workingOrderIds) ?: return@UndoRedoActionRow
                    HapticFeedbackHelper.triggerSwapTick(context, view)
                    workingOrderIds = restored
                    ToneRepository.reorderModules(restored)
                },
                onRedo = {
                    val restored = reorderHistory.redo(workingOrderIds) ?: return@UndoRedoActionRow
                    HapticFeedbackHelper.triggerSwapTick(context, view)
                    workingOrderIds = restored
                    ToneRepository.reorderModules(restored)
                },
                testTagPrefix = "modules",
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Smoothly expandable Top Inspector Card (shown only when a chip is selected)
            AnimatedVisibility(
                visible = selectedModule != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                selectedModule?.let { module ->
                    ModuleInspectorCard(
                        module = module,
                        onDelete = { deletingModule = module },
                        onEdit = { editingModule = module },
                        onConfigure = { onConfigureModule(module.id) },
                        onToggleEnabled = {
                            ToneRepository.setModuleEnabled(module.id, !module.enabled)
                        },
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                }
            }

            if (selectedModule == null) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Centered wrapped draggable chip cloud with live automatic swap-while-dragging
            DraggableChipCloud(
                items = chipItems,
                selectedId = selectedModuleId,
                onChipClick = { id ->
                    selectedModuleId = if (selectedModuleId == id) null else id
                },
                onDragStarted = { startIds ->
                    orderBeforeDrag = startIds
                },
                onReorder = { orderedIds ->
                    workingOrderIds = orderedIds
                },
                onDragFinished = { finalIds ->
                    workingOrderIds = finalIds
                    if (orderBeforeDrag.isNotEmpty() && orderBeforeDrag != finalIds) {
                        reorderHistory.recordTransition(orderBeforeDrag, finalIds)
                    }
                    ToneRepository.reorderModules(finalIds)
                },
                testTagPrefix = "module_chip"
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Hold and drag an item to reorder the display sequence of your modules. And also you can tap to get more detailed customization of your modules.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { selectedModuleId = null }
                    .padding(horizontal = 12.dp)
            )

            Spacer(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { selectedModuleId = null }
            )
        }

        // Tactile centered circular + button above the bottom navigation bar
        CenteredAddCircleButton(
            contentDescription = "Create new module",
            onClick = { showCreateDialog = true },
            testTag = "create_new_module_button",
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
        )
    }

    if (showCreateDialog) {
        ItemEditorModalDialog(
            title = "Module",
            bodyLabel = "Query",
            bodyPlaceholder = "Ex: Add emojis inside the target text...",
            bodyRequired = true,
            showIconPicker = true,
            confirmLabel = "Save",
            isDuplicateName = { candidate -> ToneRepository.isModuleNameDuplicate(candidate) },
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, query, emoji, iconKey, colorKey ->
                if (ToneRepository.addCustomModule(name, query, emoji, iconKey, colorKey)) {
                    showCreateDialog = false
                }
            }
        )
    }

    editingModule?.let { target ->
        ItemEditorModalDialog(
            title = "Edit Module",
            bodyLabel = "Query",
            bodyPlaceholder = "Ex: Add emojis inside the target text...",
            bodyRequired = true,
            initialName = target.name,
            initialBody = target.query,
            showIconPicker = true,
            initialEmoji = target.iconEmoji,
            initialIconKey = target.iconKey,
            initialColorKey = target.iconColorKey,
            confirmLabel = "Update",
            isDuplicateName = { candidate ->
                ToneRepository.isModuleNameDuplicate(candidate, excludeId = target.id)
            },
            onDismiss = { editingModule = null },
            onConfirm = { name, query, emoji, iconKey, colorKey ->
                if (ToneRepository.editCustomModule(target.id, name, query, emoji, iconKey, colorKey)) {
                    editingModule = null
                }
            }
        )
    }

    deletingModule?.let { target ->
        ConfirmDeleteItemDialog(
            itemType = "module",
            itemName = target.name,
            onDismiss = { deletingModule = null },
            onConfirm = {
                ToneRepository.deleteCustomModule(target.id)
                if (selectedModuleId == target.id) selectedModuleId = null
                deletingModule = null
            }
        )
    }
}

@Composable
private fun ModuleInspectorCard(
    module: ModuleItem,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onConfigure: () -> Unit,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canMutate = !module.isBuiltIn
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* absorb tap so inspector stays open */ }
            .testTag("module_inspector_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Module",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val inspectorIconKey = when {
                    module.iconEmoji.isNotBlank() -> ""
                    module.iconKey.isNotBlank() -> module.iconKey
                    else -> CuratedIconCatalog.builtInModuleIconKey(module.id)
                }
                if (module.iconEmoji.isNotBlank() || inspectorIconKey.isNotBlank()) {
                    ItemBadgeGlyph(
                        iconEmoji = module.iconEmoji,
                        iconKey = inspectorIconKey,
                        iconColorKey = module.iconColorKey,
                        defaultTint = MaterialTheme.colorScheme.primary,
                        iconSize = 19.dp,
                        emojiSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = module.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Query",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = module.query,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = if (module.isBuiltIn) FontWeight.Bold else FontWeight.Normal,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Side-by-side Delete & Edit buttons (enabled ONLY for custom modules)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InspectorOutlineButton(
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    enabled = canMutate,
                    onClick = onDelete,
                    testTag = "inspector_delete_button",
                    modifier = Modifier.weight(1f)
                )
                InspectorOutlineButton(
                    icon = Icons.Outlined.Edit,
                    label = "Edit",
                    enabled = canMutate,
                    onClick = onEdit,
                    testTag = "inspector_edit_button",
                    modifier = Modifier.weight(1f)
                )
            }

            // Full-width Configure button ONLY for Translate and Tone
            if (module.isConfigurable) {
                Spacer(modifier = Modifier.height(10.dp))
                InspectorOutlineButton(
                    icon = Icons.Outlined.Build,
                    label = "Configure",
                    enabled = true,
                    onClick = onConfigure,
                    testTag = "inspector_configure_button",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full-width Disable / Enable toggle button
            InspectorOutlineButton(
                icon = if (module.enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                label = if (module.enabled) "Disable" else "Enable",
                enabled = true,
                onClick = onToggleEnabled,
                testTag = "inspector_toggle_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InspectorOutlineButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val contentTint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.7f else 0.3f)
        ),
        modifier = modifier
            .height(48.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = contentTint,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

// =============================================================================
// Sub-Configuration Screen: Tone
// =============================================================================

@Composable
private fun ConfigureToneScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repoTones by ToneRepository.tones.collectAsState()
    var orderedIds by remember { mutableStateOf(repoTones.map { it.id }) }
    val reorderHistory = remember { ReorderHistoryStack() }
    var orderBeforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTone by remember { mutableStateOf<ToneItem?>(null) }
    var deletingTone by remember { mutableStateOf<ToneItem?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            ToneRepository.reorderTones(orderedIds)
            reorderHistory.clear()
        }
    }

    LaunchedEffect(repoTones) {
        val currentIds = repoTones.map { it.id }.toSet()
        val kept = orderedIds.filter { it in currentIds }
        val added = repoTones.map { it.id }.filterNot { it in kept }
        val merged = kept + added
        if (merged != orderedIds) {
            orderedIds = merged
        }
    }

    val displayTones = remember(repoTones, orderedIds) {
        val byId = repoTones.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }
    val selectedTone = remember(displayTones, selectedId) {
        displayTones.firstOrNull { it.id == selectedId }
    }

    SubConfigChipCloudScaffold(
        title = "Tone",
        items = displayTones.map {
            CloudChipItem(
                id = it.id,
                label = it.name,
                enabled = it.enabled,
                iconEmoji = it.iconEmoji,
                iconKey = it.iconKey,
                iconColorKey = it.iconColorKey
            )
        },
        selectedId = selectedId,
        selectedName = selectedTone?.name,
        selectedEmoji = selectedTone?.iconEmoji.orEmpty(),
        selectedIconKey = selectedTone?.iconKey.orEmpty(),
        selectedColorKey = selectedTone?.iconColorKey.orEmpty(),
        selectedDetail = selectedTone?.instruction,
        selectedCanMutate = selectedTone?.let { !it.isDefault } ?: false,
        selectedEnabled = selectedTone?.enabled ?: true,
        canUndo = reorderHistory.canUndo,
        canRedo = reorderHistory.canRedo,
        onUndo = {
            val restored = reorderHistory.undo(orderedIds) ?: return@SubConfigChipCloudScaffold
            HapticFeedbackHelper.triggerSwapTick(context, view)
            orderedIds = restored
            ToneRepository.reorderTones(restored)
        },
        onRedo = {
            val restored = reorderHistory.redo(orderedIds) ?: return@SubConfigChipCloudScaffold
            HapticFeedbackHelper.triggerSwapTick(context, view)
            orderedIds = restored
            ToneRepository.reorderTones(restored)
        },
        onBack = {
            ToneRepository.reorderTones(orderedIds)
            reorderHistory.clear()
            onClose()
        },
        onAddClick = { showCreateDialog = true },
        onChipClick = { id -> selectedId = if (selectedId == id) null else id },
        onClearSelection = { selectedId = null },
        onDragStarted = { startIds -> orderBeforeDrag = startIds },
        onReorderDraft = { newOrder -> orderedIds = newOrder },
        onDragFinished = { finalIds ->
            orderedIds = finalIds
            if (orderBeforeDrag.isNotEmpty() && orderBeforeDrag != finalIds) {
                reorderHistory.recordTransition(orderBeforeDrag, finalIds)
            }
            ToneRepository.reorderTones(finalIds)
        },
        onEditSelected = { editingTone = selectedTone },
        onDeleteSelected = { deletingTone = selectedTone },
        onToggleSelected = {
            selectedTone?.let { ToneRepository.setToneEnabled(it.id, !it.enabled) }
        },
        onSaveAndClose = {
            ToneRepository.reorderTones(orderedIds)
            reorderHistory.clear()
            onClose()
        },
        testTagPrefix = "config_tone",
        modifier = modifier
    )

    if (showCreateDialog) {
        ItemEditorModalDialog(
            title = "New Tone",
            namePlaceholder = "Ex: Vibe Coding",
            bodyLabel = "System Instruction",
            bodyPlaceholder = "Ex: You are a vibe coding engineer who formats the user's message clearly and practically...",
            bodyRequired = true,
            showIconPicker = true,
            confirmLabel = "Save",
            isDuplicateName = { candidate -> ToneRepository.isToneNameDuplicate(candidate) },
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, instruction, emoji, iconKey, colorKey ->
                if (ToneRepository.addCustomTone(name, instruction, emoji, iconKey, colorKey)) {
                    showCreateDialog = false
                }
            }
        )
    }

    editingTone?.let { target ->
        ItemEditorModalDialog(
            title = "Edit Tone",
            namePlaceholder = "Ex: Vibe Coding",
            bodyLabel = "System Instruction",
            bodyPlaceholder = "Describe how Rapcha should rewrite text in this tone...",
            bodyRequired = true,
            initialName = target.name,
            initialBody = target.instruction,
            showIconPicker = true,
            initialEmoji = target.iconEmoji,
            initialIconKey = target.iconKey,
            initialColorKey = target.iconColorKey,
            confirmLabel = "Update",
            isDuplicateName = { candidate ->
                ToneRepository.isToneNameDuplicate(candidate, excludeId = target.id)
            },
            onDismiss = { editingTone = null },
            onConfirm = { name, instruction, emoji, iconKey, colorKey ->
                if (ToneRepository.editCustomTone(target.id, name, instruction, emoji, iconKey, colorKey)) {
                    editingTone = null
                }
            }
        )
    }

    deletingTone?.let { target ->
        ConfirmDeleteItemDialog(
            itemType = "tone",
            itemName = target.name,
            onDismiss = { deletingTone = null },
            onConfirm = {
                ToneRepository.deleteCustomTone(target.id)
                if (selectedId == target.id) selectedId = null
                deletingTone = null
            }
        )
    }
}

// =============================================================================
// Sub-Configuration Screen: Target Language
// =============================================================================

@Composable
private fun ConfigureTargetLanguageScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repoLanguages by ToneRepository.languages.collectAsState()
    var orderedIds by remember { mutableStateOf(repoLanguages.map { it.id }) }
    val reorderHistory = remember { ReorderHistoryStack() }
    var orderBeforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingLang by remember { mutableStateOf<TargetLanguageItem?>(null) }
    var deletingLang by remember { mutableStateOf<TargetLanguageItem?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            ToneRepository.reorderLanguages(orderedIds)
            reorderHistory.clear()
        }
    }

    LaunchedEffect(repoLanguages) {
        val currentIds = repoLanguages.map { it.id }.toSet()
        val kept = orderedIds.filter { it in currentIds }
        val added = repoLanguages.map { it.id }.filterNot { it in kept }
        val merged = kept + added
        if (merged != orderedIds) {
            orderedIds = merged
        }
    }

    val displayLanguages = remember(repoLanguages, orderedIds) {
        val byId = repoLanguages.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }
    val selectedLang = remember(displayLanguages, selectedId) {
        displayLanguages.firstOrNull { it.id == selectedId }
    }

    SubConfigChipCloudScaffold(
        title = "Target Language",
        items = displayLanguages.map { CloudChipItem(it.id, it.name, it.enabled) },
        selectedId = selectedId,
        selectedName = selectedLang?.name,
        selectedEmoji = "",
        selectedIconKey = "",
        selectedColorKey = "",
        selectedDetail = selectedLang?.instruction?.takeIf { it.isNotBlank() },
        selectedCanMutate = selectedLang?.let { !it.isDefault } ?: false,
        selectedEnabled = selectedLang?.enabled ?: true,
        canUndo = reorderHistory.canUndo,
        canRedo = reorderHistory.canRedo,
        onUndo = {
            val restored = reorderHistory.undo(orderedIds) ?: return@SubConfigChipCloudScaffold
            HapticFeedbackHelper.triggerSwapTick(context, view)
            orderedIds = restored
            ToneRepository.reorderLanguages(restored)
        },
        onRedo = {
            val restored = reorderHistory.redo(orderedIds) ?: return@SubConfigChipCloudScaffold
            HapticFeedbackHelper.triggerSwapTick(context, view)
            orderedIds = restored
            ToneRepository.reorderLanguages(restored)
        },
        onBack = {
            ToneRepository.reorderLanguages(orderedIds)
            reorderHistory.clear()
            onClose()
        },
        onAddClick = { showCreateDialog = true },
        onChipClick = { id -> selectedId = if (selectedId == id) null else id },
        onClearSelection = { selectedId = null },
        onDragStarted = { startIds -> orderBeforeDrag = startIds },
        onReorderDraft = { newOrder -> orderedIds = newOrder },
        onDragFinished = { finalIds ->
            orderedIds = finalIds
            if (orderBeforeDrag.isNotEmpty() && orderBeforeDrag != finalIds) {
                reorderHistory.recordTransition(orderBeforeDrag, finalIds)
            }
            ToneRepository.reorderLanguages(finalIds)
        },
        onEditSelected = { editingLang = selectedLang },
        onDeleteSelected = { deletingLang = selectedLang },
        onToggleSelected = {
            selectedLang?.let { ToneRepository.setLanguageEnabled(it.id, !it.enabled) }
        },
        onSaveAndClose = {
            ToneRepository.reorderLanguages(orderedIds)
            reorderHistory.clear()
            onClose()
        },
        testTagPrefix = "config_language",
        modifier = modifier
    )

    if (showCreateDialog) {
        ItemEditorModalDialog(
            title = "New Language",
            namePlaceholder = "Ex: \uD83C\uDDF3\uD83C\uDDEC Pidgin or \uD83C\uDDEC\uD83C\uDDED Twi",
            bodyLabel = "Dialect / Style Note (Optional)",
            bodyPlaceholder = "Optional note (e.g., Translate into natural everyday Nigerian Pidgin)",
            bodyRequired = false,
            confirmLabel = "Save",
            isDuplicateName = { candidate -> ToneRepository.isLanguageNameDuplicate(candidate) },
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, instruction, _, _, _ ->
                if (ToneRepository.addCustomLanguage(name, instruction)) {
                    showCreateDialog = false
                }
            }
        )
    }

    editingLang?.let { target ->
        ItemEditorModalDialog(
            title = "Edit Language",
            namePlaceholder = "Ex: \uD83C\uDDF3\uD83C\uDDEC Pidgin",
            bodyLabel = "Dialect / Style Note (Optional)",
            bodyPlaceholder = "Optional dialect or translation instruction...",
            bodyRequired = false,
            initialName = target.name,
            initialBody = target.instruction,
            confirmLabel = "Update",
            isDuplicateName = { candidate ->
                ToneRepository.isLanguageNameDuplicate(candidate, excludeId = target.id)
            },
            onDismiss = { editingLang = null },
            onConfirm = { name, instruction, _, _, _ ->
                if (ToneRepository.editCustomLanguage(target.id, name, instruction)) {
                    editingLang = null
                }
            }
        )
    }

    deletingLang?.let { target ->
        ConfirmDeleteItemDialog(
            itemType = "language",
            itemName = target.name,
            onDismiss = { deletingLang = null },
            onConfirm = {
                ToneRepository.deleteCustomLanguage(target.id)
                if (selectedId == target.id) selectedId = null
                deletingLang = null
            }
        )
    }
}

@Composable
private fun SubConfigChipCloudScaffold(
    title: String,
    items: List<CloudChipItem>,
    selectedId: String?,
    selectedName: String?,
    selectedEmoji: String,
    selectedIconKey: String,
    selectedColorKey: String,
    selectedDetail: String?,
    selectedCanMutate: Boolean,
    selectedEnabled: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onChipClick: (String) -> Unit,
    onClearSelection: () -> Unit,
    onDragStarted: (List<String>) -> Unit,
    onReorderDraft: (List<String>) -> Unit,
    onDragFinished: (List<String>) -> Unit,
    onEditSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onToggleSelected: () -> Unit,
    onSaveAndClose: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClearSelection() }
            .padding(horizontal = 20.dp)
            .testTag("${testTagPrefix}_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar: Close (X) + Title + Top-right Undo / Redo arrows
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("${testTagPrefix}_close_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            UndoRedoActionRow(
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = onUndo,
                onRedo = onRedo,
                testTagPrefix = testTagPrefix
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = selectedName != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (selectedName != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {}
                            .testTag("${testTagPrefix}_inspector_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedEmoji.isNotBlank() || selectedIconKey.isNotBlank()) {
                                    ItemBadgeGlyph(
                                        iconEmoji = selectedEmoji,
                                        iconKey = selectedIconKey,
                                        iconColorKey = selectedColorKey,
                                        defaultTint = MaterialTheme.colorScheme.primary,
                                        iconSize = 18.dp,
                                        emojiSize = 17.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = selectedName,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (!selectedDetail.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = selectedDetail,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                InspectorOutlineButton(
                                    icon = Icons.Outlined.Delete,
                                    label = "Delete",
                                    enabled = selectedCanMutate,
                                    onClick = onDeleteSelected,
                                    testTag = "${testTagPrefix}_delete_button",
                                    modifier = Modifier.weight(1f)
                                )
                                InspectorOutlineButton(
                                    icon = Icons.Outlined.Edit,
                                    label = "Edit",
                                    enabled = selectedCanMutate,
                                    onClick = onEditSelected,
                                    testTag = "${testTagPrefix}_edit_button",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            InspectorOutlineButton(
                                icon = if (selectedEnabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                label = if (selectedEnabled) "Disable" else "Enable",
                                enabled = true,
                                onClick = onToggleSelected,
                                testTag = "${testTagPrefix}_toggle_button",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (selectedName == null) {
                Spacer(modifier = Modifier.height(14.dp))
            }

            DraggableChipCloud(
                items = items,
                selectedId = selectedId,
                onChipClick = onChipClick,
                onDragStarted = onDragStarted,
                onReorder = onReorderDraft,
                onDragFinished = onDragFinished,
                testTagPrefix = "${testTagPrefix}_chip"
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Caption + Tactile Centered Circular + Button + Save pill button anchored cleanly at bottom
        Text(
            text = "Hold and drag an item to reorder the display sequence of your modules. And also you can tap to get more detailed customization of your modules.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClearSelection() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        CenteredAddCircleButton(
            contentDescription = "Add $title",
            onClick = onAddClick,
            testTag = "${testTagPrefix}_add_button"
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSaveAndClose,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .height(52.dp)
                .testTag("${testTagPrefix}_save_button")
        ) {
            Text(
                text = "Save",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UndoRedoActionRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.testTag("${testTagPrefix}_undo_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Undo reorder",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (canUndo) 1f else 0.30f),
                modifier = Modifier.size(22.dp)
            )
        }
        IconButton(
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.testTag("${testTagPrefix}_redo_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Redo,
                contentDescription = "Redo reorder",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (canRedo) 1f else 0.30f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CenteredAddCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        ),
        modifier = modifier
            .size(66.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = contentDescription,
            modifier = Modifier.size(32.dp)
        )
    }
}


