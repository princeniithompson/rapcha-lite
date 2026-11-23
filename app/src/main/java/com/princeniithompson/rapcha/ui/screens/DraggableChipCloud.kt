package com.princeniithompson.rapcha.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CloudChipItem(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val iconEmoji: String = "",
    val iconKey: String = "",
    val iconColorKey: String = ""
)

/**
 * Pure helper for live drag-to-reorder indexing:
 * - [swapPairFromStartOrder]: Direct 2-item slot swap relative to the drag-start order!
 *   When the user holds item A and settles it over item B, B moves directly into A's original
 *   starting slot and A takes B's slot. If the user keeps holding A and then settles over item C,
 *   B glides back to its original slot and C moves into A's original starting slot.
 * - [moveItem]: Sequential insertion helper preserved for compatibility.
 */
internal object ChipCloudReorderHelper {
    const val SWAP_SETTLE_DWELL_MS = 160L

    fun swapPairFromStartOrder(
        dragStartOrder: List<String>,
        draggedId: String,
        targetSlotIndex: Int
    ): List<String> {
        if (dragStartOrder.isEmpty() || targetSlotIndex !in dragStartOrder.indices) {
            return dragStartOrder
        }
        val startIndex = dragStartOrder.indexOf(draggedId)
        if (startIndex == -1 || startIndex == targetSlotIndex) {
            return dragStartOrder
        }
        val result = dragStartOrder.toMutableList()
        val displacedItem = result[targetSlotIndex]
        result[targetSlotIndex] = draggedId
        result[startIndex] = displacedItem
        return result
    }

    fun moveItem(ids: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        if (fromIndex == toIndex || fromIndex !in ids.indices || toIndex !in ids.indices) {
            return ids
        }
        val mutable = ids.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable
    }
}

/**
 * Page-scoped in-memory Undo / Redo history stack for chip reorder sessions.
 *
 * - Backed by Compose [mutableStateListOf] so Undo/Redo header buttons update reactively.
 * - Lives only while the user is on the current page: leaving the page saves the final
 *   arrangement and clears/disposes the history so old undo steps never leak across visits.
 */
internal class ReorderHistoryStack(private val maxDepth: Int = 30) {
    private val undoStack = mutableStateListOf<List<String>>()
    private val redoStack = mutableStateListOf<List<String>>()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun recordTransition(beforeOrder: List<String>, afterOrder: List<String>) {
        if (beforeOrder == afterOrder || beforeOrder.isEmpty()) return
        undoStack.add(beforeOrder)
        while (undoStack.size > maxDepth) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo(currentOrder: List<String>): List<String>? {
        if (undoStack.isEmpty()) return null
        val target = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(currentOrder)
        return reconcile(target, currentOrder)
    }

    fun redo(currentOrder: List<String>): List<String>? {
        if (redoStack.isEmpty()) return null
        val target = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(currentOrder)
        return reconcile(target, currentOrder)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun reconcile(targetOrder: List<String>, liveOrder: List<String>): List<String> {
        val liveSet = liveOrder.toSet()
        val kept = targetOrder.filter { it in liveSet }
        val extras = liveOrder.filterNot { it in kept }
        return kept + extras
    }
}

/**
 * Centered wrapped flow layout whose [MeasurePolicy] is explicitly keyed on [orderSignature]
 * so that reordering `key(item.id)` children always triggers a fresh measure and placement pass.
 */
@Composable
private fun CenteredFlowLayout(
    orderSignature: List<String>,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 10.dp,
    verticalSpacing: Dp = 10.dp,
    content: @Composable () -> Unit
) {
    val measurePolicy = remember(orderSignature, horizontalSpacing, verticalSpacing) {
        MeasurePolicy { measurables, constraints ->
            val hSpacePx = horizontalSpacing.roundToPx()
            val vSpacePx = verticalSpacing.roundToPx()
            val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(childConstraints) }

            val rows = mutableListOf<MutableList<Placeable>>()
            val rowWidths = mutableListOf<Int>()
            val rowHeights = mutableListOf<Int>()

            var currentRow = mutableListOf<Placeable>()
            var currentRowWidth = 0
            var currentRowHeight = 0

            for (placeable in placeables) {
                val addedWidth = if (currentRow.isEmpty()) placeable.width else hSpacePx + placeable.width
                if (currentRow.isNotEmpty() && currentRowWidth + addedWidth > maxWidth) {
                    rows.add(currentRow)
                    rowWidths.add(currentRowWidth)
                    rowHeights.add(currentRowHeight)
                    currentRow = mutableListOf(placeable)
                    currentRowWidth = placeable.width
                    currentRowHeight = placeable.height
                } else {
                    currentRow.add(placeable)
                    currentRowWidth += addedWidth
                    currentRowHeight = maxOf(currentRowHeight, placeable.height)
                }
            }
            if (currentRow.isNotEmpty()) {
                rows.add(currentRow)
                rowWidths.add(currentRowWidth)
                rowHeights.add(currentRowHeight)
            }

            val maxRowWidth = rowWidths.maxOrNull() ?: 0
            val layoutWidth = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                maxRowWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            }
            val totalHeight = if (rows.isEmpty()) {
                0
            } else {
                rowHeights.sum() + vSpacePx * (rows.size - 1)
            }
            val layoutHeight = totalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

            layout(layoutWidth, layoutHeight) {
                var y = 0
                for (rowIndex in rows.indices) {
                    val row = rows[rowIndex]
                    val rowW = rowWidths[rowIndex]
                    val rowH = rowHeights[rowIndex]
                    var x = ((layoutWidth - rowW) / 2).coerceAtLeast(0)
                    for (placeable in row) {
                        val itemY = y + (rowH - placeable.height) / 2
                        placeable.placeRelative(x, itemY)
                        x += placeable.width + hSpacePx
                    }
                    y += rowH + vSpacePx
                }
            }
        }
    }
    Layout(
        content = content,
        modifier = modifier,
        measurePolicy = measurePolicy
    )
}

