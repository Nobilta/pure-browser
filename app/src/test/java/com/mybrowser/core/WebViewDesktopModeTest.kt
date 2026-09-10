package com.mybrowser.core

import android.app.Application
import android.webkit.WebView
import com.mybrowser.site.SiteSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebViewDesktopModeTest {
    @Test fun redirectSettingsKeepDesktopUaAndDisablingRestoresMobileUa() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = SiteSettingsRepository(context)
        val view = WebView(context)
        try {
            view.settings.userAgentString = MOBILE_UA
            repo.update("https://m.jrs16.com") { it.copy(desktop = true) }
            for (url in listOf("https://m.jrs16.com/", "https://www.jrs16.com/", "https://m.jrs16.com/")) {
                WebViewConfig.applySiteSettings(view, repo.get(url))
                assertTrue(view.settings.userAgentString.contains("X11; Linux x86_64"))
                assertFalse(view.settings.userAgentString.contains("Mobile"))
            }
            repo.update("https://www.jrs16.com") { it.copy(desktop = false) }
            WebViewConfig.applySiteSettings(view, repo.get("https://m.jrs16.com"))
            assertTrue(view.settings.userAgentString.contains("Mobile"))
            assertFalse(view.settings.userAgentString.contains("; wv"))
        } finally { view.destroy() }
    }

    @Test fun viewportIsAppliedToTheFinishedDocumentInsteadOfDuringNavigation() {
        val view = WebView(RuntimeEnvironment.getApplication())
        try {
            view.settings.userAgentString = MOBILE_UA
            view.loadUrl("https://m.jrs16.com/")
            WebViewConfig.applyDesktopMode(view, true)
            assertNull(shadowOf(view).lastEvaluatedJavascript)
            WebViewConfig.applyDesktopViewport(view)
            assertTrue(shadowOf(view).lastEvaluatedJavascript.contains("width=1024"))
        } finally { view.destroy() }
    }

    private companion object {
        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Test; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/113.0.0.0 Mobile Safari/537.36"
    }
}
