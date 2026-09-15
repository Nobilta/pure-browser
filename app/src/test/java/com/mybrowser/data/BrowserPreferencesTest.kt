package com.mybrowser.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun settingsSurviveRepositoryRecreation() {
        val selected = BrowserPreferences(ThemeMode.DARK, VideoPreferences(
            verticalGestures = false, boostRate = 3f, rememberSpeed = true, preferredSpeed = 1.5f,
        ))
        BrowserPreferencesRepository(context).save(selected)
        assertEquals(selected, BrowserPreferencesRepository(context).load())
    }

    @Test fun invalidSpeedSelectionsFallBackToSupportedValues() {
        val saved = BrowserPreferencesRepository(context).save(BrowserPreferences(video = VideoPreferences(
            boostRate = Float.NaN, preferredSpeed = 100f,
        )))
        assertEquals(2f, saved.video.boostRate, 0f)
        assertEquals(1f, saved.video.preferredSpeed, 0f)
        assertEquals(saved, BrowserPreferencesRepository(context).load())
    }

    @Test fun unknownThemeFromLaterVersionDoesNotPreventStartup() {
        context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE).edit().putString("theme", "FUTURE_THEME").commit()
        assertEquals(ThemeMode.SYSTEM, BrowserPreferencesRepository(context).load().theme)
        assertFalse(BrowserPreferencesRepository(context).load().video.rememberSpeed)
    }

    @Test fun startupUpdateChecksStayOnUntilTheUserTurnsThemOff() {
        assertTrue(BrowserPreferencesRepository(context).load().autoCheckUpdates)
        BrowserPreferencesRepository(context).save(BrowserPreferences(autoCheckUpdates = false))
        assertFalse(BrowserPreferencesRepository(context).load().autoCheckUpdates)
    }
}
