package com.mybrowser.media

import android.app.Application
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import com.mybrowser.tabs.MediaNavigation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaResponseHandoffTest {
    private class Body : java.io.ByteArrayInputStream(byteArrayOf(1)) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private fun request(url: String, main: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = url.toUri()
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun onlyTheOwningMainFrameCanConsumeTheResponseOnce() {
        val handoff = MediaResponseHandoff()
        val original = MediaNavigation.create("https://media.test/start", "https://source.test/watch")!!
        val next = original.follow("https://login.test/landing", true, "no-referrer")!!
        val body = Body()
        val response = WebResourceResponse("text/html", "UTF-8", body)
        handoff.offer(next, response)
        assertNull(handoff.take(request(original.url), original))
        assertNull(handoff.take(request(next.url, main = false), next))
        assertSame(response, handoff.take(request(next.url), next))
        assertNull(handoff.take(request(next.url), next))
        handoff.close()
        assertFalse(body.closed)
        response.data.close()
    }

    @Test fun navigationReplacementCancellationAndTimeoutCloseTheUnclaimedStream() {
        val handoff = MediaResponseHandoff()
        val navigation = MediaNavigation.create("https://media.test/start", "https://source.test/watch")!!
        fun pending(): Pair<WebResourceResponse, Body> {
            val body = Body()
            val response = WebResourceResponse("text/html", "UTF-8", body)
            handoff.offer(navigation, response)
            return response to body
        }
        val first = pending()
        val second = pending()
        assertTrue(first.second.closed)
        handoff.discard(first.first) // A stale timeout must not close a replacement.
        assertFalse(second.second.closed)
        handoff.retain(navigation.forLoad())
        assertTrue(second.second.closed)
        val third = pending()
        navigation.loadId.cancelled = true
        assertNull(handoff.take(request(navigation.url), navigation))
        assertTrue(third.second.closed)
        val fourth = pending()
        handoff.discard(fourth.first)
        assertTrue(fourth.second.closed)
    }
}
