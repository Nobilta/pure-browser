package com.mybrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme: Material 3 with Material You dynamic colour.
 *
 * **Why not MaterialExpressiveTheme.** Expressive is the newer M3 iteration and would be
 * the obvious target, but in material3 1.4.0 — the current stable — both
 * `MaterialExpressiveTheme` and `ExperimentalMaterial3ExpressiveApi` are declared
 * `internal`, so they cannot be called at all; an `@OptIn` does not help. They become
 * public over the course of the 1.5.0 alphas.
 *
 * Staying on stable is the deliberate choice rather than moving to 1.5.0-alphaNN: those
 * release notes show graduation is not monotonic (alpha19 reverted `MaterialShapes` and
 * `LoadingIndicator` back to experimental), and a moving design-system API underneath a
 * UI being actively written costs more than the Expressive delta is worth here. The
 * Expressive-only components — FAB menus, split buttons, flexible app bars, wavy
 * progress — are not ones a browser chrome of "omnibar + toolbar + WebView" uses. The
 * main real gain would be the motion scheme.
 *
 * To adopt Expressive once 1.5.0 is stable: bump `composeBom`, swap [MaterialTheme] for
 * `MaterialExpressiveTheme`, and pass `motionScheme = MotionScheme.expressive()`. This
 * file is the only place that changes.
 *
 * **Dynamic colour with no fallback palette.** [dynamicLightColorScheme] and
 * [dynamicDarkColorScheme] need API 31 and minSdk is 34, so they are unconditionally
 * available — no `if (SDK_INT >= S)` branch and no hand-written backup ColorScheme. The
 * wallpaper supplies the palette.
 */
@Composable
fun MyBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

/**
 * Project colours that Material 3 has no role for.
 *
 * MD3 defines `error` but no success/trust counterpart, and the omnibar's padlock needs
 * one. Keeping it here rather than in colors.xml means it tracks the theme: a fixed green
 * that reads fine on a light surface is too dark against a dark one.
 *
 * Both values are checked against their surface for WCAG AA on non-text contrast (3:1).
 */
object BrowserColors {
    val secure: Color
        @Composable get() = if (LocalIsDarkTheme.current) Color(0xFF6DD58C) else Color(0xFF188038)
}

private val LocalIsDarkTheme = staticCompositionLocalOf { false }
