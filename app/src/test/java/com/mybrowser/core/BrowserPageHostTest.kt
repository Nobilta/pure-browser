package com.mybrowser.core

import android.app.Activity
import android.app.Application
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserPageHostTest {
    @Test fun localBindingSurvivesNavigationButRemoteDocumentsCannotUseItForSubresources() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val host = BrowserPageHost(activity.get()) { }
        val uri = android.net.Uri.parse("content://html.test/index.html")
        fun stream() = shadowOf(activity.get().contentResolver).registerInputStream(uri,
            java.io.ByteArrayInputStream("<h1>local</h1>".toByteArray()))
        stream()
        val document = com.mybrowser.text.LocalHtmlDocument.open(activity.get(), uri)
        try {
            val view = host.acquire({}, { false })
            host.bindLocalDocument(view, document)
            fun request(mainFrame: Boolean, headers: Map<String, String> = emptyMap()) = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse(document.url)
                override fun isForMainFrame() = mainFrame
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = headers
            }
            val localHeaders = mapOf("Referer" to document.url)
            assertEquals(404, host.localResponse(view, request(false, localHeaders), "https://remote.test/")!!.statusCode)
            for (headers in listOf(emptyMap(), mapOf("Referer" to "https://remote.test/"),
                    mapOf("Origin" to "https://remote.test/"), mapOf("Origin" to "null"))) {
                assertEquals(404, host.localResponse(view, request(false, headers), document.url)!!.statusCode)
            }
            for ((main, source, headers) in listOf(Triple(true, "https://remote.test/", emptyMap()),
                    Triple(false, document.url, localHeaders),
                    Triple(false, document.url, mapOf("Origin" to com.mybrowser.site.SiteOrigin.of(document.url)!!)))) {
                stream()
                val response = host.localResponse(view, request(main, headers), source)!!
                assertEquals("text/html", response.mimeType)
                assertEquals("<h1>local</h1>", response.data.bufferedReader().use { it.readText() })
                assertEquals("same-origin", response.responseHeaders["Referrer-Policy"])
            }
            host.discard(view)
        } finally { host.close(); activity.pause().stop().destroy() }
    }

    @Test fun failedConfigurationReleasesTheSameViewBeforeEvictionAndRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val events = mutableListOf<String>()
        val released = mutableListOf<WebView>()
        val host = BrowserPageHost(activity.get()) { released += it; events += "release" }
        var attempts = 0
        try {
            val view = host.acquire(configure = {
                if (attempts++ == 0) throw OutOfMemoryError("fixture")
            }, evict = {
                assertTrue(shadowOf(released.single()).wasDestroyCalled())
                events += "evict"
                true
            })
            assertEquals(listOf("release", "evict"), events)
            assertNotSame(released.single(), view)
            host.current = view
            host.close()
            assertNull(host.current)
            assertEquals(2, released.size)
            assertTrue(shadowOf(view).wasDestroyCalled())
        } finally { host.close(); activity.pause().stop().destroy() }
    }

    @Test fun closingDuringPopupTransportReleasesChannelsOnceAndDefersNativeContents() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val released = mutableListOf<WebView>()
        val host = BrowserPageHost(activity.get()) { released += it }
        try {
            val opener = host.acquire({}, { false })
            val popup = host.acquire({}, { false })
            host.holdForPopup(opener, popup)
            host.current = popup
            host.ownerTabId = "popup"
            host.close()
            assertEquals(setOf(opener, popup), released.toSet())
            assertFalse(shadowOf(opener).wasDestroyCalled())
            assertFalse(shadowOf(popup).wasDestroyCalled())
            assertNull(host.ownerTabId)
            host.onPopupContentsAttached(popup, attach = false)
            host.discard(popup)
            assertTrue(shadowOf(opener).wasDestroyCalled())
            assertTrue(shadowOf(popup).wasDestroyCalled())
            assertEquals(2, released.size)
        } finally { host.close(); activity.pause().stop().destroy() }
    }
}
