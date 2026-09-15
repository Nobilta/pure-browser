package com.mybrowser.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    var hasPresentedContent = false

    /** The surface presented last, so the next route can fade it out if the container changes. */
    var lastShape: SheetShape? = null
    private var owner: Any? = null
    private var back: (() -> Unit)? = null

    fun attach(owner: Any, back: () -> Unit) { this.owner = owner; this.back = back }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; back = null } }
    fun dismiss(fallback: () -> Unit) { (back ?: fallback)() }
}

/**
 * What this route needs to know about the window presenting it. Captured before
 * [LocalSheetWindow] is cleared, because a picker opened by this page gets its own window.
 */
internal class SheetPresentation(
    private val first: Boolean,
    private val previous: SheetShape?,
    /** 1 for a top-level destination, 2 for the single child a menu may push. */
    val depth: Int,
    private val publishShape: (SheetShape) -> Unit,
) {
    fun sceneFor(fullscreen: Boolean): SheetScene = when {
        first -> SheetScene.ENTER
        previous != null && previous.fullscreen != fullscreen -> SheetScene.CONTAINER
        else -> SheetScene.WITHIN
    }

    /** The surface this route replaced, non-null only while a container change settles. */
    fun replacedShape(fullscreen: Boolean): SheetShape? =
        previous?.takeIf { sceneFor(fullscreen) == SheetScene.CONTAINER }

    fun publish(shape: SheetShape) = publishShape(shape)
}

private val LocalSheetWindow = staticCompositionLocalOf<SheetWindowState?> { null }
private val LocalSheetInsets = staticCompositionLocalOf<WindowInsets?> { null }
private val LocalSheetPresentation = staticCompositionLocalOf {
    SheetPresentation(first = true, previous = null, depth = 1, publishShape = {})
}

/** Set by the route host: 1 for a top-level destination, 2 for a pushed child. */
internal val LocalSheetDepth = staticCompositionLocalOf { 1 }

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
        val first = remember(window) { !window.hasPresentedContent }
        val previous = remember(window) { window.lastShape }
        val depth = LocalSheetDepth.current
        SideEffect { window.hasPresentedContent = true }
        val dismiss by rememberUpdatedState(onDismissRequest)
        DisposableEffect(window, owner) {
            window.attach(owner) { dismiss() }
            onDispose { window.detach(owner) }
        }
        val publish: (SheetShape) -> Unit = remember(window) { { shape -> window.lastShape = shape } }
        CompositionLocalProvider(
            LocalSheetWindow provides null,
            LocalSheetPresentation provides SheetPresentation(first, previous, depth, publish),
            content = content,
        )
    }
}

/**
 * Runs this route's entrance and republishes the surface a later route may replace.
 * Both containers call it, so they cannot drift apart in rhythm or distance.
 */
@Composable
private fun sheetMotion(fullscreen: Boolean, color: Color, height: Int): SheetMotion {
    val presentation = LocalSheetPresentation.current
    val scene = presentation.sceneFor(fullscreen)
    val progress = rememberSheetMotion(scene)
    SideEffect { if (height > 0) presentation.publish(SheetShape(fullscreen, height, color)) }
    return SheetMotion(
        scene = scene,
        progress = progress.asState(),
        direction = if (presentation.depth > 1) 1f else -1f,
        replaced = presentation.replacedShape(fullscreen),
    )
}

/** A faded copy of the surface a route replaced, so switching containers does not pop. */
@Composable
private fun ReplacedSurface(shape: SheetShape, alpha: Float, sheetShape: Shape) {
    if (shape.fullscreen) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(shape.color))
    } else {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .height(with(LocalDensity.current) { shape.height.toDp() })
                .graphicsLayer { this.alpha = alpha }
                .background(shape.color, sheetShape))
        }
    }
}

/** Fixed edges and one mounted route; only the surface layer moves during entrance. */
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
        var surfaceHeight by remember { mutableIntStateOf(0) }
        val motion = sheetMotion(fullscreen = false, color = containerColor, height = surfaceHeight)
        val scene = motion.scene
        val progress = motion.progress
        val contentDistance = with(LocalDensity.current) { BrowserMotion.CONTENT_DISTANCE.toPx() }
        val scrim = MaterialTheme.colorScheme.scrim
        val sheetShape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp),
        )
        Box(Modifier.fillMaxSize()) {
            // The scrim keeps the page out of every transition: it fades in with the first
            // surface and then holds, so a route or container change never flashes the webpage.
            Box(Modifier.matchParentSize().drawBehind {
                val alpha = if (scene == SheetScene.ENTER) BrowserMotion.SCRIM_ALPHA * progress.value
                else BrowserMotion.SCRIM_ALPHA
                drawRect(scrim.copy(alpha = alpha))
            }
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .semantics {
                    contentDescription = dismissLabel
                    onClick { onDismissRequest(); true }
                })
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, 1f - progress.value, sheetShape) }
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding().padding(top = 16.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                        .onSizeChanged { surfaceHeight = it.height }
                        .graphicsLayer {
                            // A sheet is a solid surface, not a translucent page floating
                            // over the website. Only translate its layer; never its scrim.
                            alpha = if (surfaceHeight == 0) 0f else 1f
                            translationY = if (scene == SheetScene.WITHIN) 0f
                            else (1f - progress.value) * surfaceHeight
                        }.then(modifier),
                    shape = sheetShape,
                    color = containerColor,
                ) {
                    Column(Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)).padding(top = 12.dp)
                        .graphicsLayer {
                            // The surface stays opaque under incoming content, so a submenu
                            // never flashes the layer behind it.
                            alpha = if (scene == SheetScene.ENTER) 1f else progress.value
                            translationY = if (scene == SheetScene.ENTER) 0f
                            else (1f - progress.value) * contentDistance * motion.direction
                        }, content = content)
                }
            }
        }
    }
}

@Composable
internal fun BrowserFullscreenSheet(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    SheetWindowContent(onDismissRequest) {
        val containerColor = MaterialTheme.colorScheme.surface
        var height by remember { mutableIntStateOf(0) }
        val motion = sheetMotion(fullscreen = true, color = containerColor, height = height)
        val scene = motion.scene
        val progress = motion.progress
        val contentDistance = with(LocalDensity.current) { BrowserMotion.CONTENT_DISTANCE.toPx() }
        val scrim = MaterialTheme.colorScheme.scrim
        val sheetShape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp),
        )
        Box(Modifier.fillMaxSize().onSizeChanged { height = it.height }) {
            // Hidden behind the opaque page at rest; it carries the transition when the
            // destination swaps containers, and dims the page while a full-screen page enters.
            Box(Modifier.matchParentSize().drawBehind {
                val alpha = if (scene == SheetScene.ENTER) BrowserMotion.SCRIM_ALPHA * progress.value
                else BrowserMotion.SCRIM_ALPHA
                drawRect(scrim.copy(alpha = alpha))
            })
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, 1f - progress.value, sheetShape) }
            Surface(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (height == 0) 0f else 1f
                translationY = if (scene == SheetScene.WITHIN) 0f else (1f - progress.value) * height
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (scene == SheetScene.ENTER) 1f else progress.value
                    translationY = if (scene == SheetScene.ENTER) 0f
                    else (1f - progress.value) * contentDistance * motion.direction
                }) { content() }
            }
        }
    }
}
