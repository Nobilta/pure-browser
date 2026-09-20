package com.mybrowser.ui.shell

import android.app.Application
import com.mybrowser.core.PageFailure
import com.mybrowser.core.PageFailureKind
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
    fun composingInputSurvivesFocusRefreshAndPageUpdatesUntilExplicitClear() {
        val state = loadedState()
        state.onOmnibarFocusChange(true)
        val composing = TextFieldValue("zhong", TextRange(5), TextRange(0, 5))
        state.onOmnibarValueChange(composing)
        state.onOmnibarFocusChange(true)
        state.onPageFinished("https://example.com/new", true, false)
        assertEquals(composing, state.omnibarValue)
        state.clearOmnibar()
        assertTrue(state.isOmnibarFocused)
        assertEquals("", state.omnibarValue.text)
        assertNull(state.omnibarValue.composition)
    }

    @Test
    fun navigationClearsFindResultsFromThePreviousDocument() {
        val state = loadedState()
        state.showFindBar()
        state.onFindQueryChange("old phrase")
        state.onFindResultUpdate(3, 10)
        state.onPageStarted("https://other.example/new")
        assertFalse(state.isFindBarVisible)
        assertEquals("", state.findQuery)
        assertEquals(0, state.findMatchCount)
        assertEquals(0, state.findCurrentMatch)
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
    fun theFailureOverlayStaysUntilAnotherDocumentIsOnScreen() {
        val state = loadedState()
        state.onPageStarted("https://example.com/gone")
        state.pageFailure = PageFailure("https://example.com/gone", PageFailureKind.NETWORK, "ERR")
        // A retry starts on the same URL: the engine is still showing its own error page, so the
        // overlay must not uncover it while the retry runs.
        state.onPageStarted("https://example.com/gone")
        assertNotNull(state.pageFailure)
        state.onPageCommitVisible("https://example.com/gone")
        assertNull(state.pageFailure)
        // Another destination also keeps the engine's previous error document covered.
        state.pageFailure = PageFailure("https://example.com/gone", PageFailureKind.NETWORK, "ERR")
        state.onNavigationRequested("https://example.com/next")
        assertNotNull(state.pageFailure)
        state.onPageStarted("https://example.com/next")
        assertNotNull(state.pageFailure)
        state.onPageCommitVisible("https://example.com/next")
        assertNull(state.pageFailure)
    }

    @Test
    fun theProgressBarIsVisibleBeforeTheFirstPercentage() {
        val state = loadedState()
        state.onNavigationRequested("https://example.com/slow")
        assertTrue(state.isProgressVisible)
        assertEquals(0, state.progress)
        // Chromium can report 10% and wait for response headers before onPageStarted.
        state.onProgressChanged(10)
        assertTrue(state.isProgressVisible)
        state.onPageStarted("https://example.com/slow")
        state.onProgressChanged(40)
        assertTrue(state.isProgressVisible)
        state.onProgressChanged(100)
        assertFalse(state.isProgressVisible)
    }

    @Test
    fun anErrorDocumentCommitDoesNotDismissRecovery() {
        val state = loadedState()
        val url = "https://example.com/unreachable"
        repeat(2) {
            state.onNavigationRequested(url)
            state.onPageStarted(url)
            state.onPageError()
            val failure = PageFailure(url, PageFailureKind.NETWORK, "ERR_CONNECTION_REFUSED")
            state.pageFailure = failure
            // This is the observed WebView order, including a commit after finish.
            state.onPageFinished(url, true, false)
            state.onProgressChanged(100)
            state.onPageCommitVisible(url)
            assertEquals(failure, state.pageFailure)
            assertFalse(state.isLoading)
        }
        state.onNavigationRequested(url)
        state.onPageCommitVisible(url) // A late commit from the previous attempt.
        assertNotNull(state.pageFailure)
        state.onPageStarted(url)
        state.onPageFinished(url, true, false)
        state.onPageCommitVisible(url)
        assertNull(state.pageFailure)
    }

    @Test
    fun pageInitiatedLoadingIgnoresLateProgressUntilTheStoppedLoadFinishes() {
        val state = loadedState()
        state.onProgressChanged(10)
        assertTrue(state.isLoading)
        state.onPageError()
        state.onProgressChanged(80)
        assertFalse(state.isLoading)
        state.onNavigationRequested("https://example.com/retry")
        assertTrue(state.isLoading)
        state.onLoadStopped()
        state.onProgressChanged(90)
        assertFalse(state.isLoading)
        state.onProgressChanged(100)
        state.onPageFinished(state.currentUrl, true, false)
        state.onProgressChanged(10) // A new POST form submission after Stop.
        assertTrue(state.isLoading)
        state.onNavigationRequested("about:blank")
        assertNull(state.pageFailure)
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
    @Test
    fun immersiveFullscreenRevealsChromeForEditingAndResetsWhenVideoTakesOver() {
        val state = loadedState()
        // Normal browsing: focus and find never touch the reveal flag.
        state.onOmnibarFocusChange(true)
        assertFalse(state.isChromeRevealed)
        state.onOmnibarFocusChange(false)
        state.showFindBar()
        assertFalse(state.isChromeRevealed)
        state.hideFindBar()

        state.onImmersiveFullscreenChanged(true)
        assertTrue(state.isImmersiveFullscreen)
        assertFalse(state.isChromeRevealed)

        // Focusing the omnibar or opening find reveals the chrome.
        state.onOmnibarFocusChange(true)
        assertTrue(state.isChromeRevealed)
        state.onOmnibarFocusChange(false)
        state.hideChrome()
        assertFalse(state.isChromeRevealed)
        state.revealChrome()
        assertTrue(state.isChromeRevealed)
        // A reveal also forces the scroll-collapse flag visible.
        assertFalse(state.isToolbarHidden)
        state.hideChrome()

        state.showFindBar()
        assertTrue(state.isChromeRevealed)

        // Video fullscreen takes the screen: immersive deactivates and the reveal resets.
        state.onImmersiveFullscreenChanged(false)
        assertFalse(state.isImmersiveFullscreen)
        assertFalse(state.isChromeRevealed)
        // Coming back from video keeps the chrome hidden until asked for again.
        state.onImmersiveFullscreenChanged(true)
        assertFalse(state.isChromeRevealed)
    }
}
