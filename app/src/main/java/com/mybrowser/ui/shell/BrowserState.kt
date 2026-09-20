package com.mybrowser.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mybrowser.core.UrlUtils

/**
 * Observable browser chrome state.
 *
 * Holds what the UI draws — URL, progress, navigation availability, omnibar contents —
 * separately from the WebView itself, which stays imperative and Activity-owned. The
 * WebView callbacks push into here; Compose reads out.
 *
 * Every property is backed by a snapshot state object, so [Stable] is accurate rather
 * than aspirational: Compose can skip recomposition when this instance is unchanged.
 *
 * Session ownership and configuration recreation are managed by BrowserSessionState;
 * this object owns the current chrome and editing state, not the WebView lifecycle.
 */
@Stable
class BrowserState {
    var pageFailure: com.mybrowser.core.PageFailure? by mutableStateOf(null)

    var currentUrl: String by mutableStateOf(ABOUT_BLANK)

    /** Null when the page has no usable title; the cast label falls back to the host. */
    var pageTitle: String? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    /** 0..100. Only meaningful while [isProgressVisible]. */
    var progress: Int by mutableIntStateOf(0)
        private set

    var canGoBack: Boolean by mutableStateOf(false)
        private set

    var canGoForward: Boolean by mutableStateOf(false)
        private set

    /**
     * Omnibar contents. A [TextFieldValue] rather than a String because the selection has
     * to be controlled: focusing selects everything so the next keystroke replaces the
     * URL, which is what every browser does and what makes the bar usable one-handed.
     */
    var omnibarValue: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    var isOmnibarFocused: Boolean by mutableStateOf(false)
        private set

    var isToolbarHidden: Boolean by mutableStateOf(false)
        private set
    private var scrollDistance = 0f

    // --- Immersive browser fullscreen ---

    /** Mirror of the fullscreen preference combined with "no video fullscreen", set by the screen. */
    var isImmersiveFullscreen: Boolean by mutableStateOf(false)
        private set

    /**
     * The user temporarily revealed the omnibar and toolbar while immersive. Kept apart
     * from [isToolbarHidden] on purpose: that flag is scroll-driven and page callbacks
     * keep resetting it, so it must never stand in for the immersive chrome state.
     */
    var isChromeRevealed: Boolean by mutableStateOf(false)
        private set

    fun onImmersiveFullscreenChanged(active: Boolean) {
        if (isImmersiveFullscreen == active) return
        isImmersiveFullscreen = active
        if (!active) isChromeRevealed = false
    }

    fun revealChrome() {
        isChromeRevealed = true
        revealToolbar()
    }

    fun hideChrome() {
        isChromeRevealed = false
    }

    val displayTitle: String
        get() = if (currentUrl == ABOUT_BLANK) "" else pageTitle ?: UrlUtils.forDisplay(currentUrl)

    fun revealToolbar() {
        isToolbarHidden = false
        scrollDistance = 0f
    }

    fun onPageScroll(scrollY: Int, oldScrollY: Int, density: Float) {
        if (scrollY <= 0 || currentUrl == ABOUT_BLANK || isOmnibarFocused ||
            isFindBarVisible || isLoading
        ) {
            revealToolbar()
            return
        }
        val delta = (scrollY - oldScrollY) / density.coerceAtLeast(1f)
        if (delta == 0f) return
        if (delta * scrollDistance < 0) scrollDistance = 0f
        scrollDistance += delta
        if (scrollDistance >= 48f) {
            isToolbarHidden = true
            scrollDistance = 0f
        } else if (scrollDistance <= -24f) {
            revealToolbar()
        }
    }

    // --- Find in page ---

    var isFindBarVisible: Boolean by mutableStateOf(false)
        private set

    var findQuery: String by mutableStateOf("")
        private set

    var findMatchCount: Int by mutableIntStateOf(0)
        private set

    var findCurrentMatch: Int by mutableIntStateOf(0)
        private set

    // --- Desktop mode ---

    var isDesktopMode: Boolean by mutableStateOf(false)

    /**
     * Visible for as long as a load is running, including before the first percentage arrives: a
     * server that accepts the connection and then stalls never reports one, and a page with no
     * signal at all is what makes a slow load look frozen. At 0 the bar is drawn indeterminate.
     */
    val isProgressVisible: Boolean
        get() = isLoading

    // --- WebView callbacks push in here ---

