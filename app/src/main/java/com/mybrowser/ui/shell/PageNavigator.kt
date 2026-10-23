package com.mybrowser.ui.shell

import android.content.Intent
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.core.NavigationPolicy
import com.mybrowser.core.NavigationReadiness
import com.mybrowser.core.NavigationTarget
import com.mybrowser.search.SearchEngine
import com.mybrowser.tabs.TabManager

/**
 * Coordinates URL/search requests, page history and links handed in by other apps.
 * WebView and tab accessors always resolve the current page/profile; none is retained across
 * a tab switch or renderer replacement. Page cleanup and site configuration remain with the host.
 */
internal class PageNavigator(
    private val state: BrowserState,
    private val webView: () -> WebView,
    private val tabs: () -> TabManager,
    private val searchEngine: () -> SearchEngine,
    private val homeUrl: () -> String,
    private val documentEpoch: () -> Long,
    private val readiness: NavigationReadiness,
    private val prepareNavigation: (String) -> Unit,
    private val importUserScript: (String) -> Boolean,
    private val openExternal: (String) -> Unit,
    private val openNewTab: (String) -> Unit,
) {
    var backHistory by mutableStateOf<List<BackHistoryEntry>?>(null)
        private set
    private var backHistoryEpoch = 0L

    fun load(url: String) {
        if (readiness.awaitPrivacy { load(url) }) return
        state.onNavigationRequested(url)
        webView().loadUrl(url)
    }

    fun reload() {
        if (readiness.awaitPrivacy(::reload)) return
        val view = webView()
        state.onNavigationRequested(view.url ?: state.currentUrl)
        view.reload()
    }

    fun navigateHistory(offset: Int) {
        if (readiness.awaitPrivacy { navigateHistory(offset) }) return
        val view = webView()
        if (!view.canGoBackOrForward(offset)) return
        val history = view.copyBackForwardList()
        val target = history.getItemAtIndex(history.currentIndex + offset) ?: return
        state.onNavigationRequested(target.url)
        view.goBackOrForward(offset)
    }

    fun navigate(input: String) {
        if (readiness.awaitPrivacy { navigate(input) }) return
        if (readiness.awaitScripts { navigate(input) }) return
        when (val target = NavigationPolicy.resolve(input, searchEngine())) {
            is NavigationTarget.Load -> {
                if (importUserScript(target.url)) return
                prepareNavigation(target.url)
                load(target.url)
                tabs().let { manager ->
                    manager.currentTab?.url = target.url
                    manager.notifyChanged()
                }
            }
            is NavigationTarget.External -> openExternal(target.url)
        }
    }

    fun goHome() = navigate(homeUrl())

    fun isHomeDocument(url: String): Boolean = url == ABOUT_BLANK || url == homeUrl()

    /** Reuse an empty/home tab or a full tab list; preserve an existing page otherwise. */
    fun openIncoming(text: String) {
        if (importUserScript(text)) return
        val manager = tabs()
        when (val target = NavigationPolicy.resolve(text, searchEngine())) {
            is NavigationTarget.External -> openExternal(target.url)
            is NavigationTarget.Load -> {
                // At cold start the restored tab's URL is already in the manager while the
                // WebView is still catching up, so state.currentUrl alone is not enough to
                // decide whether this is a fresh tab.
                val current = if (isHomeDocument(state.currentUrl)) manager.currentTab?.url.orEmpty()
                else state.currentUrl
                if (isHomeDocument(current) || current.isBlank() || !manager.canCreateTab) {
                    navigate(target.url)
                } else {
                    openNewTab(target.url)
                }
            }
        }
    }

    fun showBackHistory() {
        val history = webView().copyBackForwardList()
        backHistoryEpoch = documentEpoch()
        backHistory = (history.currentIndex - 1 downTo 0).take(64).map { index ->
            val item = history.getItemAtIndex(index)
            BackHistoryEntry(index - history.currentIndex, item.title.orEmpty(), item.url.orEmpty())
        }
    }

    fun selectBackHistory(offset: Int) {
        dismissBackHistory()
        if (backHistoryEpoch == documentEpoch()) navigateHistory(offset)
    }

    fun dismissBackHistory() { backHistory = null }

    fun exitSite() {
        dismissBackHistory()
        webView().clearHistory()
        goHome()
    }

    companion object {
        private const val ABOUT_BLANK = "about:blank"

        /** A launcher/task switch without text must leave the current page alone. */
        fun intentNavigationText(intent: Intent): String? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}
