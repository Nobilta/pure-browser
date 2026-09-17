package com.mybrowser.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Material 3 motion tokens and the specs this app builds from them.
 *
 * Callers name a spec — `sheetEnter`, `pageChange`, … — instead of a duration or a curve, so one
 * table decides the rhythm of every surface the shell owns. Component defaults (menus, ripples,
 * switches, the navigation indicator) keep their library animation and are deliberately not
 * re-timed here.
 */
internal object BrowserMotion {
    // Easing tokens of the Material 3 motion system; add the remaining ones with the spec that
    // needs them rather than keeping unused names here.
    val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(.3f, 0f, 1f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)

    /** A panel arrives: long enough to read the surface, decelerating into place. */
    val sheetEnter: FiniteAnimationSpec<Float> = tween(300, easing = EmphasizedDecelerate)

    /** A panel leaves the way it came: shorter and accelerating away. */
    val sheetExit: FiniteAnimationSpec<Float> = tween(200, easing = EmphasizedAccelerate)

    /** A page pushes or pops inside one window; the direction only flips the travel. */
    val pageChange: FiniteAnimationSpec<Float> = tween(250, easing = Standard)

    /** Same-level content swaps without travelling: filters, tabs, wide-layout detail. */
    val contentReplace: FiniteAnimationSpec<Float> = tween(150, easing = Standard)

    /**
     * The address bar leaving towards its own screen edge. It changes layout size rather than
     * moving a layer, so this spec is sized rather than float-based; the duration and curve are the
     * same pair the rest of the app uses.
     */
    val chromeShow: FiniteAnimationSpec<IntSize> = tween(200, easing = StandardDecelerate)
    val chromeHide: FiniteAnimationSpec<IntSize> = tween(150, easing = StandardAccelerate)

    /** A row a user adds or removes: it fades, and its neighbours glide to their new places. */
    val itemEnter: FiniteAnimationSpec<Float> = tween(150, easing = Standard)
    val itemPlacement: FiniteAnimationSpec<IntOffset> = tween(200, easing = Standard)
    val itemExit: FiniteAnimationSpec<Float> = tween(100, easing = Standard)

    /** A local surface (a dialog) settles into place instead of appearing. */
    val localEnter: FiniteAnimationSpec<Float> = tween(150, easing = StandardDecelerate)
    val localExit: FiniteAnimationSpec<Float> = tween(100, easing = StandardAccelerate)

    /**
     * A control surface arriving at, or leaving, one edge of the screen: the player's bars, a
     * search field dropping out of the header. The same pair of durations the chrome uses, sized
     * for travel instead of a layout change.
     */
    val overlayEnter: FiniteAnimationSpec<IntOffset> = tween(200, easing = StandardDecelerate)
    val overlayExit: FiniteAnimationSpec<IntOffset> = tween(150, easing = StandardAccelerate)

    /** A header inset that settles with a page change instead of jumping. */
    val headerShift: FiniteAnimationSpec<Dp> = tween(200, easing = Standard)

    /** Travel of a pushed page: arriving content starts one step towards the edge. */
    val PAGE_CHANGE_DISTANCE = 24.dp

    /** How far a full-screen page is offset while it fades in or out. */
    val FULLSCREEN_OFFSET = 32.dp

    /**
     * A page change fades faster than it travels (150 ms of 250 ms), so content is readable before
     * the travel ends. Applied to the same progress value the displacement uses.
     */
    const val PAGE_CHANGE_FADE_SHARE = 250f / 150f

    /** Dim behind a sheet surface; a sheet never floats translucent over the page. */
    const val SCRIM_ALPHA = .32f
}

/** Alpha for a page that is still travelling: the fade finishes before the movement does. */
internal fun pageChangeAlpha(progress: Float): Float =
    (progress * BrowserMotion.PAGE_CHANGE_FADE_SHARE).coerceIn(0f, 1f)

/**
 * The item motion for a list the user edits (closing a tab, deleting a download or bookmark).
 * Applying it to every list would animate streamed updates as well, which reads as noise, so only
 * user-managed rows ask for it.
 */
internal fun LazyItemScope.userItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = BrowserMotion.itemEnter,
    placementSpec = BrowserMotion.itemPlacement,
    fadeOutSpec = BrowserMotion.itemExit,
)

/** Geometry of one presented surface; a replaced container is faded out with it. */
internal data class SheetShape(val fullscreen: Boolean, val height: Int, val color: Color)

/**
 * How one route places itself.
 *
 * [visibility] belongs to the window, so an entrance and its exit are one motion and an interrupted
 * animation reverses from where it is. [content] belongs to this route: it stays settled for the
 * first page of a window (the window carries that entrance) and animates when this page replaced
 * another one inside the same window.
 */
internal class SheetMotion(
    val visibility: State<Float>,
    val content: State<Float>,
    /** +1 while a child arrives, -1 while the parent returns. */
    val direction: Float,
    /** The surface this page replaced, drawn while a container change settles. */
    val replaced: SheetShape?,
)
