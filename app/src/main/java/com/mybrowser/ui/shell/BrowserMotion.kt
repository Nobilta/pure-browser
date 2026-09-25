package com.mybrowser.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 motion.
 *
 * Two kinds of animation live in this app, and they need two different kinds of spec. Picking the
 * wrong one for either is what makes motion feel wrong rather than merely fast or slow.
 *
 * **A transition the user asked for** — a sheet opening, a page pushing in, a dialog appearing —
 * has a beginning, an end, and a shape. Material 3 gives that shape as an easing curve, and the
 * shape matters: `emphasised decelerate` covers 62% of the distance in the first tenth of its
 * duration. It is *front-loaded*, so the surface is already there and then brakes. A spring
 * released from rest cannot do that. Its response is second-order, so it swells in and only reaches
 * 90% of the distance about two thirds of the way through — measurably back-loaded, and the reason
 * a tall sheet can feel like it heaves itself into place instead of arriving. Fitting a spring to
 * Material 3's decelerate curve as closely as a second-order system allows still leaves it 21% of
 * the travel behind one tenth of the way in, so [panelArrive] and its siblings are the spec's own
 * curve and duration tokens rather than an approximation of them.
 *
 * **Motion a finger or a scroll drives** — bars collapsing under a scroll, a row gliding to a new
 * place, a sheet settling after a drag — has no shape to preserve, because the input wrote it. What
 * it needs instead is to survive being interrupted: a scroll reverses, a row's neighbours keep
 * moving. That is what a spring is for, since it continues from the value *and* velocity it already
 * has, where a tween restarts its easing from zero.
 *
 * [resume] is the bridge between the two: a transition interrupted mid-flight, or handed over by a
 * gesture, finishes on a spring so it never restarts, and everything else runs on the curve.
 *
 * The curve and duration values are Material 3's own tokens, taken from the same constants the
 * library resolves internally (`MotionTokens`), so a browser surface and the Material component
 * next to it move on one rhythm.
 */
