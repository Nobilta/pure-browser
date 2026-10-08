package com.mybrowser.ui.shell

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mybrowser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    /** Closes this window the way Back does; a sheet's dismissal drag ends here too. */
    val dismiss: () -> Unit,
) {
    /** The surface this route replaced, non-null only while a container change settles. */
    fun replacedShape(fullscreen: Boolean): SheetShape? =
        previous?.takeIf { previous.fullscreen != fullscreen }

    fun publish(shape: SheetShape) = publishShape(shape)
}

private val LocalSheetWindow = staticCompositionLocalOf<SheetWindowState?> { null }
private val LocalSheetInsets = staticCompositionLocalOf<WindowInsets?> { null }

/**
 * A sheet that can be followed by a back gesture.
 *
 * The window owns the progress and the surface owns the drag offset, so a predictive gesture goes
 * through both exactly as a finger drag does — the two are the same movement, one driven by the
 * system's cancellable callback instead of by touch.
 */
internal interface SheetBackGesture {
    /** Takes the gesture at [progress] (0..1 of the sheet's height) and a speed in the same units per second. */
    fun start(progress: Float, velocity: Float): Float?

    /** Continues a gesture already started, in the same units. */
    fun move(progress: Float, velocity: Float): Boolean

    /** The gesture completed: close the sheet, keeping the speed it was thrown at. */
    fun commit()

    /**
     * Close the sheet outright, for the press the handler consumed without a gesture to follow.
     *
     * A Back that arrives as a single completion — a key event, or a device that has no predictive
     * gesture — is still the user asking to close this sheet, and the handler has already taken the
     * press; this is what answers it.
     */
    fun close()

    /** The gesture was let go or taken away: put the sheet back where it was. */
    fun cancel()
}

/**
 * Reports that the window's surface has been laid out at least once.
 *
 * A dialog window is created on the frame the route is set, and its content is measured a frame
 * or two later. Without this signal the entrance animates over that invisible stretch and then
 * snaps what is left of it, which is what makes a sheet feel like it jumps.
 */
private val LocalSheetMeasured = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The window's own motion.
 *
 * One progress, not several: the dim behind the surface and the surface's own position are one
 * movement, and driving them from one value is what keeps them reading as one. Both ways of giving
 * the dim a clock of its own were measured on device and both showed: on a faster spring it arrived
 * well before the surface it was dimming for, and on a linear ramp of its own the surface reached
 * 81% of its travel while the dim had reached 19%.
 *
 * [visibility] is an [Animatable] rather than plain state so a gesture can hand over what it was
 * doing: a dismissal drag leaves the position it reached and the speed it was thrown at, and the
 * exit picks both up. Without that hand-off, letting go of a flicked sheet would stop it dead and
 * then start it moving again — the seam this class exists to remove.
 */
internal class SheetWindowProgress(initial: Float = 0f) {
    val visibility = Animatable(initial)

    /**
     * Whether the mounted container covers the window. A bottom sheet and a full-window page
     * travel on different tokens, and the host has to choose before the window animation starts;
     * the container publishes this as it mounts, which is always earlier, because the window
     * waits for the surface's first layout.
     */
    var fullscreen by mutableStateOf(false)

    /**
     * The speed a gesture ended at, waiting for the movement that will continue it. Only a spring
     * can start from a speed, so carrying this across is what makes letting go of a thrown sheet
     * seamless; the host reads it once and clears it.
     */
    private var handedOver: Float? = null

    /** Leaves the speed a gesture ended at, in progress units per second, for the next movement. */
    fun handOff(velocity: Float) { handedOver = velocity }

    /** Takes the handed-over speed and clears it, so only the movement right after a gesture sees it. */
    fun takeCarried(): Float? = handedOver.also { handedOver = null }
}

private val LocalSheetProgress =
    staticCompositionLocalOf { SheetWindowProgress(initial = 1f).also { it.fullscreen = true } }
private val LocalSheetPresentation = staticCompositionLocalOf {
    SheetPresentation(first = true, previous = null, depth = 1, publishShape = {}, dismiss = {})
}

/** Set by the route host: 1 for a top-level destination, 2 for a pushed child. */
internal val LocalSheetDepth = staticCompositionLocalOf { 1 }

@Composable
internal fun browserSheetInsets(): WindowInsets = LocalSheetInsets.current ?: WindowInsets.safeDrawing

