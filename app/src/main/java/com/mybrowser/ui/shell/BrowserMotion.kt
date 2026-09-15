package com.mybrowser.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** MD3 standard easing; layer transforms avoid remeasuring a page on every frame. */
internal object BrowserMotion {
    val Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    const val ENTER_MS = 240
    const val CONTENT_MS = 120

    /** How far arriving content travels while a route change settles. */
    val CONTENT_DISTANCE = 12.dp

    /** Dim behind a sheet surface; a sheet never floats translucent over the page. */
    const val SCRIM_ALPHA = .32f
}

/**
 * One motion vocabulary for every sheet, in three scenes:
 *
 * - [ENTER] the sheet system opens, so the surface itself moves and the scrim fades in;
 * - [WITHIN] a destination of the same shape replaces this one, so only the content moves;
 * - [CONTAINER] the destination switched between a bottom sheet and a full-screen page: the
 *   replaced surface is drawn fading out while this one slides in, so the swap cannot pop.
 */
internal enum class SheetScene { ENTER, WITHIN, CONTAINER }

/** Geometry of one presented surface; a replaced container is faded out with it. */
internal data class SheetShape(val fullscreen: Boolean, val height: Int, val color: Color)

/** A presentation's motion. Progress is read inside layer lambdas, so no frame recomposes. */
internal class SheetMotion(
    val scene: SheetScene,
    val progress: State<Float>,
    /** +1 while a child arrives, -1 while the parent returns. */
    val direction: Float,
    /** The surface this presentation replaced, drawn while a container change settles. */
    val replaced: SheetShape?,
)

@Composable
internal fun rememberBrowserEntrance(key: Any? = Unit, duration: Int = BrowserMotion.ENTER_MS): Animatable<Float, *> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(duration, easing = BrowserMotion.Easing))
    }
    return progress
}

/** The entrance every scene shares: only the duration differs, so the rhythm stays one. */
@Composable
internal fun rememberSheetMotion(scene: SheetScene): Animatable<Float, *> = rememberBrowserEntrance(
    scene, if (scene == SheetScene.ENTER) BrowserMotion.ENTER_MS else BrowserMotion.CONTENT_MS,
)

@Composable
internal fun Modifier.browserContentMotion(key: Any?, distance: Dp = 12.dp): Modifier {
    val progress = rememberBrowserEntrance(key, BrowserMotion.CONTENT_MS)
    val pixels = with(LocalDensity.current) { distance.toPx() }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * pixels }
}
