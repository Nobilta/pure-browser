package com.mybrowser.ui

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
 * Not a ViewModel on purpose. The Activity declares `configChanges` covering rotation, so
 * it is never recreated in normal use and there is no state to survive. Adding a
 * ViewModel would buy nothing and obscure that the WebView pool, not this class, is what
 * makes rotation cheap.
 */
@Stable
class BrowserState {

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
     * Hidden outside 1..99. At 0 the load has not started and at 100 it is done; showing
     * an empty or full bar in those states reads as a stuck page.
     */
    val isProgressVisible: Boolean
        get() = isLoading && progress in 1..99

    val securityLevel: SecurityLevel
        get() = when {
            currentUrl == ABOUT_BLANK -> SecurityLevel.NONE
            UrlUtils.isHttps(currentUrl) -> SecurityLevel.SECURE
            else -> SecurityLevel.INSECURE
        }

    // --- WebView callbacks push in here ---

    fun onPageStarted(url: String) {
        revealToolbar()
        currentUrl = url
        isLoading = true
        progress = 0
        // Cleared here, not in onPageFinished: the old title would otherwise label the new
        // page for the whole load.
        pageTitle = null
        syncOmnibarToUrl()
    }

    fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        isLoading = false
        onHistoryUpdated(url, canGoBack, canGoForward)
    }

    fun onHistoryUpdated(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        currentUrl = url
        this.canGoBack = canGoBack
        this.canGoForward = canGoForward
        syncOmnibarToUrl()
    }

    fun onPageError() {
        isLoading = false
    }

    fun onProgressChanged(value: Int) {
        progress = value
        // A progress report is the only signal that a same-document navigation
        // (history.pushState) finished, so loading has to be able to end here too.
        if (value >= 100) isLoading = false
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

    enum class SecurityLevel { NONE, SECURE, INSECURE }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
    }
}