@Composable
internal fun BrowserSheetWindow(
    progress: SheetWindowProgress,
    onDismissRequest: () -> Unit,
    onMeasured: () -> Unit = {},
    /**
     * True when the caller animates the exit in Compose and unmounts this window only afterwards.
     *
     * The window carries its own exit animation for the surfaces that leave by being unmounted
     * with nothing left composed to run a spring on — a picker a page opens for itself. A sheet a
     * route host presents is not one of those: the host animates it out first, so the window
     * animation would play a second time on content that has already left the screen, holding the
     * window (and its scrim) alive for another 250 ms after the user has seen it go.
     */
    composeDrivenExit: Boolean,
    content: @Composable () -> Unit,
) {
    val window = remember { SheetWindowState() }
    // Older Android versions can report zero system insets inside a dialog.
    val contentInsets = browserSheetInsets()
    Dialog(onDismissRequest = { window.dismiss(onDismissRequest) }, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        // True for the theme it selects, not for the behaviour: Compose hands an edge-to-edge
        // dialog a theme carrying `backgroundDimEnabled`, which makes the platform dim the page the
        // moment the window appears and then drop that dim as soon as the app takes over — one dark
        // frame, one light one, then the app's own fade. That is the flicker on opening a sheet.
        // The app draws the dim itself, so it asks for the theme without one; Material 3's own
        // edge-to-edge dialog theme leaves it off for the same reason.
        decorFitsSystemWindows = true,
        dismissOnClickOutside = false,
    )) {
        ApplySheetSystemBars(fullscreen = true, windowExitAnimation = !composeDrivenExit)
        CompositionLocalProvider(
            LocalSheetWindow provides window,
            LocalSheetInsets provides contentInsets,
            LocalSheetProgress provides progress,
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
        val progress = remember { SheetWindowProgress() }
        var measured by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            snapshotFlow { measured }.first { it }
            progress.visibility.animateTo(1f, progress.arriveSpec())
        }
        BrowserSheetWindow(progress, onDismissRequest, onMeasured = { measured = true },
            composeDrivenExit = false) {
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
            LocalSheetPresentation provides SheetPresentation(first, previous, depth, publish, dismiss),
            content = content,
        )
    }
}

/**
 * The curves a window travels on, resolved without a composition read.
 *
 * The host has to choose one from inside the coroutine that runs the animation, by which point the
 * container has already published whether it covers the window — a full-window page travels further
 * than a panel and gets a correspondingly longer token. Keeping the choice in a plain function lets
 * that coroutine make it; a composition read would not be available to it.
 */
internal fun SheetWindowProgress.arriveSpec(): FiniteAnimationSpec<Float> =
    if (fullscreen) BrowserMotion.windowArrive() else BrowserMotion.panelArrive()

internal fun SheetWindowProgress.departSpec(): FiniteAnimationSpec<Float> =
    if (fullscreen) BrowserMotion.windowDepart() else BrowserMotion.panelDepart()

/**
 * Runs this page's own transition and republishes the surface a later route may replace.
 * Both containers call it, so they cannot drift apart in rhythm or distance.
 */
