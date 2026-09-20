package com.mybrowser.core

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PopupWebViewClientTest {
    private class Page : PopupWebViewClient.Listener {
        var alive = true
        var url = "https://origin.example/login"
        var documentSeenByFilter = ""
        var loading = false
        override fun isAlive() = alive
        override fun onNavigation(url: String) { this.url = url }
        override fun onStarted(url: String) { this.url = url; loading = true }
        override fun onFinished(url: String) { loading = false }
        override fun onHistory(url: String) { this.url = url }
        override fun onError(url: String, code: Int, description: String) { loading = false }
        override fun onIntercept(request: WebResourceRequest): WebResourceResponse? {
            documentSeenByFilter = url
            return null
        }
        override fun onGone(view: WebView, crashed: Boolean) { alive = false }
    }

    @Test fun callbackNavigationAndFetchUseTheOpenerDocumentWithoutBlankResponses() {
        val page = Page()
        val client = PopupWebViewClient(page)
        val view = WebView(RuntimeEnvironment.getApplication())
        try {
            val callback = "https://origin.example/callback"
            assertFalse(client.shouldOverrideUrlLoading(view, request(callback, true)))
            client.onPageStarted(view, callback, null)
            assertTrue(page.loading)
            assertNull(client.shouldInterceptRequest(view, request("https://origin.example/session", false)))
            assertEquals(callback, page.documentSeenByFilter)
            client.onPageFinished(view, callback)
            assertFalse(page.loading)
            assertTrue(client.shouldOverrideUrlLoading(view, request("intent://launch/#Intent;end", true)))
            assertEquals(callback, page.url)
        } finally { view.destroy() }
    }

    @Test fun closedSessionCannotChangeItsMetadataOrStartAnExternalApp() {
        val page = Page()
        val client = PopupWebViewClient(page)
        val view = WebView(RuntimeEnvironment.getApplication())
        try {
            page.alive = false
            assertTrue(client.shouldOverrideUrlLoading(view, request("https://other.example/", true)))
            client.onPageStarted(view, "https://other.example/", null)
            assertEquals("https://origin.example/login", page.url)
            assertFalse(page.loading)
        } finally { view.destroy() }
    }

    private fun request(url: String, main: Boolean) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
