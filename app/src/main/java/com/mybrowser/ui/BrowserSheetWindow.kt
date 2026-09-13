package com.mybrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mybrowser.R

/** The visible page owns Back; changing a route never tears down the dialog window. */
private class SheetWindowState {
    private var owner: Any? = null
    private var back: (() -> Unit)? = null

    fun attach(owner: Any, back: () -> Unit) { this.owner = owner; this.back = back }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; back = null } }
    fun dismiss(fallback: () -> Unit) { (back ?: fallback)() }
}

private val LocalSheetWindow = staticCompositionLocalOf<SheetWindowState?> { null }
private val LocalSheetInsets = staticCompositionLocalOf<WindowInsets?> { null }

@Composable
internal fun browserSheetInsets(): WindowInsets = LocalSheetInsets.current ?: WindowInsets.safeDrawing

@Composable
internal fun BrowserSheetWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val window = remember { SheetWindowState() }
    // Older Android versions can report zero system insets inside a dialog.
    val contentInsets = browserSheetInsets()
    Dialog(onDismissRequest = { window.dismiss(onDismissRequest) }, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        dismissOnClickOutside = false,
    )) {
        ApplySheetSystemBars(fullscreen = true)
        CompositionLocalProvider(LocalSheetWindow provides window, LocalSheetInsets provides contentInsets) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun SheetWindowContent(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val window = LocalSheetWindow.current
    if (window == null) {
        BrowserSheetWindow(onDismissRequest) { SheetWindowContent(onDismissRequest, content) }
    } else {
        val owner = remember { Any() }
        val dismiss by rememberUpdatedState(onDismissRequest)
        DisposableEffect(window, owner) {
            window.attach(owner) { dismiss() }
            onDispose { window.detach(owner) }
        }
        // A picker or confirmation opened by this page needs its own input owner.
        CompositionLocalProvider(LocalSheetWindow provides null, content = content)
    }
}

/** Fixed sheets have no drag anchors or enter/exit animation to expose a previous page. */
@Composable
internal fun BrowserBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetWindowContent(onDismissRequest) {
        val dismissLabel = stringResource(R.string.ui_close)
        val safeInsets = browserSheetInsets()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .32f))
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .semantics {
                    contentDescription = dismissLabel
                    onClick { onDismissRequest(); true }
                })
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().then(modifier),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = containerColor,
                ) {
                    Column(Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)), content = content)
                }
            }
        }
    }
}

@Composable
internal fun BrowserFullscreenSheet(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    SheetWindowContent(onDismissRequest, content)
}
