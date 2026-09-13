package com.mybrowser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** MD3 standard easing; layer transforms avoid remeasuring a page on every frame. */
internal object BrowserMotion {
    val Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    const val ENTER_MS = 300
    const val CONTENT_MS = 200
}

@Composable
internal fun rememberBrowserEntrance(key: Any? = Unit, duration: Int = BrowserMotion.ENTER_MS): Animatable<Float, *> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(duration, easing = BrowserMotion.Easing))
    }
    return progress
}

@Composable
internal fun Modifier.browserContentMotion(key: Any?, distance: Dp = 12.dp): Modifier {
    val progress = rememberBrowserEntrance(key, BrowserMotion.CONTENT_MS)
    val pixels = with(LocalDensity.current) { distance.toPx() }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * pixels }
}
