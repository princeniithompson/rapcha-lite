package com.princeniithompson.rapcha.ui.components.overlay.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import com.princeniithompson.rapcha.ui.components.overlay.FloatingActionRow

/**
 * Action buttons section (Cancel, Polish, Complete) with state-guarded completion logic.
 */
@Composable
fun FloatingButtonRowSection(
    state: FloatingDictationPopupState,
    palette: AuroraColorPalette,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPolish: Boolean = false,
    elapsedSeconds: Int = 0,
    isNetworkProblem: Boolean = false,
    staticPreview: Boolean = false
) {
    FloatingActionRow(
        isFinalizing = if (staticPreview) false else state.isFinalizing,
        isPolishing = if (staticPreview) false else state.isPolishing,
        palette = palette,
        onCancelClick = onCancelClick,
        onPolishClick = onPolishClick,
        onCompleteClick = {
            if (staticPreview) {
                onCompleteClick()
            } else if (!state.isFinalizing && !state.isPolishing) {
                state.markLocalComplete()
                onCompleteClick()
            }
        },
        modifier = modifier,
        showPolish = showPolish,
        elapsedSeconds = elapsedSeconds,
        isNetworkProblem = if (staticPreview) false else isNetworkProblem,
        blinkAlphaProvider = if (staticPreview) ({ 1f }) else state.cursorAlphaProvider,
        mockPressFeedback = staticPreview
    )
}
