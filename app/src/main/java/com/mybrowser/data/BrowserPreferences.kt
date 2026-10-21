package com.mybrowser.data

import com.mybrowser.R
import android.content.Context
import androidx.core.content.edit
import com.mybrowser.core.PlaybackSpeed

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
    val automaticPip: Boolean = true,
    val backgroundPlayback: Boolean = false,
)

data class BrowserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val video: VideoPreferences = VideoPreferences(),
    val bottomAddressBar: Boolean = false,
    val swipeTabs: Boolean = false,
    /** Check the public release manifest once per process start and offer a newer build. */
    val autoCheckUpdates: Boolean = true,
    /**
     * The user's wish to browse incognito. Persisted across restarts, unlike the live
     * session in PrivacyMode: every cold start opens a brand-new incognito session and
     * never restores private tabs or cookies.
     */
    val incognitoEnabled: Boolean = false,
    /** Online search suggestions in normal mode; the input is sent to the current engine. */
    val searchSuggestionsEnabled: Boolean = true,
    /**
     * Online search suggestions in incognito. Off by default: the user must opt in
     * before any incognito input leaves the device.
     */
    val privateSearchSuggestionsEnabled: Boolean = false,
    /**
     * The user's wish for immersive browsing: system bars, omnibar and toolbar stay
     * hidden and the page fills the screen. A small floating entry point keeps
     * browser actions reachable; unlike video fullscreen this never recreates the
     * WebView or changes the profile.
     */
    val browserFullscreenEnabled: Boolean = false,
)

