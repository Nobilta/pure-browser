package com.mybrowser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
    surface = Color(0xFFF9F9FF), background = Color(0xFFF9F9FF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5C8FF), onPrimary = Color(0xFF00315E),
    primaryContainer = Color(0xFF1C4777), onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBBC7DB), secondaryContainer = Color(0xFF3C4858),
    surface = Color(0xFF111318), background = Color(0xFF111318),
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
    CompositionLocalProvider(LocalIsDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

object BrowserColors {
    val secure: Color
        @Composable get() = if (LocalIsDarkTheme.current) Color(0xFF6DD58C) else Color(0xFF188038)
}

private val LocalIsDarkTheme = staticCompositionLocalOf { false }
