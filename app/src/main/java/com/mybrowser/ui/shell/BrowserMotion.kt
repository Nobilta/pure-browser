package com.mybrowser.ui.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
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
    val EmphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)

    /** A panel arrives: long enough to read the surface, decelerating into place. */
    val sheetEnter: AnimationSpec<Float> = tween(300, easing = EmphasizedDecelerate)

    /** A panel leaves the way it came: shorter and accelerating away. */
    val sheetExit: AnimationSpec<Float> = tween(200, easing = EmphasizedAccelerate)

    /** A page pushes or pops inside one window; the direction only flips the travel. */
    val pageChange: AnimationSpec<Float> = tween(250, easing = Standard)

    /** Same-level content swaps without travelling: filters, tabs, wide-layout detail. */
    val contentReplace: AnimationSpec<Float> = tween(150, easing = Standard)

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
