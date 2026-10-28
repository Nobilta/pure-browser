package com.mybrowser.ui.shell

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The activity's feedback state, so a dialog-hosted sheet can show the same snackbar.
 *
 * Sheets are separate Dialog windows: a host only in the activity window would sit behind the
 * scrim and a settings confirmation would be invisible. Both hosts observe one state; the visible
 * one is the sheet's while it is open and the activity's afterwards.
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * SnackbarHost with the swipe-to-dismiss the Material spec describes but the component leaves
 * to the caller. A horizontal drag moves the bar; releasing past 80dp dismisses the current
 * snackbar. Taps still reach the action because the detector waits for horizontal slop.
 */
@Composable
fun SwipeDismissSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember(hostState) { mutableFloatStateOf(0f) }
    val current = hostState.currentSnackbarData
    LaunchedEffect(current) { offsetX = 0f }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        modifier = modifier.pointerInput(hostState, current) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val dismissed = abs(offsetX) >= threshold
                    if (dismissed && hostState.currentSnackbarData === current) current?.dismiss()
                    offsetX = 0f
                },
                onDragCancel = { offsetX = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount
                },
            )
        },
    ) {
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier.graphicsLayer {
                translationX = offsetX
                // Fade a little while dragging, so the gesture reads even over a dark page.
                alpha = (1f - abs(offsetX) / (threshold * 3f)).coerceIn(0.4f, 1f)
            },
        )
    }
}
