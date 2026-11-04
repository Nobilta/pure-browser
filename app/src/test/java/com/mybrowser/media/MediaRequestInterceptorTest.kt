package com.mybrowser.media

import android.app.Application
import android.webkit.WebResourceRequest
import androidx.core.net.toUri
import com.mybrowser.tabs.MediaNavigation
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaRequestInterceptorTest {
    private fun request(url: String, main: Boolean = false, headers: Map<String, String> = emptyMap()) = object : WebResourceRequest {
        override fun getUrl() = url.toUri()
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    @Test fun guardedRangeResponsePreservesBytesStatusAndRequestCredentials() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        server.createContext("/media.mp4") { exchange ->
            seen += listOf("Referer", "Range", "Cookie", "Authorization", "If-None-Match").associateWith {
                exchange.requestHeaders.getFirst(it).orEmpty()
            }
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Range", "bytes 3-5/9")
            exchange.sendResponseHeaders(206, 3)
            exchange.responseBody.use { it.write(byteArrayOf(3, 4, 5)) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mp4"
            val ref = "http://site.example/watch?id=1"
            val response = MediaRequestInterceptor.intercept(request(url, headers = mapOf(
                "Referer" to url, "Range" to "bytes=3-5", "Cookie" to "session=one",
                "Authorization" to "Bearer scoped", "If-None-Match" to "stale-cache")), MediaNavigation.create(url, ref), null) { true }!!
            assertEquals(206, response.statusCode)
            assertEquals("bytes 3-5/9", response.responseHeaders.entries.first { it.key.equals("Content-Range", true) }.value)
            assertEquals(6, response.data.available())
            assertEquals(3L, response.data.skip(3)) // WebView seeks the already ranged stream again.
            assertArrayEquals(byteArrayOf(3, 4, 5), response.data.use { it.readBytes() })
            assertEquals(mapOf("Referer" to ref, "Range" to "bytes=3-5", "Cookie" to "session=one",
                "Authorization" to "Bearer scoped", "If-None-Match" to ""), seen.single())
        } finally { server.stop(0) }
    }

    @Test fun crossOriginRedirectKeepsReferrerButDropsCookiesAndAuthorization() {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<List<String?>>()
        target.createContext("/final") { exchange ->
            seen += listOf("Cookie", "Authorization", "Referer").map { exchange.requestHeaders.getFirst(it) }
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(403, 1)
            exchange.responseBody.use { it.write(byteArrayOf(7)) }
        }
        source.createContext("/media") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/final")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        source.start(); target.start()
        try {
            val url = "http://127.0.0.1:${source.address.port}/media"
            val ref = "http://site.example/watch"
            val response = MediaRequestInterceptor.intercept(request(url, headers = mapOf("Cookie" to "secret=1",
                "Authorization" to "Bearer secret")), MediaNavigation.create(url, ref), null) { true }!!
            assertEquals(403, response.statusCode)
            assertArrayEquals(byteArrayOf(7), response.data.use { it.readBytes() })
            assertEquals(listOf(null, null, ref), seen.single())
            assertNull(MediaRequestInterceptor.intercept(request(url, main = true), MediaNavigation.create(url, ref), null) { true })
        } finally { source.stop(0); target.stop(0) }
    }

    @Test fun unrelatedRequestsStayWithWebViewAndExpiredPagesDoNotFetch() {
        val url = "http://127.0.0.1:1/video.mp4"
        val ref = "http://site.example/watch"
        val context = MediaNavigation.create(url, ref)
        assertNull(MediaRequestInterceptor.intercept(request(url + "?other", main = true), context, null) { true })
        assertNull(MediaRequestInterceptor.intercept(request(url + "?another"), context, null) { true })
        assertEquals(502, MediaRequestInterceptor.intercept(request(url), context, null) { false }!!.statusCode)
    }

    @Test fun rangesBeyondTheWebViewBridgeSizeLimitKeepNativeHandling() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Range", "bytes 3000000000-3000000002/4000000000")
            exchange.sendResponseHeaders(206, 3)
            exchange.responseBody.use { it.write(byteArrayOf(3, 4, 5)) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mp4"
            assertNull(MediaRequestInterceptor.intercept(request(url, headers = mapOf("Range" to "bytes=3000000000-")),
                MediaNavigation.create(url, "http://site.example/watch"), null) { true })
        } finally { server.stop(0) }
    }
}