@Composable
private fun sheetMotion(fullscreen: Boolean, color: Color, height: Int): SheetMotion {
    val presentation = LocalSheetPresentation.current
    val progress = LocalSheetProgress.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentHeight by rememberUpdatedState(height)
    // The window carries the first page in and out. A page that replaced another one inside the
    // same window animates its own content instead, so one operation is never composed twice.
    val content = remember { Animatable(if (presentation.first) 1f else 0f) }
    // Alpha keeps its own clock rather than a share of the travel's: fading and travelling are two
    // jobs with two curves, and one progress scaled to fake the other is what previously made a
    // page's content fully opaque while it was still moving.
    val contentFade = remember { Animatable(if (presentation.first) 1f else 0f) }
    SideEffect {
        progress.fullscreen = fullscreen
        if (height > 0) presentation.publish(SheetShape(fullscreen, height, color))
    }
    LaunchedEffect(Unit) {
        if (content.value < 1f) {
            launch { contentFade.animateTo(1f, BrowserMotion.contentCross()) }
            content.animateTo(1f, BrowserMotion.pageArrive())
        }
    }
    // A full-window page is not something a user drags away; a panel is.
    val drag: SheetDrag? = if (fullscreen) null else remember(progress) {
        sheetDrag(
            scope = scope,
            density = density,
            height = { currentHeight },
            progress = progress,
            onDismiss = presentation.dismiss,
            // The finger wrote the shape, so letting go settles on a spring rather than a curve.
            settleSpec = { BrowserMotion.resume() },
        )
    }
    // A back gesture on a sheet the user can throw away is the same movement as a finger drag, one
    // driven by the system's cancellable callback: the surface follows the gesture and letting go
    // either commits it or springs it back. Enabled only for a settled panel, because an enabled
    // handler consumes the gesture — claiming one it cannot follow would swallow the press and leave
    // the user with nothing happening at all.
    val backGesture = drag?.backGesture?.invoke()
    // Only a settled panel claims the back gesture. A handler that is enabled consumes whatever it
    // is given, and this one cannot know before the press whether a predictive gesture is coming —
    // so it claims nothing while the surface is arriving or leaving, where the page that is leaving
    // would otherwise be dismissed a second time.
    //
    // Read through derivedStateOf: the progress is an Animatable, so reading it directly here would
    // recompose this whole container — the sheet's content included — on every frame of every
    // open/close. The derived value changes only when the answer does.
    val settled by remember(progress) {
        derivedStateOf { progress.visibility.value >= 1f && progress.visibility.targetValue >= 1f }
    }
    PredictiveBackHandler(enabled = backGesture != null && settled) { back: Flow<BackEventCompat> ->
        // The gesture's speed is not reported, so it is derived: progress per second between two
        // samples. That is the unit the window's hand-off already expects, so nothing here has to
        // know how tall the sheet is.
        var lastProgress = 0f
        var lastFrame = 0L
        // The first sample starts the movement and every one after it moves the sheet. The collect
        // never returns normally — the dispatcher cancels it when the gesture ends — so this flag,
        // not the return value, is what says whether there is a surface to put back.
        var following = false
        var committed = false
        try {
            back.collect { event: BackEventCompat ->
                val seconds = if (lastFrame == 0L) 0f else (event.frameTimeMillis - lastFrame) / 1000f
                val velocity = if (seconds > 0f) (event.progress - lastProgress) / seconds else 0f
                lastProgress = event.progress
                lastFrame = event.frameTimeMillis
                val surface = backGesture ?: return@collect
                if (!following) following = surface.start(event.progress, velocity) != null
                else surface.move(event.progress, velocity)
            }
            committed = true
        } finally {
            // Three endings, and they are not the same:
            //  - no progress arrived at all: this was a plain Back (a key, or a device without the
            //    predictive gesture), and the sheet still has to close — the press was consumed here
            //    rather than reaching the Activity, so falling through would swallow it;
            //  - progress arrived and the flow completed: the gesture finished, close as thrown;
            //  - progress arrived and the flow was cancelled: the user let go without completing, or
            //    the composition went away, and the surface goes back where it was.
            val surface = backGesture
            when {
                surface == null -> Unit
                !following -> surface.close()
                committed -> surface.commit()
                else -> surface.cancel()
            }
        }
    }

    // The route can close under an active gesture — a Back press, a toolbar action. The finger no
    // longer owns the position then, so the drag lets go of it instead of springing back.
    DisposableEffect(drag) {
        onDispose { drag?.cancel() }
    }
    return SheetMotion(
        visibility = progress.visibility.asState(),
        content = content.asState(),
        contentFade = contentFade.asState(),
        direction = if (presentation.depth > 1) 1f else -1f,
        replaced = presentation.replacedShape(fullscreen),
        drag = drag,
    )
}

/** Offset the surface is drawn at: the window's own position plus whatever a finger has added. */
private fun sheetTranslation(visibility: Float, height: Int, dragOffset: Float): Float =
    (1f - visibility) * height + dragOffset

/**
 * A finger's hold on a sheet's position.
 *
 * The surface draws at the window's position plus this offset, so a drag never has to reach
 * inside an animation to move the sheet. Letting go either settles the offset back to zero or
 * folds it into the window's progress and asks the route to close — and the fold has to be
 * arithmetically exact, because the two ways of expressing the same position must agree at the
 * moment of hand-off or the surface jumps.
 */
