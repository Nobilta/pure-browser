package com.mybrowser.ui.shell

import android.app.Application
import android.content.Intent
import android.webkit.WebView
import androidx.core.net.toUri
import com.mybrowser.core.NavigationReadiness
import com.mybrowser.search.SearchEngine
import com.mybrowser.tabs.TabManager
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageNavigatorTest {
    private class HistoryView : WebView(RuntimeEnvironment.getApplication()) {
        override fun canGoBackOrForward(steps: Int): Boolean = copyBackForwardList().let {
            it.currentIndex + steps in 0 until it.size
        }
    }

    private class Fixture(maxTabs: Int = 32) {
        val state = BrowserState()
        var view: WebView = HistoryView()
        var tabs = TabManager(maxTabs)
        var engine = SearchEngine.BAIDU
        var home = "about:blank"
        var epoch = 0L
        val prepared = mutableListOf<String>()
        val external = mutableListOf<String>()
        val newTabs = mutableListOf<String>()
        val imports = mutableListOf<String>()
        val navigator = PageNavigator(
            state, { view }, { tabs }, { engine }, { home }, { epoch },
            NavigationReadiness(TestScope(), { true }, { true }, { true }, {}, {}),
            prepareNavigation = { prepared += it },
            importUserScript = { url ->
                url.endsWith(".user.js").also { if (it) imports += url }
            },
            openExternal = { external += it },
            openNewTab = { newTabs += it },
        )
    }

    @Test fun navigationUsesTheCurrentRendererProfileAndSearchEngine() {
        val f = Fixture()
        val oldView = f.view
        val oldTabs = f.tabs
        f.view = HistoryView()
        f.tabs = TabManager()
        f.engine = SearchEngine.BING
        val expected = f.engine.buildSearchUrl("two words")
        f.navigator.navigate("two words")
        assertEquals(expected, shadowOf(f.view).lastLoadedUrl)
        assertEquals(expected, f.tabs.currentTab?.url)
        assertEquals(expected, f.state.currentUrl)
        assertEquals(listOf(expected), f.prepared)
        assertNull(shadowOf(oldView).lastLoadedUrl)
        assertEquals("", oldTabs.currentTab?.url)
    }

    @Test fun scriptsAndExternalSchemesDoNotReplaceTheCurrentPage() {
        val f = Fixture()
        f.navigator.navigate("https://example.com/page")
        val before = f.state.currentUrl
        f.navigator.navigate("https://example.com/install.user.js")
        f.navigator.navigate("mailto:hello@example.com")
        assertEquals(listOf("https://example.com/install.user.js"), f.imports)
        assertEquals(listOf("mailto:hello@example.com"), f.external)
        assertEquals(before, f.state.currentUrl)
        assertEquals(before, f.tabs.currentTab?.url)
        assertEquals(listOf(before), f.prepared)
    }

    @Test fun incomingLinksReuseHomeButPreserveAnExistingArticle() {
        val f = Fixture()
        f.navigator.openIncoming("https://example.com/first")
        assertEquals("https://example.com/first", shadowOf(f.view).lastLoadedUrl)
        assertTrue(f.newTabs.isEmpty())
        f.navigator.openIncoming("https://example.com/second")
        assertEquals(listOf("https://example.com/second"), f.newTabs)
        assertEquals("https://example.com/first", f.tabs.currentTab?.url)
        f.navigator.openIncoming("https://example.com/install.user.js")
        assertEquals(1, f.newTabs.size)
        assertEquals(1, f.imports.size)
    }

    @Test fun incomingLinkReusesTheCurrentTabWhenTheListIsFull() {
        val f = Fixture(maxTabs = 1)
        f.navigator.navigate("https://example.com/first")
        f.navigator.openIncoming("https://example.com/second")
        assertEquals("https://example.com/second", f.tabs.currentTab?.url)
        assertTrue(f.newTabs.isEmpty())
    }

    @Test fun homepageChangesAreReadAtTheTimeOfNavigation() {
        val f = Fixture()
        f.home = "https://example.com/home"
        f.navigator.goHome()
        assertEquals(f.home, shadowOf(f.view).lastLoadedUrl)
        f.navigator.openIncoming("https://example.com/article")
        assertTrue(f.newTabs.isEmpty())
        f.home = "about:blank"
        assertFalse(f.navigator.isHomeDocument("https://example.com/home"))
        assertTrue(f.navigator.isHomeDocument("about:blank"))
    }

    @Test fun historySelectionCannotApplyToAReplacementDocument() {
        val f = Fixture()
        shadowOf(f.view).pushEntryToHistory("https://example.com/first")
        shadowOf(f.view).pushEntryToHistory("https://example.com/second")
        f.navigator.showBackHistory()
        assertEquals(-1, f.navigator.backHistory?.single()?.offset)
        f.epoch++
        f.navigator.selectBackHistory(-1)
        assertEquals(1, f.view.copyBackForwardList().currentIndex)
        assertNull(f.navigator.backHistory)
        f.navigator.showBackHistory()
        f.navigator.selectBackHistory(-1)
        assertEquals(0, f.view.copyBackForwardList().currentIndex)
        assertEquals("https://example.com/first", f.state.currentUrl)
        f.navigator.navigateHistory(-99)
        assertEquals("https://example.com/first", f.state.currentUrl)
    }

    @Test fun onlyViewAndSendIntentsWithNonblankTextNavigate() {
        assertEquals("https://example.com", PageNavigator.intentNavigationText(
            Intent(Intent.ACTION_VIEW, "https://example.com".toUri())))
        assertEquals("shared text", PageNavigator.intentNavigationText(
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  shared text  ")))
        assertNull(PageNavigator.intentNavigationText(Intent(Intent.ACTION_MAIN)))
        assertNull(PageNavigator.intentNavigationText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "  ")))
    }
}
