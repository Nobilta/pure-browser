package com.mybrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mybrowser.data.ThemeMode

// Fixed light palette: pure-white canvas, hairline porcelain cards, snow-mist interactive wells, one ocean-blue accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5BD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE9FF),
    onSecondaryContainer = Color(0xFF001B3E),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C4A6E),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF2F8),
    onSurfaceVariant = Color(0xFF526075),
    // The tint is switched off globally: no M3 component may wash a purple hue over a card.
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF2F8),
    surfaceContainerHighest = Color(0xFFE8EDF5),
    outline = Color(0xFF6E7C91),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1B2332),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF8AB4F8),
)

// Fixed dark palette: deep navy canvas with cards one step brighter, never pure black.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF004397),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF283A56),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFF7DD3FC),
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF075985),
    onTertiaryContainer = Color(0xFFE0F2FE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF0E131B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0E131B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2332),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF090D13),
    surfaceContainerLow = Color(0xFF1B2332),
    surfaceContainer = Color(0xFF131924),
    surfaceContainerHigh = Color(0xFF253044),
    surfaceContainerHighest = Color(0xFF2F3C54),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF2B374B),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF0E131B),
    inversePrimary = Color(0xFF1D5BD6),
)

/** The only five corner slots the app draws, plus `CircleShape` for capsules. */
val PureBrowserShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * One type scale, declared once.
 *
 * Sizes and line heights are fixed here so a card cannot invent its own pair; interactive text
 * never drops below 12.sp. Missing roles (display/title/body/label leftovers) keep Material's
 * defaults and are not used by browser-owned surfaces.
 */
val PureBrowserTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * The app's single palette.
 *
 * The browser chrome never follows the wallpaper: the light and dark schemes below are the only
 * two the app draws, and the theme setting chooses between them or follows the system's own
 * light/dark switch. Incognito is not a third hue either — it uses the same scheme and is marked
 * by a badge in the omnibar, on the homepage and in the tab count.
 */
@Composable
fun PureBrowserTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = PureBrowserShapes,
            typography = PureBrowserTypography,
            content = content,
        )
    }
}

/**
 * Status colours that sit outside the Material colour roles.
 *
 * Green/amber carry meaning here, so the brand palette cannot replace them. They are still theme
 * values: tuned for each background and exposed through a CompositionLocal so a contrast test has
 * one source to check. [recessedWell] is the one true inset: darker than a card in both modes.
 */
@Immutable
data class BrowserExtendedColors(
    val secure: Color,
    val warning: Color,
    val recessedWell: Color,
    val cardBorder: Color,
)

private val LightExtendedColors = BrowserExtendedColors(
    secure = Color(0xFF146C2E),
    warning = Color(0xFF7A5900),
    recessedWell = Color(0xFFEDF2F8),
    cardBorder = Color(0xFFDCE3EC),
)
private val DarkExtendedColors = BrowserExtendedColors(
    secure = Color(0xFF7DD88F),
    warning = Color(0xFFF2C94C),
    recessedWell = Color(0xFF090D13),
    cardBorder = Color(0xFF2C3A50),
)

object BrowserColors {
    /** HTTPS with a valid certificate. */
    val secure: Color
        @Composable get() = LocalExtendedColors.current.secure

    /** Plain HTTP, or a setting that is currently degraded. */
    val warning: Color
        @Composable get() = LocalExtendedColors.current.warning

    /** A genuinely sunken surface: code blocks, diagnostics, inset wells inside a card. */
    val recessedWell: Color
        @Composable get() = LocalExtendedColors.current.recessedWell

    /** Crisp 1dp structural hairline separating pure-white grouped cards from a pure-white canvas. */
    val cardBorder: Color
        @Composable get() = LocalExtendedColors.current.cardBorder
}

private val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