private fun sheetDrag(
    scope: CoroutineScope,
    density: Density,
    height: () -> Int,
    progress: SheetWindowProgress,
    onDismiss: () -> Unit,
    settleSpec: () -> FiniteAnimationSpec<Float>,
): SheetDrag {
    val offset = mutableFloatStateOf(0f)
    var dragging = false
    var settling: Job? = null
    val settle = Animatable(0f)
    val scale = density.density
    // The predictive-back gesture writes the same offset a finger does, so the surface cannot tell
    // the two apart: following the system's progress, springing back on release, and handing the
    // release speed to the window's exit are the same mechanics either way.
    var backGesture: SheetBackGesture? = null
    var backVelocity = 0f
    // Only a settled sheet can be followed: one still arriving or already leaving is driven by the
    // window, and a gesture taking it over as well would put two things on the same position.
    fun ready() = height().let { it > 0 } && progress.visibility.value >= 1f &&
        progress.visibility.targetValue >= 1f
    backGesture = object : SheetBackGesture {
        override fun start(progress: Float, velocity: Float): Float? {
            if (!ready()) return null
            settling?.cancel()
            dragging = false
            backVelocity = velocity
            offset.floatValue = (progress.coerceIn(0f, 1f) * height()).coerceIn(0f, height().toFloat())
            return offset.floatValue
        }

        override fun move(progress: Float, velocity: Float): Boolean {
            // A gesture that has been resolved while still under the finger — a second Back press —
            // must not keep writing the position it no longer owns. [ready] is what says so: a
            // commit snaps the window progress below 1 and a plain Back dismisses outright, and both
            // leave the surface unsettled. (The object holding this method is itself the non-null
            // value of the enclosing reference, so testing that reference here proved nothing.)
            if (!ready()) return false
            backVelocity = velocity
            offset.floatValue = (progress.coerceIn(0f, 1f) * height()).coerceIn(0f, height().toFloat())
            return true
        }

        override fun close() {
            if (offset.floatValue != 0f) {
                // Nothing can resume a position written by a gesture that never reported, so the
                // surface is put back first: the window's exit measures from rest.
                offset.floatValue = 0f
            }
            onDismiss()
        }

        override fun commit() {
            val limit = height().toFloat()
            if (limit <= 0f) { offset.floatValue = 0f; return }
            val share = (offset.floatValue / limit).coerceIn(0f, 1f)
            val thrown = backVelocity
            scope.launch {
                // The same hand-off a released finger performs: the position is folded into the
                // window's progress and the speed is left for the exit to start from.
                settle.snapTo(offset.floatValue)
                progress.visibility.snapTo(1f - share)
                // The speed is already in progress units per second, which is what the window wants.
                progress.handOff(thrown)
                offset.floatValue = 0f
                onDismiss()
            }
        }

        override fun cancel() {
            settling?.cancel()
            settling = scope.launch {
                settle.snapTo(offset.floatValue)
                settle.animateTo(0f, settleSpec()) { offset.floatValue = value }
            }
        }
    }
    return SheetDrag(
        offset = offset,
        onDrag = { delta ->
            val limit = height().toFloat()
            // A sheet still arriving or already leaving is driven by the window; the finger may
            // only take over a surface that has settled, or the two would move it at once.
            if (limit <= 0f || progress.visibility.value < 1f || progress.visibility.targetValue < 1f) 0f
            else {
                val from = offset.floatValue
                val to = (from + delta).coerceIn(0f, limit)
                settling?.cancel()
                dragging = true
                offset.floatValue = to
                to - from
            }
        },
        onRelease = { velocity ->
            if (dragging) {
                dragging = false
                val limit = height().toFloat()
                when {
                    limit <= 0f -> offset.floatValue = 0f
                    // Thrown down hard enough, or pulled far enough: dismiss from wherever the
                    // gesture left the surface, at the speed it was thrown.
                    velocity > scale * BrowserMotion.DISMISS_VELOCITY_DP ||
                        offset.floatValue > limit * BrowserMotion.DISMISS_TRAVEL_SHARE -> {
                        val share = (offset.floatValue / limit).coerceIn(0f, 1f)
                        scope.launch {
                            progress.visibility.snapTo(1f - share)
                            progress.handOff(velocity / limit)
                            offset.floatValue = 0f
                            onDismiss()
                        }
                    }
                    else -> settling = scope.launch {
                        settle.snapTo(offset.floatValue)
                        settle.animateTo(0f, settleSpec()) { offset.floatValue = value }
                    }
                }
            }
        },
        onCancel = {
            settling?.cancel()
            dragging = false
            offset.floatValue = 0f
        },
    ).also { drag -> drag.backGesture = { backGesture } }
}

