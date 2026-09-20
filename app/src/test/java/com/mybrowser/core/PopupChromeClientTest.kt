package com.mybrowser.core

import android.app.Application
import android.webkit.WebView
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PopupChromeClientTest {
    @Test fun hiddenWindowCloseAndTitleKeepTheirSourceInsteadOfTargetingTheThirdTab() {
        val context = RuntimeEnvironment.getApplication()
        val popup = WebView(context)
        val selected = WebView(context)
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val listener = Proxy.newProxyInstance(
            BrowserChromeClient.Listener::class.java.classLoader,
            arrayOf(BrowserChromeClient.Listener::class.java),
        ) { _, method, args ->
            if (method.name == "isCurrentWebView") args?.first() === selected
            else { calls += method.name to args.orEmpty().toList(); null }
        } as BrowserChromeClient.Listener
        try {
            val client = BrowserChromeClient(listener, popup)
            client.onReceivedTitle(popup, "Signed in")
            client.onProgressChanged(popup, 100)
            client.onCloseWindow(popup)
            assertEquals(listOf("onBackgroundTitleChanged", "onCloseWindow"), calls.map { it.first })
            assertSame(popup, calls[0].second[0])
            assertSame(popup, calls[1].second.single())
        } finally { popup.destroy(); selected.destroy() }
    }
}
