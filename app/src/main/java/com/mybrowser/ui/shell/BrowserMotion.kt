package com.mybrowser.ui.shell

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Material 3 motion.
 *
 * These are the token values of Material 3's **standard** motion scheme — the damping ratio and
 * stiffness pairs the library itself animates on. Material 3 assigns that scheme to "utilitarian
 * UI elements and recurring interactions" and reserves its expressive scheme for "prominent UI
 * elements and hero interactions"; a browser is the first kind, where every animation is something
 * a user triggers over and over: a scroll hiding the chrome, a sheet opening under a thumb.
 *
 * The numbers are written out here rather than read from `MaterialTheme.motionScheme` because
 * Material 3 1.4.0 keeps that API internal to the library. The values are not a guess — they are
 * `StandardMotionTokens`, the same constants `Switch`, `Checkbox` and `ModalBottomSheet` resolve
 * through, so a browser surface and the Material component next to it are moving on one rhythm.
 *
 * Everything is a spring, and that is the point of the system. An interrupted spring carries on
 * from the value *and* the velocity it already has; a tween restarts its easing curve from zero,
 * and that restart is what reads as a hitch when a scroll reverses the chrome, when a sheet is
 * reopened while it is still leaving, or when a list keeps moving under a new row.
 *
 * Material 3 splits the system in two, and so does this file:
 *
 * - **Spatial** — anything whose bounds, position, size or shape changes. Damped at 0.9, so a
 *   surface settles instead of stopping dead.
 * - **Effects** — alpha and colour only. Critically damped at 1.0, so a fade never overshoots the
 *   value it is heading for and a colour never flashes past its target.
 *
 * Callers name the role a surface plays rather than a speed. [chromeSpatial] is for something that
 * has to answer the finger on every frame of a scroll, [panelSpatial] for a surface the user asked
 * for, [pageSpatial] for one that takes the whole window, and the effects trio is the same three
 * speeds for the changes that do not move anything.
 */
internal object BrowserMotion {
    // StandardMotionTokens.spring*SpatialDamping / spring*SpatialStiffness.
    private const val SPATIAL_DAMPING = .9f
    private const val FAST_SPATIAL_STIFFNESS = 1400f
    private const val DEFAULT_SPATIAL_STIFFNESS = 700f
    private const val SLOW_SPATIAL_STIFFNESS = 300f

    // StandardMotionTokens.spring*EffectsDamping / spring*EffectsStiffness. Damping is critical,
    // which is what keeps an alpha from overshooting past 1 and back.
    private const val EFFECTS_DAMPING = 1f
    private const val FAST_EFFECTS_STIFFNESS = 3800f
    private const val DEFAULT_EFFECTS_STIFFNESS = 1600f
    private const val SLOW_EFFECTS_STIFFNESS = 800f

    /**
     * Recurring chrome that must keep up with a gesture: the address and tool bars sliding away
     * under a scroll, an overlay arriving at an edge, a row gliding to a new place. The fastest
     * spatial token — a scroll can reverse it at any moment.
     */
    fun <T> chromeSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS)

    /**
     * A surface the user asked for and expects to watch: a bottom sheet travelling its own height,
     * a page pushing in inside a window, a dialog settling into place.
     */
    fun <T> panelSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, DEFAULT_SPATIAL_STIFFNESS)

    /** A surface that takes the whole window: a settings or bookmarks page, a QR scanner. */
    fun <T> pageSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SLOW_SPATIAL_STIFFNESS)

    /** A frequent alpha or tint change: the load bar, a FAB, an icon that swaps state. */
    fun <T> chromeEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS)

    /** A surface's own fade: a scrim, a crossfading title, a row that appears or leaves. */
    fun <T> panelEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, DEFAULT_EFFECTS_STIFFNESS)

    /** A whole page fading in or out, where the fade is the main event rather than a detail. */
    fun <T> pageEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, SLOW_EFFECTS_STIFFNESS)

    // ---- Geometry -----------------------------------------------------------------------

    /** Travel of a pushed page: arriving content starts one step towards the edge. */
    val PAGE_CHANGE_DISTANCE = 24.dp

    /** How far a full-screen page is offset while it fades in or out. */
    val FULLSCREEN_OFFSET = 32.dp

    /** Dim behind a sheet surface; a sheet never floats translucent over the page. */
    const val SCRIM_ALPHA = .32f

    /**
     * How far a dragged sheet has to travel, as a share of its own height, before letting go
     * dismisses it instead of letting it settle back.
     */
    const val DISMISS_TRAVEL_SHARE = .33f

    /** Releasing faster than this dismisses a sheet however short the drag was, in dp/s. */
    const val DISMISS_VELOCITY_DP = 900f
}

