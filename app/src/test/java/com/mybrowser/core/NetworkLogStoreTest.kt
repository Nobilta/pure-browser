package com.mybrowser.core

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkLogStoreTest {
    @Test fun pageFinishedNeverInventsA200AndKeepsPageLoadSeparate() {
        val store = NetworkLogStore()
        store.beginPage("https://example.com/")
        store.markPageFinished("https://example.com/", pageLoadMs = 321)
        val main = store.snapshot().single { it.isForMainFrame }
        assertNull(main.statusCode)
        assertNull(main.errorCode)
        assertNull(main.durationMs)
        assertEquals(321L, main.pageLoadMs)
        assertTrue(main.completed)
        assertTrue(main.settled)
        assertFalse(main.blocked)
    }

    @Test fun nativeRequestStartedAfterPageFinishIsSettledAsUnknown() {
        val store = NetworkLogStore()
        store.beginPage("https://example.com/")
        store.markPageFinished("https://example.com/", pageLoadMs = 10)
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://example.com/late.png")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
        val id = store.recordRequest(request, "https://example.com/", ResourceType.IMAGE)
        val late = store.snapshot().single { it.id == id }
        assertTrue(late.settled)
        assertTrue(late.durationMs == null)
        assertFalse(late.completed)
        assertNull(late.statusCode)
    }

    @Test fun clearingTheLogDoesNotTurnALateRequestBackIntoInProgress() {
        val store = NetworkLogStore()
        store.beginPage("https://example.com/")
        store.markPageFinished("https://example.com/", pageLoadMs = 10)
        store.clear()
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://example.com/late-after-clear.png")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }
        val id = store.recordRequest(request, "https://example.com/", ResourceType.IMAGE)
        val late = store.snapshot().single { it.id == id }
        assertTrue(late.settled)
        assertNull(late.durationMs)
    }

    @Test fun ringEvictionReportsTheRecordWhoseBodyMustBeReleased() {
        val evicted = mutableListOf<NetworkRequestLog>()
        val store = NetworkLogStore(maxEntries = 2, onEvicted = { evicted += it })
        store.beginPage("https://example.com/")
        store.markPageFinished("https://example.com/", pageLoadMs = 1)
        val first = store.snapshot().first()
        store.recordPageScript("c1", "GET", "https://example.com/a", ResourceType.XML_HTTP_REQUEST, false, "https://example.com/")
        store.recordPageScript("c2", "GET", "https://example.com/b", ResourceType.XML_HTTP_REQUEST, false, "https://example.com/")
        assertTrue(evicted.isNotEmpty())
        assertEquals(first.id, evicted.first().id)
    }
}
