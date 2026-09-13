package com.mybrowser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/** Wallpaper colours on Android 12+, with stable palettes on Android 10–11. */
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
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    // Browser forms and libraries use fixed edges, including nested pickers/dialogs.
    CompositionLocalProvider(LocalIsDarkTheme provides dark, LocalOverscrollFactory provides null) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

object BrowserColors {
    val secure: Color
        @Composable get() = if (LocalIsDarkTheme.current) Color(0xFF6DD58C) else Color(0xFF188038)
    val warning: Color
        @Composable get() = if (LocalIsDarkTheme.current) Color(0xFFE8C349) else Color(0xFF785900)
}

private val LocalIsDarkTheme = staticCompositionLocalOf { false }