@Composable
fun DraggableChipCloud(
    items: List<CloudChipItem>,
    selectedId: String?,
    onChipClick: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDragFinished: (List<String>) -> Unit = onReorder,
    onDragStarted: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    testTagPrefix: String = "chip"
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentItems by rememberUpdatedState(items)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentOnChipClick by rememberUpdatedState(onChipClick)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)

    val chipBounds = remember { mutableStateMapOf<String, Rect>() }
    val slotBoundsAtDragStart = remember { mutableStateMapOf<Int, Rect>() }
    val previousTopLeft = remember { mutableMapOf<String, Offset>() }
    val pendingSnapOffset = remember { mutableStateMapOf<String, Offset>() }
    val placementAnimatables = remember { mutableMapOf<String, Animatable<Offset, AnimationVector2D>>() }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragStartSlotIndex by remember { mutableIntStateOf(-1) }
    var dragFingerInParent by remember { mutableStateOf(Offset.Zero) }
    var grabOffsetFromChipCenter by remember { mutableStateOf(Offset.Zero) }
    var hoveredSlotIndex by remember { mutableIntStateOf(-1) }
    var activeSwappedSlotIndex by remember { mutableIntStateOf(-1) }

    // Dwell timer: when the dragged chip rests over a target slot for ~160ms while finger is still down,
    // immediately animate the target chip into the dragged chip's starting slot (and return any previous
    // target chip smoothly back to its own slot) WITHOUT mutating layout mid-drag!
    LaunchedEffect(draggingId, hoveredSlotIndex) {
        val activeId = draggingId ?: return@LaunchedEffect
        val startSlot = dragStartSlotIndex
        val targetSlot = hoveredSlotIndex
        if (startSlot < 0 || targetSlot < 0 || targetSlot == activeSwappedSlotIndex) return@LaunchedEffect

        delay(ChipCloudReorderHelper.SWAP_SETTLE_DWELL_MS)

        if (draggingId == activeId && hoveredSlotIndex == targetSlot) {
            val baseOrder = dragStartOrder
            val startRect = slotBoundsAtDragStart[startSlot]
            if (targetSlot in baseOrder.indices && startRect != null) {
                val previousSwappedSlot = activeSwappedSlotIndex
                activeSwappedSlotIndex = targetSlot
                HapticFeedbackHelper.triggerSwapTick(context, view)

                // 1. If an earlier chip was displaced to startSlot, smoothly glide it back home (Offset.Zero)
                if (previousSwappedSlot in baseOrder.indices && previousSwappedSlot != startSlot && previousSwappedSlot != targetSlot) {
                    val prevChipId = baseOrder[previousSwappedSlot]
                    pendingSnapOffset.remove(prevChipId)
                    placementAnimatables[prevChipId]?.let { anim ->
                        scope.launch {
                            anim.animateTo(
                                targetValue = Offset.Zero,
                                animationSpec = spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                }

                // 2. If targetSlot is a different chip, smoothly glide it into startSlot's position!
                if (targetSlot != startSlot) {
                    val targetChipId = baseOrder[targetSlot]
                    val targetRect = slotBoundsAtDragStart[targetSlot]
                    val anim = placementAnimatables[targetChipId]
                    if (targetRect != null && anim != null) {
                        val deltaToStartSlot = Offset(
                            x = startRect.center.x - targetRect.center.x,
                            y = startRect.center.y - targetRect.center.y
                        )
                        pendingSnapOffset.remove(targetChipId)
                        scope.launch {
                            anim.animateTo(
                                targetValue = deltaToStartSlot,
                                animationSpec = spring(
                                    dampingRatio = 0.80f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    CenteredFlowLayout(
        orderSignature = items.map { it.id },
        modifier = modifier.fillMaxWidth(),
        horizontalSpacing = 12.dp,
        verticalSpacing = 12.dp
    ) {
        items.forEach { item ->
            key(item.id) {
                val isSelected = selectedId == item.id
                val isDragging = draggingId == item.id
                val placementAnim = remember(item.id) {
                    placementAnimatables.getOrPut(item.id) {
                        Animatable(Offset.Zero, Offset.VectorConverter)
                    }
                }

                val containerColor = MaterialTheme.colorScheme.primary
                val contentColor = MaterialTheme.colorScheme.onPrimary
                val borderColor = when {
                    isDragging -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
                    else -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
                }
                val borderWidth = when {
                    isDragging -> 1.75.dp
                    isSelected -> 2.dp
                    else -> 1.dp
                }
                val chipShadowElevation = when {
                    isDragging -> 12.dp
                    isSelected -> 8.dp
                    !item.enabled -> 1.dp
                    else -> 5.dp
                }

                // Outer Box = pure layout slot inside CenteredFlowLayout (NEVER has graphicsLayer!)
                Box(
                    modifier = Modifier
                        .zIndex(if (isDragging) 10f else 0f)
                        .onGloballyPositioned { coords ->
                            val rect = coords.boundsInParent()
                            chipBounds[item.id] = rect
                            val prev = previousTopLeft[item.id]
                            if (prev != null && (prev - rect.topLeft).getDistanceSquared() > 0.5f && draggingId == null) {
                                // Smoothly animate Undo / Redo or post-drop slot layout transitions
                                val currentVisualOffset = pendingSnapOffset[item.id] ?: placementAnim.value
                                val prevVisualTopLeft = prev + currentVisualOffset
                                val startOffset = prevVisualTopLeft - rect.topLeft
                                previousTopLeft[item.id] = rect.topLeft
                                pendingSnapOffset[item.id] = startOffset
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    placementAnim.snapTo(startOffset)
                                    pendingSnapOffset.remove(item.id)
                                    placementAnim.animateTo(
                                        targetValue = Offset.Zero,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            } else {
                                previousTopLeft[item.id] = rect.topLeft
                            }
                        }
                        .pointerInput(item.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val wasSelectedAtTouchDown = currentSelectedId == item.id
                                val longPress = awaitLongPressOrCancellation(down.id)
                                if (longPress == null) {
                                    val upChange = currentEvent.changes.firstOrNull { it.id == down.id }
                                    if (upChange != null && upChange.changedToUpIgnoreConsumed()) {
                                        currentEvent.changes.forEach { it.consume() }
                                        if (!wasSelectedAtTouchDown || currentSelectedId == item.id) {
                                            currentOnChipClick(item.id)
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                // Consume long-press event so parent background clickable never triggers
                                longPress.consume()
                                currentEvent.changes.forEach { it.consume() }

                                // Long-press confirmed: fire immediate pickup haptic & snapshot starting slots
                                HapticFeedbackHelper.triggerPickup(context, view)
                                val startList = currentItems.map { it.id }
                                dragStartOrder = startList
                                currentOnDragStarted(startList)

                                slotBoundsAtDragStart.clear()
                                startList.forEachIndexed { idx, id ->
                                    chipBounds[id]?.let { slotBoundsAtDragStart[idx] = it }
                                }

                                pendingSnapOffset.remove(item.id)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    placementAnimatables[item.id]?.snapTo(Offset.Zero)
                                }

                                val initialRect = chipBounds[item.id] ?: Rect.Zero
                                val startInParent = initialRect.topLeft + longPress.position
                                dragFingerInParent = startInParent
                                grabOffsetFromChipCenter = startInParent - initialRect.center
                                val startSlotIdx = startList.indexOf(item.id)
                                dragStartSlotIndex = startSlotIdx
                                hoveredSlotIndex = startSlotIdx
                                activeSwappedSlotIndex = startSlotIdx
                                draggingId = item.id

                                try {
                                    drag(longPress.id) { change ->
                                        change.consume()
                                        dragFingerInParent = initialRect.topLeft + change.position
                                        val draggedCenter = dragFingerInParent - grabOffsetFromChipCenter

                                        val hitSlot = slotBoundsAtDragStart.entries.firstOrNull { (_, slotRect) ->
                                            slotRect.inflate(6f).contains(draggedCenter)
                                        }?.key

                                        if (hitSlot != null && hitSlot != hoveredSlotIndex) {
                                            hoveredSlotIndex = hitSlot
                                        }
                                    }
                                } finally {
                                    val droppedId = draggingId
                                    val baseOrder = dragStartOrder
                                    val startSlot = dragStartSlotIndex
                                    val settledSlot = activeSwappedSlotIndex
                                    val finalOrder = if (
                                        droppedId != null &&
                                        startSlot in baseOrder.indices &&
                                        settledSlot in baseOrder.indices &&
                                        settledSlot != startSlot
                                    ) {
                                        ChipCloudReorderHelper.swapPairFromStartOrder(
                                            dragStartOrder = baseOrder,
                                            draggedId = droppedId,
                                            targetSlotIndex = settledSlot
                                        )
                                    } else {
                                        baseOrder.ifEmpty { currentItems.map { it.id } }
                                    }

                                    if (droppedId != null) {
                                        val currentRect = chipBounds[droppedId]
                                        val anim = placementAnimatables[droppedId]
                                        if (currentRect != null && anim != null) {
                                            val fingerOffsetFromOldSlot = Offset(
                                                x = (dragFingerInParent.x - grabOffsetFromChipCenter.x) - currentRect.center.x,
                                                y = (dragFingerInParent.y - grabOffsetFromChipCenter.y) - currentRect.center.y
                                            )
                                            pendingSnapOffset[droppedId] = fingerOffsetFromOldSlot
                                            if (finalOrder == baseOrder) {
                                                // No swap occurred: glide dragged chip back into its starting slot
                                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                    anim.snapTo(fingerOffsetFromOldSlot)
                                                    pendingSnapOffset.remove(droppedId)
                                                    anim.animateTo(
                                                        targetValue = Offset.Zero,
                                                        animationSpec = spring(
                                                            dampingRatio = 0.82f,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (finalOrder == baseOrder) {
                                        baseOrder.forEach { id ->
                                            if (id != droppedId) {
                                                val anim = placementAnimatables[id]
                                                if (anim != null && anim.value != Offset.Zero) {
                                                    pendingSnapOffset.remove(id)
                                                    scope.launch {
                                                        anim.animateTo(
                                                            targetValue = Offset.Zero,
                                                            animationSpec = spring(
                                                                dampingRatio = 0.82f,
                                                                stiffness = Spring.StiffnessMediumLow
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    draggingId = null
                                    dragStartSlotIndex = -1
                                    hoveredSlotIndex = -1
                                    activeSwappedSlotIndex = -1

                                    if (finalOrder != baseOrder) {
                                        currentOnReorder(finalOrder)
                                    }
                                    currentOnDragFinished(finalOrder)
                                }
                            }
                        }
                ) {
                    // Inner Surface = visual chip with GPU graphicsLayer (never affects outer Box slot bounds!)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = containerColor,
                        border = BorderStroke(borderWidth, borderColor),
                        shadowElevation = chipShadowElevation,
                        modifier = Modifier
                            .graphicsLayer {
                                if (isDragging) {
                                    val currentRect = chipBounds[item.id]
                                    if (currentRect != null) {
                                        translationX = (dragFingerInParent.x - grabOffsetFromChipCenter.x) - currentRect.center.x
                                        translationY = (dragFingerInParent.y - grabOffsetFromChipCenter.y) - currentRect.center.y
                                    }
                                    scaleX = 1.07f
                                    scaleY = 1.07f
                                } else {
                                    val offset = pendingSnapOffset[item.id] ?: placementAnim.value
                                    translationX = offset.x
                                    translationY = offset.y
                                    if (isSelected) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                    }
                                }
                            }
                            .alpha(if (item.enabled || isDragging) 1f else 0.42f)
                            .heightIn(min = 48.dp)
                            .testTag("${testTagPrefix}_${item.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val hasBadge = item.iconEmoji.isNotBlank() || item.iconKey.isNotBlank()
                            if (hasBadge) {
                                ItemBadgeGlyph(
                                    iconEmoji = item.iconEmoji,
                                    iconKey = item.iconKey,
                                    iconColorKey = "",
                                    defaultTint = contentColor,
                                    iconSize = 17.dp,
                                    emojiSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                            }
                            Text(
                                text = item.label,
                                color = contentColor,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected || isDragging) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dual-path haptic helper (View haptics with FLAG_IGNORE_GLOBAL_SETTING + direct hardware Vibrator)
 * so long-press pickup and slot swaps always produce crisp physical feedback even on Transsion /
 * Infinix / low-end devices where touch haptics are disabled in system settings.
 */
internal object HapticFeedbackHelper {
    fun triggerPickup(context: Context, view: View?) {
        try {
            @Suppress("DEPRECATION")
            view?.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
        }
        vibrateOneShot(context, durationMs = 38L, amplitude = 180)
    }

    fun triggerSwapTick(context: Context, view: View?) {
        try {
            @Suppress("DEPRECATION")
            view?.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
        }
        vibrateOneShot(context, durationMs = 20L, amplitude = 130)
    }

    private fun vibrateOneShot(context: Context, durationMs: Long, amplitude: Int) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
