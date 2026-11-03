package com.mybrowser.core

import android.app.Application
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
