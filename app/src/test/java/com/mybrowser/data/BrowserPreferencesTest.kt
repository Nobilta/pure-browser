package com.mybrowser.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
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

    @Test fun incognitoWishDefaultsOffSurvivesRecreationAndRoundTripsThroughBothWriters() {
        assertFalse(BrowserPreferencesRepository(context).load().incognitoEnabled)
        // saveIncognitoEnabled is the mode-switch path: it must report a confirmed write.
        assertTrue(BrowserPreferencesRepository(context).saveIncognitoEnabled(true))
        assertTrue(BrowserPreferencesRepository(context).load().incognitoEnabled)
        // A full save() through other settings keeps the persisted wish when the caller
        // copies the current in-memory preferences, as the settings UI does.
        val repository = BrowserPreferencesRepository(context)
        repository.save(repository.load().copy(bottomAddressBar = true))
        assertTrue(BrowserPreferencesRepository(context).load().incognitoEnabled)
        assertTrue(repository.saveIncognitoEnabled(false))
        assertFalse(BrowserPreferencesRepository(context).load().incognitoEnabled)
    }

    @Test fun searchSuggestionsDefaultOnAndPrivateDefaultsOff() {
        val defaults = BrowserPreferencesRepository(context).load()
        assertTrue(defaults.searchSuggestionsEnabled)
        assertFalse(defaults.privateSearchSuggestionsEnabled)
        val repository = BrowserPreferencesRepository(context)
        repository.save(repository.load().copy(searchSuggestionsEnabled = false, privateSearchSuggestionsEnabled = true))
        val reloaded = BrowserPreferencesRepository(context).load()
        assertFalse(reloaded.searchSuggestionsEnabled)
        assertTrue(reloaded.privateSearchSuggestionsEnabled)
    }
    @Test fun fullscreenPreferenceRoundTripsAndReportsItsWrite() {
        val repository = BrowserPreferencesRepository(context)
        assertFalse(repository.load().browserFullscreenEnabled)
        assertTrue(repository.saveBrowserFullscreenEnabled(true))
        assertTrue(repository.load().browserFullscreenEnabled)
        assertTrue(repository.saveBrowserFullscreenEnabled(false))
        assertFalse(repository.load().browserFullscreenEnabled)
        // The whole-preference save carries the field as well.
        repository.save(BrowserPreferences(browserFullscreenEnabled = true))
        assertTrue(repository.load().browserFullscreenEnabled)
    }

    @Test fun failedFullscreenCommitRestoresTheValueSeenByRepositoryReloads() {
        val actual = context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE)
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                            if (method.name == "commit") {
                                // Android publishes to memory even when writing to disk fails.
                                editor.apply()
                                false
                            } else {
                                val result = method.invoke(editor, *(args ?: emptyArray()))
                                if (result is SharedPreferences.Editor) proxy else result
                            }
                        } as SharedPreferences.Editor
                    }
                }
        }
        val repository = BrowserPreferencesRepository(failing)
        assertFalse(repository.saveBrowserFullscreenEnabled(true))
        assertFalse(BrowserPreferencesRepository(context).load().browserFullscreenEnabled)
        actual.edit().putBoolean("browser_fullscreen_enabled", true).commit()
        assertFalse(repository.saveBrowserFullscreenEnabled(false))
        assertTrue(BrowserPreferencesRepository(context).load().browserFullscreenEnabled)
    }
}
