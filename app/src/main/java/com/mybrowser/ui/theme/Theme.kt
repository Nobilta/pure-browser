package com.mybrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mybrowser.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF365F91), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3FF), onPrimaryContainer = Color(0xFF102D4E),
    secondary = Color(0xFF535F70), secondaryContainer = Color(0xFFD7E3F7),
    onSecondary = Color.White, onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DAFF), onTertiaryContainer = Color(0xFF251431),
    surface = Color(0xFFF9F9FF), background = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20), onBackground = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB), onSurfaceVariant = Color(0xFF43474F),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC3C6CF),
    surfaceDim = Color(0xFFD9DADF), surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEEF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9), surfaceTint = Color(0xFF365F91),
    inverseSurface = Color(0xFF2E3035), inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFFA5C8FF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5C8FF), onPrimary = Color(0xFF00315E),
    primaryContainer = Color(0xFF1C4777), onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBBC7DB), secondaryContainer = Color(0xFF3C4858),
    onSecondary = Color(0xFF253141), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD7BFE4), onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F), onTertiaryContainer = Color(0xFFF3DAFF),
    surface = Color(0xFF111318), background = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E9), onBackground = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF43474F), onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D919A), outlineVariant = Color(0xFF43474F),
    surfaceDim = Color(0xFF111318), surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A), surfaceTint = Color(0xFFA5C8FF),
    inverseSurface = Color(0xFFE1E2E9), inverseOnSurface = Color(0xFF2E3035),
    inversePrimary = Color(0xFF365F91),
)

/**
 * The app's own palette, on every Android version.
 *
 * The colours do not follow the wallpaper: a browser chrome that changed hue with the system
 * theme made the same screen look like a different product on every device, and it left the
 * launcher mark (now a blue orbit) next to a wallpaper-derived accent. The light and dark schemes
 * below are the only two the app draws; the theme setting chooses between them, or follows the
 * system's own light/dark switch.
 *
 * Motion is not installed here: Material 3 1.4.0 keeps `MotionScheme` internal, so the app's own
 * motion tokens are declared in `BrowserMotion` using the library's own easing, duration and spring
 * values — the same ones its components resolve internally — which keeps one rhythm without
 * reaching into an internal API.
 */
@Composable
fun MyBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    // The platform scroll effect is deliberately left in place: on Android 12+ a list stretches
    // under a fling at its edge, and that stretch is the app's most frequently felt motion. A
    // surface that must not move with it opts out where it is scrolled, instead of every scroll
    // in the app being switched off at once.
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/**
 * Status colours that sit outside the Material colour roles.
 *
 * Green/amber/red carry meaning here, so the brand palette cannot replace them. They
 * are still theme values: tuned for each background and exposed through a CompositionLocal so a
 * future contrast test has one source to check.
 */
@Immutable
data class BrowserExtendedColors(val secure: Color, val warning: Color)

private val LightExtendedColors = BrowserExtendedColors(
    secure = Color(0xFF146C2E),
    warning = Color(0xFF7A5900),
)
private val DarkExtendedColors = BrowserExtendedColors(
    secure = Color(0xFF7DD88F),
    warning = Color(0xFFF2C94C),
)

object BrowserColors {
    val secure: Color
        @Composable get() = LocalExtendedColors.current.secure
    val warning: Color
        @Composable get() = LocalExtendedColors.current.warning
}

private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
