package com.mybrowser.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

/** One queued message. Actions are never dropped, so an undo cannot starve behind status text. */
internal data class FeedbackMessage(
    override val message: String,
    override val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Results that must remain readable and return if interrupted by an action. */
    val important: Boolean = false,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration
        get() = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/**
 * The order messages are shown in, independent of Compose.
 *
 * The old implementation dismissed whatever was on screen before showing the next message, which
 * lost both halves of a burst (settings import reports twice) and could throw away an undo that
 * had promised the user ten seconds. This queue keeps the visible message visible and decides
 * what may be dropped instead: duplicates are ignored, a message with an action is never dropped,
 * and ordinary status text is capped while an undo is on screen.
 */
internal class FeedbackQueue(private val maxOrdinary: Int = 3) {
    private val items = ArrayDeque<FeedbackMessage>()

    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()

    fun hasAction(): Boolean = items.any { it.actionLabel != null }

    /**
     * Adds [message], unless it is a duplicate of the visible or the queued one.
     *
     * [visibleMessage] and [visibleAction] describe what the host is showing right now; when that
     * is an action-bearing message, an ordinary newcomer replaces the waiting ordinary slot
     * rather than queueing behind the undo, so the user sees one current status afterwards.
     */
    fun add(
        message: FeedbackMessage,
        visibleMessage: String?,
        visibleAction: String?,
    ): Boolean {
        if (message.actionLabel == null) {
            if (visibleMessage == message.message) return false
            if (items.any { it.actionLabel == null && it.message == message.message }) return false
            if (visibleAction != null) items.removeAll { it.actionLabel == null && !it.important }
        }
        items.addLast(message)
        while (items.count { it.actionLabel == null && !it.important } > maxOrdinary) {
            val dropped = items.firstOrNull { it.actionLabel == null && !it.important } ?: break
            items.remove(dropped)
        }
        return true
    }

    /** Put an interrupted result back before other results; pending actions still go first. */
    fun restoreInterrupted(message: FeedbackMessage) {
        if (message.important && message.actionLabel == null &&
            items.none { it.actionLabel == null && it.message == message.message }) {
            items.addFirst(message)
        }
    }

    fun removeFirst(): FeedbackMessage? {
        val action = items.firstOrNull { it.actionLabel != null }
        if (action != null) {
            items.remove(action)
            return action
        }
        return items.removeFirstOrNull()
    }
}

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
        ) { data -> BrowserSnackbar(data) }
    }
}

/**
 * The app's snackbar, styled as a floating message rather than Material's full-width bar.
 *
 * Material's default snackbar uses `inverseSurface` and fills the width, which on a phone reads
 * as a black slab across the page — the shape, not the mechanism, was what looked wrong. This
 * keeps the snackbar (actions, TalkBack, one queue) and narrows it to a pill that wraps its text:
 * a light surface over a light page, black in the dark theme, with enough elevation and an
 * outline to stay legible above the toolbar. Both the plain message and the one with an action
 * keep the same pill: the action adds a button and a second line, never a different shape.
 */
@Composable
private fun BrowserSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val action = data.visuals.actionLabel
    val important = (data.visuals as? FeedbackMessage)?.important == true
    // One shape for both: a stadium clamps to half the height, so a plain message and a taller
    // action-bearing one read as the same component rather than two different notifications.
    val shape = CircleShape
    val container = if (dark) Color.Black else colors.surfaceContainerHighest
    val outline = if (dark) colors.outlineVariant.copy(alpha = 0.55f) else colors.outlineVariant
    Surface(
        modifier = modifier.widthIn(max = 560.dp).border(1.dp, outline, shape),
        shape = shape,
        color = container,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            // The action button used to add its own 40dp minimum, which made the undo message
            // visibly taller than a status message. One 48dp floor, with the button fitting inside
            // it, keeps both readings on the same bar; only a genuinely two-line message grows.
            modifier = Modifier.heightIn(min = 48.dp).padding(
                start = 20.dp,
                end = if (action == null) 20.dp else 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = if (important) Int.MAX_VALUE else 2,
                overflow = if (important) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            if (action != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { data.performAction() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(action, color = colors.primary)
                }
            }
        }
    }
}