    private var navigationTerminated = false
    private var documentStarted = false

    /** WebView can defer onPageStarted until response headers arrive. */
    fun onNavigationRequested(url: String) {
        beginNavigation(url)
        documentStarted = false
    }

    private fun beginNavigation(url: String) {
        // Keep the previous error document covered until the replacement commits. Home has its
        // own surface, so it does not need a recovery overlay while about:blank is loading.
        if (url == ABOUT_BLANK) pageFailure = null
        navigationTerminated = false
        hideFindBar()
        revealToolbar()
        currentUrl = url
        isLoading = true
        progress = 0
        pageTitle = null
        syncOmnibarToUrl()
    }

    fun onPageStarted(url: String) {
        beginNavigation(url)
        documentStarted = true
    }

    fun onPageCommitVisible(url: String) {
        // Chromium commits its own error document too, after onReceivedError/onPageFinished.
        // A pending retry also must not accept a late commit from the preceding failed load.
        if (documentStarted && !navigationTerminated && url == currentUrl) pageFailure = null
    }

    fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        finishLoading()
        onHistoryUpdated(url, canGoBack, canGoForward)
    }

    private fun finishLoading() {
        isLoading = false
        // After a stopped load finishes, the still-visible page can submit a POST form. Its
        // first progress callback must be able to start a new load without an override callback.
        // Failed documents retain their guard through finish and the following error-page commit.
        if (pageFailure == null) navigationTerminated = false
    }

    fun onHistoryUpdated(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        currentUrl = url
        this.canGoBack = canGoBack
        this.canGoForward = canGoForward
        syncOmnibarToUrl()
    }

    fun onPageError() {
        navigationTerminated = true
        isLoading = false
    }

    fun onLoadStopped() {
        navigationTerminated = true
        isLoading = false
    }

    fun onProgressChanged(value: Int) {
        progress = value.coerceIn(0, 100)
        if (progress >= 100) finishLoading()
        else if (!navigationTerminated) {
            // Covers navigations initiated inside WebView, including POST forms that do not
            // invoke shouldOverrideUrlLoading, and same-document loads without onPageStarted.
            isLoading = true
            revealToolbar()
        }
    }

    /** Blank titles are normalised to null so the cast label can fall back to the host. */
    fun onTitleChanged(title: String?) {
        pageTitle = title?.trim()?.takeIf { it.isNotBlank() }
    }

    // --- omnibar ---

    fun onOmnibarValueChange(value: TextFieldValue) {
        omnibarValue = value
    }

    fun onOmnibarFocusChange(focused: Boolean) {
        if (isOmnibarFocused == focused) return
        isOmnibarFocused = focused
        if (focused) {
            revealToolbar()
            if (isImmersiveFullscreen) isChromeRevealed = true
            // Show the real URL for editing, not the trimmed display form, and select it
            // all so typing replaces rather than appends.
            val text = currentUrl.takeUnless { it == ABOUT_BLANK }.orEmpty()
            omnibarValue = TextFieldValue(text = text, selection = TextRange(0, text.length))
        } else {
            // Only sync if not already synced to avoid cursor jumping
            if (omnibarValue.text != UrlUtils.forDisplay(currentUrl)) {
                syncOmnibarToUrl()
            }
        }
    }

    fun clearOmnibar() {
        omnibarValue = TextFieldValue()
    }

    // --- Find in page ---

    fun showFindBar() {
        revealToolbar()
        if (isImmersiveFullscreen) isChromeRevealed = true
        isFindBarVisible = true
    }

    fun hideFindBar() {
        isFindBarVisible = false
        findQuery = ""
        findMatchCount = 0
        findCurrentMatch = 0
    }

    fun onFindQueryChange(query: String) {
        findQuery = query
    }

    fun onFindResultUpdate(activeMatchOrdinal: Int, numberOfMatches: Int) {
        findCurrentMatch = activeMatchOrdinal
        findMatchCount = numberOfMatches
    }

    // --- Desktop mode ---

    fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
    }

    /**
     * Pushes the current URL into the omnibar, but never while it has focus — a page
     * finishing in the background would otherwise overwrite half-typed input.
     */
    private fun syncOmnibarToUrl() {
        if (isOmnibarFocused) return
        omnibarValue = TextFieldValue(UrlUtils.forDisplay(currentUrl))
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
    }
}