/** UI preferences only; URLs, cookies and temporary playback state never enter this store. */
class BrowserPreferencesRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)

    private fun read(): BrowserPreferences = BrowserPreferences(
        bottomAddressBar = prefs.getBoolean("bottom_address_bar", false),
        swipeTabs = prefs.getBoolean("swipe_tabs", false),
        autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
        incognitoEnabled = prefs.getBoolean("incognito_enabled", false),
        searchSuggestionsEnabled = prefs.getBoolean("search_suggestions_enabled", true),
        privateSearchSuggestionsEnabled = prefs.getBoolean("private_search_suggestions_enabled", false),
        browserFullscreenEnabled = prefs.getBoolean("browser_fullscreen_enabled", false),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM").orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM),
        video = VideoPreferences(
            automaticPip = prefs.getBoolean("video_auto_pip", true),
            backgroundPlayback = prefs.getBoolean("video_background", false),
            enhancedControls = prefs.getBoolean("video_controls", true),
            verticalGestures = prefs.getBoolean("video_vertical", true),
            horizontalSeek = prefs.getBoolean("video_seek", true),
            holdToBoost = prefs.getBoolean("video_hold", true),
            boostRate = prefs.getFloat("video_boost", 2f).takeIf { it in PlaybackSpeed.BOOST_OPTIONS } ?: 2f,
            landscapeFullscreen = prefs.getBoolean("video_landscape", true),
            rememberSpeed = prefs.getBoolean("video_remember_speed", false),
            preferredSpeed = PlaybackSpeed.normalizeSelection(prefs.getFloat("video_speed", 1f)) ?: 1f,
        ),
    )

    /**
     * Persists the incognito wish with a confirmed result. A mode switch must only happen
     * after this succeeds, so unlike [save] this reports the disk write instead of firing
     * and forgetting. Call from an IO dispatcher.
     */
    fun saveIncognitoEnabled(value: Boolean): Boolean = synchronized(writeLock) {
        runCatching { prefs.commitConfirmed(mapOf("incognito_enabled" to value)) }.isSuccess
    }

    /** Restore the in-memory value too: a failed commit must not enable fullscreen on reload. */
    fun saveBrowserFullscreenEnabled(value: Boolean): Boolean = synchronized(writeLock) {
        runCatching { prefs.commitConfirmed(mapOf("browser_fullscreen_enabled" to value)) }.isSuccess
    }

    fun load(): BrowserPreferences = synchronized(writeLock) { read() }

    /**
     * Applies one UI change against the values currently on disk rather than overwriting the whole
     * snapshot the UI was built from. A settings import can commit a field while the settings sheet
     * is open; the next toggle must not put the pre-import snapshot back.
     */
    fun saveChanged(base: BrowserPreferences, target: BrowserPreferences, confirmed: Boolean = false): BrowserPreferences =
        synchronized(writeLock) { write(mergeChanged(base, target, read()), confirmed) }

    /** Runs a read-modify-write atomically with respect to every other repository write. */
    fun update(transform: (BrowserPreferences) -> BrowserPreferences, confirmed: Boolean = false): BrowserPreferences =
        synchronized(writeLock) { write(transform(read()), confirmed) }

    fun save(value: BrowserPreferences, confirmed: Boolean = false): BrowserPreferences =
        synchronized(writeLock) { write(value, confirmed) }

    private fun mergeChanged(
        base: BrowserPreferences,
        target: BrowserPreferences,
        current: BrowserPreferences,
    ): BrowserPreferences {
        fun <T> changed(before: T, after: T, existing: T): T = if (after != before) after else existing
        val video = target.video
        val baseVideo = base.video
        val currentVideo = current.video
        return current.copy(
            theme = changed(base.theme, target.theme, current.theme),
            bottomAddressBar = changed(base.bottomAddressBar, target.bottomAddressBar, current.bottomAddressBar),
            swipeTabs = changed(base.swipeTabs, target.swipeTabs, current.swipeTabs),
            autoCheckUpdates = changed(base.autoCheckUpdates, target.autoCheckUpdates, current.autoCheckUpdates),
            incognitoEnabled = changed(base.incognitoEnabled, target.incognitoEnabled, current.incognitoEnabled),
            searchSuggestionsEnabled = changed(
                base.searchSuggestionsEnabled, target.searchSuggestionsEnabled, current.searchSuggestionsEnabled,
            ),
            privateSearchSuggestionsEnabled = changed(
                base.privateSearchSuggestionsEnabled, target.privateSearchSuggestionsEnabled,
                current.privateSearchSuggestionsEnabled,
            ),
            browserFullscreenEnabled = changed(
                base.browserFullscreenEnabled, target.browserFullscreenEnabled, current.browserFullscreenEnabled,
            ),
            video = currentVideo.copy(
                automaticPip = changed(baseVideo.automaticPip, video.automaticPip, currentVideo.automaticPip),
                backgroundPlayback = changed(
                    baseVideo.backgroundPlayback, video.backgroundPlayback, currentVideo.backgroundPlayback,
                ),
                enhancedControls = changed(
                    baseVideo.enhancedControls, video.enhancedControls, currentVideo.enhancedControls,
                ),
                verticalGestures = changed(
                    baseVideo.verticalGestures, video.verticalGestures, currentVideo.verticalGestures,
                ),
                horizontalSeek = changed(
                    baseVideo.horizontalSeek, video.horizontalSeek, currentVideo.horizontalSeek,
                ),
                holdToBoost = changed(baseVideo.holdToBoost, video.holdToBoost, currentVideo.holdToBoost),
                boostRate = changed(baseVideo.boostRate, video.boostRate, currentVideo.boostRate),
                landscapeFullscreen = changed(
                    baseVideo.landscapeFullscreen, video.landscapeFullscreen, currentVideo.landscapeFullscreen,
                ),
                rememberSpeed = changed(
                    baseVideo.rememberSpeed, video.rememberSpeed, currentVideo.rememberSpeed,
                ),
                preferredSpeed = changed(
                    baseVideo.preferredSpeed, video.preferredSpeed, currentVideo.preferredSpeed,
                ),
            ),
        )
    }

    private fun write(value: BrowserPreferences, confirmed: Boolean): BrowserPreferences {
        val video = value.video.copy(
            boostRate = value.video.boostRate.takeIf { it in PlaybackSpeed.BOOST_OPTIONS } ?: 2f,
            preferredSpeed = PlaybackSpeed.normalizeSelection(value.video.preferredSpeed) ?: 1f,
        )
        val values = mapOf(
            "video_auto_pip" to video.automaticPip,
            "video_background" to video.backgroundPlayback,
            "bottom_address_bar" to value.bottomAddressBar,
            "swipe_tabs" to value.swipeTabs,
            "auto_check_updates" to value.autoCheckUpdates,
            "incognito_enabled" to value.incognitoEnabled,
            "search_suggestions_enabled" to value.searchSuggestionsEnabled,
            "private_search_suggestions_enabled" to value.privateSearchSuggestionsEnabled,
            "browser_fullscreen_enabled" to value.browserFullscreenEnabled,
            "theme" to value.theme.name,
            "video_controls" to video.enhancedControls,
            "video_vertical" to video.verticalGestures,
            "video_seek" to video.horizontalSeek,
            "video_hold" to video.holdToBoost,
            "video_boost" to video.boostRate,
            "video_landscape" to video.landscapeFullscreen,
            "video_remember_speed" to video.rememberSpeed,
            "video_speed" to video.preferredSpeed,
        )
        if (confirmed) prefs.commitConfirmed(values) else prefs.edit { putValues(values) }
        return value.copy(video = video)
    }

    private companion object {
        /**
         * Shared by every repository instance in the process. Settings import and the settings
         * sheet construct their own repository, so a per-instance lock would not serialise them.
         */
        val writeLock = Any()
    }
}
