package com.mybrowser.data

import com.mybrowser.R
import android.content.Context
import androidx.core.content.edit
import com.mybrowser.media.PlaybackSpeed

enum class ThemeMode(val labelRes: Int) {
    SYSTEM(R.string.ui_system_default), LIGHT(R.string.ui_light), DARK(R.string.ui_dark),
}

data class VideoPreferences(
    val enhancedControls: Boolean = true,
    val verticalGestures: Boolean = true,
    val horizontalSeek: Boolean = true,
    val holdToBoost: Boolean = true,
    val boostRate: Float = 2f,
    val landscapeFullscreen: Boolean = true,
    val rememberSpeed: Boolean = false,
    val preferredSpeed: Float = PlaybackSpeed.DEFAULT,
)

data class BrowserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val video: VideoPreferences = VideoPreferences(),
    val readingTextZoom: Int = 100,
    val protectPrivateScreens: Boolean = true,
    val bottomAddressBar: Boolean = false,
    val swipeTabs: Boolean = false,
)

/** UI preferences only; URLs, cookies and temporary playback state never enter this store. */
class BrowserPreferencesRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)

    fun load(): BrowserPreferences = BrowserPreferences(
        bottomAddressBar = prefs.getBoolean("bottom_address_bar", false),
        swipeTabs = prefs.getBoolean("swipe_tabs", false),
        protectPrivateScreens = prefs.getBoolean("protect_private_screens", true),
        readingTextZoom = prefs.getInt("reading_text_zoom", 100).coerceIn(75, 175),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM").orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM),
        video = VideoPreferences(
            enhancedControls = prefs.getBoolean("video_controls", true),
            verticalGestures = prefs.getBoolean("video_vertical", true),
            horizontalSeek = prefs.getBoolean("video_seek", true),
            holdToBoost = prefs.getBoolean("video_hold", true),
            boostRate = prefs.getFloat("video_boost", 2f).takeIf { it == 2f || it == 3f } ?: 2f,
            landscapeFullscreen = prefs.getBoolean("video_landscape", true),
            rememberSpeed = prefs.getBoolean("video_remember_speed", false),
            preferredSpeed = PlaybackSpeed.normalizeSelection(prefs.getFloat("video_speed", 1f)) ?: 1f,
        ),
    )

    fun save(value: BrowserPreferences): BrowserPreferences {
        val video = value.video.copy(
            boostRate = value.video.boostRate.takeIf { it == 2f || it == 3f } ?: 2f,
            preferredSpeed = PlaybackSpeed.normalizeSelection(value.video.preferredSpeed) ?: 1f,
        )
        prefs.edit {
            putBoolean("bottom_address_bar", value.bottomAddressBar)
            putBoolean("swipe_tabs", value.swipeTabs)
            putBoolean("protect_private_screens", value.protectPrivateScreens)
            putInt("reading_text_zoom", value.readingTextZoom.coerceIn(75, 175))
            putString("theme", value.theme.name)
            .putBoolean("video_controls", video.enhancedControls)
            .putBoolean("video_vertical", video.verticalGestures)
            .putBoolean("video_seek", video.horizontalSeek)
            .putBoolean("video_hold", video.holdToBoost)
            .putFloat("video_boost", video.boostRate)
            .putBoolean("video_landscape", video.landscapeFullscreen)
            .putBoolean("video_remember_speed", video.rememberSpeed)
            .putFloat("video_speed", video.preferredSpeed)
        }
        return value.copy(video = video, readingTextZoom = value.readingTextZoom.coerceIn(75, 175))
    }
}