/** One sheet shape for both containers, so a container change swaps rounded edges only. */
@Composable
private fun sheetShape(): Shape = MaterialTheme.shapes.extraLarge.copy(
    bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp),
)

/** A faded copy of the surface a route replaced, so switching containers does not pop. */
@Composable
private fun ReplacedSurface(shape: SheetShape, alpha: () -> Float, sheetShape: Shape) {
    // The alpha is a provider rather than a value on purpose: reading the animation's state here
    // would observe it in composition, and this call sits next to the whole page, so the entire
    // sheet would recompose on every frame of a route change. Inside the layer it is a draw-time
    // read and only the layer's own properties are rewritten.
    if (shape.fullscreen) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }.background(shape.color))
    } else {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .height(with(LocalDensity.current) { shape.height.toDp() })
                .graphicsLayer { this.alpha = alpha() }
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
        val drag = motion.drag
        // A fling that has run out of content at the top of the sheet belongs to the sheet, which
        // is how a long list is dismissed without first having to find its edge: the child reports
        // what it could not use and the sheet takes the rest.
        val nestedScroll = remember(drag) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || drag == null) return Offset.Zero
                    val taken = drag.drag(available.y)
                    return if (taken == 0f) Offset.Zero else Offset(0f, taken)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (drag == null || available.y <= 0f) return Velocity.Zero
                    drag.release(available.y)
                    return available
                }
            }
        }
        val draggable = rememberDraggableState { delta -> drag?.drag(delta) }
        Box(Modifier.fillMaxSize()) {
            // The scrim keeps the page out of every transition: it fades with the window progress
            // and holds while routes change, so a change never flashes the webpage. It reads that
            // one progress rather than carrying its own, so the dim and the surface it is dimming
            // for always arrive together.
            // Solid colour in a layer, and only the layer's alpha moves. Reading the progress inside
            // a draw lambda instead re-records the full-window dim on every frame of the animation,
            // which is real work on the two tallest sheets in the app.
            Box(Modifier.matchParentSize()
                .graphicsLayer { alpha = motion.visibility.value.coerceIn(0f, 1f) }
                .background(scrim.copy(alpha = BrowserMotion.SCRIM_ALPHA))
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .semantics {
                    contentDescription = dismissLabel
                    onClick { onDismissRequest(); true }
                })
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, { 1f - motion.content.value }, shape) }
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding().padding(top = 16.dp)) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                        .onSizeChanged { surfaceHeight = it.height; if (it.height > 0) measured() }
                        .nestedScroll(nestedScroll)
                        .draggable(
                            state = draggable,
                            orientation = Orientation.Vertical,
                            onDragStopped = { drag?.release(it) },
                        )
                        .graphicsLayer {
                            // A sheet is a solid surface, not a translucent page floating over the
                            // website: only its layer moves, and it leaves along the edge it came
                            // from. A gesture adds its own offset on top of the window's so the
                            // surface is only ever moved by one thing at a time.
                            alpha = if (surfaceHeight == 0) 0f else 1f
                            translationY = sheetTranslation(
                                motion.visibility.value, surfaceHeight, drag?.offset?.value ?: 0f,
                            )
                        }.then(modifier),
                    shape = shape,
                    color = containerColor,
                ) {
                    Column(Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)).padding(top = 12.dp)
                        .graphicsLayer {
                            // The surface stays opaque under incoming content, so a submenu never
                            // flashes the layer behind it.
                            alpha = motion.contentFade.value
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
            // enters or leaves, from the surface's own progress so the two stay together.
            Box(Modifier.matchParentSize()
                .graphicsLayer { alpha = motion.visibility.value.coerceIn(0f, 1f) }
                .background(scrim.copy(alpha = BrowserMotion.SCRIM_ALPHA)))
            motion.replaced?.let { replaced -> ReplacedSurface(replaced, { 1f - motion.content.value }, shape) }
            Surface(Modifier.fillMaxSize().graphicsLayer {
                // A page covers the window instead of sliding through it: it fades in from a short
                // offset, so a settings or bookmarks page does not drag the whole screen.
                alpha = if (height == 0) 0f else motion.visibility.value
                translationY = (1f - motion.visibility.value) * offset
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = motion.contentFade.value
                    translationY = (1f - motion.content.value) * contentDistance * motion.direction
                }) { content() }
            }
        }
    }
}