/**
 * The item motion for a list the user edits (closing a tab, deleting a download or bookmark).
 * Applying it to every list would animate streamed updates as well, which reads as noise, so only
 * user-managed rows ask for it. Placement is spatial and the fade is an effect, so a row that
 * moves keeps its colour while its neighbours close the gap.
 */
@Composable
internal fun LazyItemScope.userItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = BrowserMotion.panelEffects(),
    placementSpec = BrowserMotion.chromeSpatial(),
    fadeOutSpec = BrowserMotion.chromeEffects(),
)

/** The grid's version of the same motion, for a set of tiles the user adds and removes. */
@Composable
internal fun LazyGridItemScope.userItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = BrowserMotion.panelEffects(),
    placementSpec = BrowserMotion.chromeSpatial(),
    fadeOutSpec = BrowserMotion.chromeEffects(),
)

/** Geometry of one presented surface; a replaced container is faded out with it. */
internal data class SheetShape(val fullscreen: Boolean, val height: Int, val color: Color)

/**
 * How one route places itself.
 *
 * Both window progresses animate together, so an entrance and its exit are one motion and an
 * interrupted animation carries on from where it is. They stay separate because they are
 * different kinds of motion: [visibility] moves the surface, [scrim] changes the dim behind it,
 * and Material 3 gives each its own damping — a scrim that rode the surface's spring would
 * overshoot its own target. [content] belongs to this route instead: it stays settled for the
 * first page of a window (the window carries that entrance) and animates when this page
 * replaced another one inside the same window.
 */
internal class SheetMotion(
    val visibility: State<Float>,
    val scrim: State<Float>,
    /** Position of this route's content inside the window. */
    val content: State<Float>,
    /** Opacity of this route's content. It is a separate clock because alpha is an effect. */
    val contentFade: State<Float>,
    /** +1 while a child arrives, -1 while the parent returns. */
    val direction: Float,
    /** The surface this page replaced, drawn while a container change settles. */
    val replaced: SheetShape?,
    /** The finger's hold on this sheet, or null for a surface that is not dragged away. */
    val drag: SheetDrag?,
)

/**
 * Drag-to-dismiss state for the sheet currently mounted.
 *
 * A sheet is the one surface a user expects to be able to throw away, and the gesture has to be
 * part of the same motion as the dismissal rather than a separate one: the finger sets the
 * surface position directly, and letting go hands the value and velocity it reached to the
 * spring that owns that position, so there is no seam between dragging and letting go.
 */
internal class SheetDrag internal constructor(
    /** Distance the surface is dragged from where the window put it, in pixels. */
    val offset: State<Float>,
    private val onDrag: (Float) -> Float,
    private val onRelease: (Float) -> Unit,
    private val onCancel: () -> Unit,
) {
    /** Positive moves the sheet down, towards the edge it came from. */
    fun drag(delta: Float): Float = onDrag(delta)

    /** [velocity] is in pixels per second; positive is downwards. */
    fun release(velocity: Float) = onRelease(velocity)

    /** Lets go without settling, because the route closed under the gesture. */
    fun cancel() = onCancel()
}