internal object BrowserMotion {
    // ---- Material 3 easing tokens ---------------------------------------------------------
    // The spec's own control points, read off MotionTokens rather than approximated.
    private val EmphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    private val EmphasizedAccelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)
    private val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    private val StandardAccelerate = CubicBezierEasing(.3f, 0f, 1f, 1f)

    /** `easing.standard` and `easing.emphasized` are the same curve in Material 3. */
    private val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f)

    // ---- Material 3 duration tokens, in milliseconds --------------------------------------
    private const val SHORT_2 = 100   // duration.short2
    private const val SHORT_3 = 150
    private const val SHORT_4 = 200
    private const val MEDIUM_1 = 250  // duration.medium1
    private const val MEDIUM_2 = 300
    private const val LONG_2 = 500

    // ---- Springs, for motion that has to survive being interrupted ------------------------

    /** `spring.*SpatialDamping` with the fast spatial stiffness, and the default one. */
    private const val FOLLOWING_DAMPING = .9f
    private const val FOLLOWING_STIFFNESS = 1400f
    private const val SETTLING_STIFFNESS = 700f

    // ---- Transitions the user asked for ---------------------------------------------------

    /**
     * A panel the user opened, arriving: a bottom sheet, a full-window page, a bar at an edge.
     * Front-loaded and braking, so the surface is present immediately and then settles.
     */
    fun <T> panelArrive(): FiniteAnimationSpec<T> = tween(MEDIUM_2, easing = EmphasizedDecelerate)

    /**
     * The same panel leaving. Material 3 gives an exit the accelerate curve, which is deliberately
     * back-loaded — the surface barely moves for its first half and then clears out — and pairs it
     * with a shorter duration than the entrance so that hesitation stays under a tenth of a second
     * instead of reading as the surface refusing to go.
     */
    fun <T> panelDepart(): FiniteAnimationSpec<T> = tween(SHORT_4, easing = EmphasizedAccelerate)

    /** A page pushing or popping inside one window; the direction only flips the travel. */
    fun <T> pageArrive(): FiniteAnimationSpec<T> = tween(MEDIUM_1, easing = EmphasizedDecelerate)

    fun <T> pageDepart(): FiniteAnimationSpec<T> = tween(SHORT_4, easing = EmphasizedAccelerate)

    /**
     * A surface that takes the whole window: settings, bookmarks, the QR scanner. Further to travel
     * than a panel, so Material 3 gives it a longer duration — and a front-loaded curve carries that
     * easily, because most of the distance is covered in the first tenth either way and the extra
     * time only lengthens the settle.
     */
    fun <T> windowArrive(): FiniteAnimationSpec<T> = tween(LONG_2, easing = EmphasizedDecelerate)

    fun <T> windowDepart(): FiniteAnimationSpec<T> = tween(MEDIUM_1, easing = EmphasizedAccelerate)

    /**
     * A small surface settling into place — a dialog, a floating action button. Shorter than a
     * panel: there is less distance to cover and nothing to read on the way.
     */
    fun <T> localArrive(): FiniteAnimationSpec<T> = tween(SHORT_3, easing = StandardDecelerate)

    fun <T> localDepart(): FiniteAnimationSpec<T> = tween(SHORT_2, easing = StandardAccelerate)

    /**
     * Same-level content swapping without travelling: a title, a tab's body, an icon that changes
     * meaning. There is no travel to shape, so this is `easing.standard` and short.
     */
    fun <T> contentCross(): FiniteAnimationSpec<T> = tween(SHORT_4, easing = Standard)

    /** Small chrome fades that are not the main event: a badge, a load bar. */
    fun <T> chromeFade(): FiniteAnimationSpec<T> = tween(SHORT_3, easing = Standard)

    /** A header inset that settles with a page change instead of jumping. */
    fun <T> headerShift(): FiniteAnimationSpec<T> = tween(SHORT_4, easing = Standard)

    // ---- Motion a finger or a scroll drives -----------------------------------------------

    /**
     * Recurring chrome that has to keep up with a gesture: the address and tool bars sliding away
     * under a scroll, an overlay arriving at an edge. The fastest spatial spring, because a scroll
     * can reverse it on any frame and it has to carry on rather than restart.
     */
    fun <T> chromeSpatial(): FiniteAnimationSpec<T> = spring(FOLLOWING_DAMPING, FOLLOWING_STIFFNESS)

    /**
     * An animation continuing from where an interrupted one left off, or from a finger letting go.
     * Slower than [chromeSpatial] because these carry more distance: a whole panel, not a bar.
     */
    fun <T> resume(): FiniteAnimationSpec<T> = spring(FOLLOWING_DAMPING, SETTLING_STIFFNESS)

    /** A row appearing in a list the user edits; a fade has no shape worth preserving. */
    fun <T> itemFadeIn(): FiniteAnimationSpec<T> = tween(SHORT_3, easing = Standard)

    fun <T> itemFadeOut(): FiniteAnimationSpec<T> = tween(SHORT_2, easing = Standard)

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
 * user-managed rows ask for it. Placement is a spring because a row's neighbours are usually still
 * moving when it lands, and the fades are short enough not to lag behind them.
 */
@Composable
internal fun LazyItemScope.userItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = BrowserMotion.itemFadeIn(),
    placementSpec = BrowserMotion.chromeSpatial(),
    fadeOutSpec = BrowserMotion.itemFadeOut(),
)

/** The grid's version of the same motion, for a set of tiles the user adds and removes. */
@Composable
internal fun LazyGridItemScope.userItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = BrowserMotion.itemFadeIn(),
    placementSpec = BrowserMotion.chromeSpatial(),
    fadeOutSpec = BrowserMotion.itemFadeOut(),
)

/** Geometry of one presented surface; a replaced container is faded out with it. */
internal data class SheetShape(val fullscreen: Boolean, val height: Int, val color: Color)

/**
 * How one route places itself.
 *
 * [visibility] belongs to the window, so an entrance and its exit are one motion and a reversal
 * continues from where it is. [content] belongs to this route instead: it stays settled for the
 * first page of a window (the window carries that entrance) and animates when this page replaced
 * another one inside the same window. The dim behind the surface is not a third progress — it
 * tracks [visibility], because a dim on its own clock arrives before the surface it is dimming for.
 */
internal class SheetMotion(
    val visibility: State<Float>,
    /** Position of this route's content inside the window. */
    val content: State<Float>,
    /** Opacity of this route's content. Its own clock: alpha and travel are different jobs. */
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
 * part of the same motion as the dismissal rather than a separate one: the finger sets the surface
 * position directly, and letting go hands the value and velocity it reached to the spring that owns
 * that position, so there is no seam between dragging and letting go.
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
