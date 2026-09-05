package com.mybrowser.ui

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserStateTest {
    private fun loadedState() = BrowserState().apply {
        onPageStarted("https://example.com/start")
        onTitleChanged("Page title")
        onPageFinished(currentUrl, false, false)
    }

    @Test
    fun titleDisplayAndFullUrlEditingAreIndependent() {
        val state = loadedState()
        assertEquals("Page title", state.displayTitle)
        state.onOmnibarFocusChange(true)
        assertEquals("https://example.com/start", state.omnibarValue.text)
        assertEquals(TextRange(0, state.currentUrl.length), state.omnibarValue.selection)
        state.onOmnibarValueChange(TextFieldValue("unfinished query"))
        state.onOmnibarFocusChange(true)
        state.onHistoryUpdated("https://example.com/next", true, false)
        state.onTitleChanged("New title")
        state.onPageFinished(state.currentUrl, true, false)
        assertEquals("unfinished query", state.omnibarValue.text)
        state.onOmnibarFocusChange(false)
        assertEquals("New title", state.displayTitle)
        state.onOmnibarFocusChange(true)
        assertEquals("https://example.com/next", state.omnibarValue.text)
    }

    @Test
    fun navigationClearsOldTitleAndBlankPageUsesHint() {
        val state = loadedState()
        state.onPageStarted("https://other.example/page")
        assertNull(state.pageTitle)
        assertEquals("other.example/page", state.displayTitle)
        state.onTitleChanged("  ")
        assertNull(state.pageTitle)
        state.onPageStarted("about:blank")
        state.onTitleChanged("about:blank")
        assertEquals("", state.displayTitle)
    }

    @Test
    fun scrollDirectionUsesDensityAndHysteresis() {
        val state = loadedState()
        state.onPageScroll(60, 0, 2f)
        assertFalse(state.isToolbarHidden)
        state.onPageScroll(110, 60, 2f)
        assertTrue(state.isToolbarHidden)
        state.onPageScroll(100, 110, 2f)
        assertTrue(state.isToolbarHidden)
        state.onPageScroll(55, 100, 2f)
        assertFalse(state.isToolbarHidden)
        state.onPageScroll(200, 55, 2f)
        assertTrue(state.isToolbarHidden)
        state.onPageScroll(0, 200, 2f)
        assertFalse(state.isToolbarHidden)
    }

    @Test
    fun editingFindingAndLoadingKeepToolbarVisible() {
        val state = loadedState()
        state.onPageScroll(200, 0, 1f)
        assertTrue(state.isToolbarHidden)
        state.onOmnibarFocusChange(true)
        state.onPageScroll(400, 200, 1f)
        assertFalse(state.isToolbarHidden)
        state.onOmnibarFocusChange(false)
        state.showFindBar()
        state.onPageScroll(600, 400, 1f)
        assertFalse(state.isToolbarHidden)
        state.hideFindBar()
        state.onPageStarted("https://example.com/new")
        state.onPageScroll(800, 600, 1f)
        assertFalse(state.isToolbarHidden)
    }

    @Test
    fun historyNavigationUpdatesControlsWithoutStartingLoad() {
        val state = loadedState()
        state.onHistoryUpdated("https://example.com/route", true, true)
        assertTrue(state.canGoBack)
        assertTrue(state.canGoForward)
        assertFalse(state.isLoading)
        assertEquals("https://example.com/route", state.currentUrl)
    }
}
