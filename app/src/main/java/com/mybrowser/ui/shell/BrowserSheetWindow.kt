package com.mybrowser.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.first

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
    /** True for the first page of a window: the window's own entrance carries it in. */
    val first: Boolean,
    private val previous: SheetShape?,
    /** 1 for a top-level destination, 2 for the single child a menu may push. */
    val depth: Int,
    private val publishShape: (SheetShape) -> Unit,
) {
    /** The surface this route replaced, non-null only while a container change settles. */
    fun replacedShape(fullscreen: Boolean): SheetShape? =
        previous?.takeIf { previous.fullscreen != fullscreen }

    fun publish(shape: SheetShape) = publishShape(shape)
}

private val LocalSheetWindow = staticCompositionLocalOf<SheetWindowState?> { null }
private val LocalSheetInsets = staticCompositionLocalOf<WindowInsets?> { null }

/**
 * Reports that the window's surface has been laid out at least once.
 *
 * A dialog window is created on the frame the route is set, and its content is measured a frame
 * or two later. Without this signal the entrance animates over that invisible stretch and then
 * snaps what is left of it, which is what makes a sheet feel like it jumps.
 */
private val LocalSheetMeasured = staticCompositionLocalOf<() -> Unit> { {} }

/** The window's own progress: 0 while off the edge, 1 while presented. */
private val LocalSheetVisibility = staticCompositionLocalOf<State<Float>> { mutableStateOf(1f) }
private val LocalSheetPresentation = staticCompositionLocalOf {
    SheetPresentation(first = true, previous = null, depth = 1, publishShape = {})
}

/** Set by the route host: 1 for a top-level destination, 2 for a pushed child. */
internal val LocalSheetDepth = staticCompositionLocalOf { 1 }

@Composable
internal fun browserSheetInsets(): WindowInsets = LocalSheetInsets.current ?: WindowInsets.safeDrawing

@Composable
internal fun BrowserSheetWindow(
    visibility: State<Float>,
    onDismissRequest: () -> Unit,
    onMeasured: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val window = remember { SheetWindowState() }
    // Older Android versions can report zero system insets inside a dialog.
    val contentInsets = browserSheetInsets()
    Dialog(onDismissRequest = { window.dismiss(onDismissRequest) }, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        dismissOnClickOutside = false,
    )) {
        ApplySheetSystemBars(fullscreen = true)
        CompositionLocalProvider(
            LocalSheetWindow provides window,
            LocalSheetInsets provides contentInsets,
            LocalSheetVisibility provides visibility,
            LocalSheetMeasured provides onMeasured,
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun SheetWindowContent(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val window = LocalSheetWindow.current
    if (window == null) {
        // A window a page opens by itself (a picker) has nothing above it to drive its progress, so
        // it runs its own entrance. Its exit is still the window animation: only the route host
        // retains a page after the route is gone. Like the route host, it waits for the first
        // layout so the entrance is not spent on an unmeasured, invisible surface.
        val visibility = remember { Animatable(0f) }
        var measured by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            snapshotFlow { measured }.first { it }
            visibility.animateTo(1f, BrowserMotion.sheetEnter)
        }
        BrowserSheetWindow(visibility.asState(), onDismissRequest, onMeasured = { measured = true }) {
            SheetWindowContent(onDismissRequest, content)
        }
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
 * Runs this page's own transition and republishes the surface a later route may replace.
 * Both containers call it, so they cannot drift apart in rhythm or distance.
 */
@Composable
private fun sheetMotion(fullscreen: Boolean, color: Color, height: Int): SheetMotion {
    val presentation = LocalSheetPresentation.current
    val visibility = LocalSheetVisibility.current
    // The window carries the first page in and out. A page that replaced another one inside the
    // same window animates its own content instead, so one operation is never composed twice.
    val content = remember { Animatable(if (presentation.first) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (content.value < 1f) content.animateTo(1f, BrowserMotion.pageChange)
    }
    SideEffect { if (height > 0) presentation.publish(SheetShape(fullscreen, height, color)) }
    return SheetMotion(
        visibility = visibility,
        content = content.asState(),
        direction = if (presentation.depth > 1) 1f else -1f,
        replaced = presentation.replacedShape(fullscreen),
    )
}

/** One sheet shape for both containers, so a container change swaps rounded edges only. */
@Composable
private fun sheetShape(): Shape = MaterialTheme.shapes.extraLarge.copy(
    bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp),
)

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

/** Fixed edges and one mounted route; only the surface layer moves while the window animates. */
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
        val measured = LocalSheetMeasured.current
        var surfaceHeight by remember { mutableIntStateOf(0) }
        val motion = sheetMotion(fullscreen = false, color = containerColor, height = surfaceHeight)
        val contentDistance = with(LocalDensity.current) { BrowserMotion.PAGE_CHANGE_DISTANCE.toPx() }
        val scrim = MaterialTheme.colorScheme.scrim
        val shape = sheetShape()
        Box(Modifier.fillMaxSize()) {
            // The scrim keeps the page out of every transition: it fades with the window progress
            // and holds while routes change, so a change never flashes the webpage.
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(scrim.copy(alpha = BrowserMotion.SCRIM_ALPHA * motion.visibility.value))
            }
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .semantics {
                    contentDescription = dismissLabel
                    onClick { onDismissRequest(); true }
                })
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, 1f - motion.content.value, shape) }
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding().padding(top = 16.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                        .onSizeChanged { surfaceHeight = it.height; if (it.height > 0) measured() }
                        .graphicsLayer {
                            // A sheet is a solid surface, not a translucent page floating over the
                            // website: only its layer moves, and it leaves along the edge it came from.
                            alpha = if (surfaceHeight == 0) 0f else 1f
                            translationY = (1f - motion.visibility.value) * surfaceHeight
                        }.then(modifier),
                    shape = shape,
                    color = containerColor,
                ) {
                    Column(Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)).padding(top = 12.dp)
                        .graphicsLayer {
                            // The surface stays opaque under incoming content, so a submenu never
                            // flashes the layer behind it.
                            alpha = pageChangeAlpha(motion.content.value)
                            translationY = (1f - motion.content.value) * contentDistance * motion.direction
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
        val measured = LocalSheetMeasured.current
        var height by remember { mutableIntStateOf(0) }
        val motion = sheetMotion(fullscreen = true, color = containerColor, height = height)
        val contentDistance = with(LocalDensity.current) { BrowserMotion.PAGE_CHANGE_DISTANCE.toPx() }
        val offset = with(LocalDensity.current) { BrowserMotion.FULLSCREEN_OFFSET.toPx() }
        val scrim = MaterialTheme.colorScheme.scrim
        val shape = sheetShape()
        Box(Modifier.fillMaxSize().onSizeChanged { height = it.height; if (it.height > 0) measured() }) {
            // Hidden behind the opaque page at rest; it dims the page while a full-screen page
            // enters or leaves, with the same progress as the surface.
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(scrim.copy(alpha = BrowserMotion.SCRIM_ALPHA * motion.visibility.value))
            })
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, 1f - motion.content.value, shape) }
            Surface(Modifier.fillMaxSize().graphicsLayer {
                // A page covers the window instead of sliding through it: it fades in from a short
                // offset, so a settings or bookmarks page does not drag the whole screen.
                alpha = if (height == 0) 0f else motion.visibility.value
                translationY = (1f - motion.visibility.value) * offset
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = pageChangeAlpha(motion.content.value)
                    translationY = (1f - motion.content.value) * contentDistance * motion.direction
                }) { content() }
            }
        }
    }
}
