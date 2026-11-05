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
    @Test fun attachmentHandsItsOnlyResponseToTheDownloaderAndOutlivesThePage() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        val expected = ByteArray(90_001) { (it % 251).toByte() }
        server.createContext("/once.mp4") { e ->
            requests++
            e.responseHeaders.add("Content-Type", "video/mp4")
            e.responseHeaders.add("Content-Disposition", "attachment; filename=once.mp4")
            e.sendResponseHeaders(if (requests == 1) 200 else 403, expected.size.toLong())
            e.responseBody.use { it.write(expected) }
        }
        server.start()
        val directory = java.nio.file.Files.createTempDirectory("media-download").toFile()
        try {
            val url = "http://127.0.0.1:${server.address.port}/once.mp4"
            var active = true
            var download: com.mybrowser.download.DownloadResponse? = null
            val intercepted = MediaRequestInterceptor.intercept(request(url, main = true), MediaNavigation.create(url, url), null,
                onDownloadResponse = { _, response ->
                    download = com.mybrowser.download.DownloadResponse(response.data, expected.size.toLong())
                    true
                }) { active }
            assertEquals(204, intercepted!!.statusCode)
            active = false
            val payload = com.mybrowser.download.HttpDownloadEngine().downloadResponse(download!!, directory) { _, _, _ -> }
            assertArrayEquals(expected, payload.parts.single().readBytes())
            assertEquals(1, requests)
        } finally { directory.deleteRecursively(); server.stop(0) }
    }

    @Test fun successfulFallbackTypesUseTheOriginalBodyExactlyOnce() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val expected = "Single use response".toByteArray()
        server.createContext("/") { e ->
            val path = e.requestURI.path
            val count = counts.merge(path, 1, Int::plus)!!
            val encoded = path == "/gzip"
            val bytes = if (encoded) java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(expected) }
            }.toByteArray() else expected
            e.responseHeaders.add("Content-Type", if (path == "/html") "text/html" else "application/octet-stream")
            if (encoded) e.responseHeaders.add("Content-Encoding", "gzip")
            if (path == "/attachment") e.responseHeaders.add("Content-Disposition", "attachment; filename=one.mp4")
            e.sendResponseHeaders(if (count == 1) 200 else 403, if (path == "/chunked") 0 else bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            for (path in listOf("/html", "/attachment", "/chunked", "/gzip")) {
                val url = "http://127.0.0.1:${server.address.port}$path"
                val response = MediaRequestInterceptor.intercept(request(url, main = true), MediaNavigation.create(url, url), null) { true }!!
                assertEquals(200, response.statusCode)
                assertArrayEquals(expected, response.data.use { it.readBytes() })
                assertEquals(1, counts[path])
            }
        } finally { server.stop(0) }
    }

    @Test fun authChallengesLeaveTheWholeLoadAndItsReloadWithChromium() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/auth.mp4") { e ->
            requests++
            e.responseHeaders.add("WWW-Authenticate", "Basic realm=media")
            e.sendResponseHeaders(401, -1); e.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/auth.mp4"
            val navigation = MediaNavigation.create(url, url)!!
            val failures = mutableListOf<MediaRequestInterceptor.Failure>()
            assertNull(MediaRequestInterceptor.intercept(request(url, main = true), navigation, null, onFailure = failures::add) { true })
            assertTrue(navigation.loadId.nativeRequests)
            assertNull(MediaRequestInterceptor.intercept(request(url), navigation, null) { true })
            assertNull(MediaRequestInterceptor.intercept(request(url, main = true), navigation.forLoad(), null) { true })
            assertTrue(failures.isEmpty())
            assertEquals(1, requests)
        } finally { server.stop(0) }
    }

    @Test fun sameUrlRangeChallengeAuthenticatesAsASubresource() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4") { e ->
            if (e.requestHeaders.getFirst("Authorization") == "Basic dGVzdDpwdXJl") {
                e.responseHeaders.add("Content-Type", "video/mp4")
                e.responseHeaders.add("Content-Range", "bytes 0-0/1")
                e.sendResponseHeaders(206, 1); e.responseBody.use { it.write(7) }
            } else {
                e.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Video\"")
                e.sendResponseHeaders(401, -1); e.close()
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mp4"
            val navigation = MediaNavigation.create(url, url)!!
            val range = request(url, main = false, headers = mapOf("Range" to "bytes=0-"))
            assertNull(MediaRequestInterceptor.intercept(range, navigation, null) { true })
            assertEquals(MediaRequestAccess.AuthenticatedRequest.SUBRESOURCE,
                navigation.loadId.access.authenticate("127.0.0.1", "Video", "test", "pure"))
            navigation.loadId.nativeRequests = false
            val response = MediaRequestInterceptor.intercept(range, navigation, null) { true }!!
            assertEquals(206, response.statusCode)
            response.data.use { assertEquals(7, it.read()) }
        } finally { server.stop(0) }
    }

    @Test fun redirectedLoginHandsOnlyTheChallengedUrlAndItsReducedPolicyToChromium() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<List<String?>>()
        server.createContext("/start.mp4") { e ->
            e.responseHeaders.add("Location", "/auth.mp4")
            e.responseHeaders.add("Referrer-Policy", "no-referrer")
            e.sendResponseHeaders(302, -1); e.close()
        }
        server.createContext("/auth.mp4") { e ->
            val auth = e.requestHeaders.getFirst("Authorization")
            seen += listOf(auth, e.requestHeaders.getFirst("Referer"))
            if (auth == "Basic dGVzdDpwdXJl") {
                e.responseHeaders.add("Content-Type", "video/mp4")
                e.sendResponseHeaders(200, 1); e.responseBody.use { it.write(1) }
            } else {
                e.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Video\"")
                e.sendResponseHeaders(401, -1); e.close()
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/start.mp4"
            val original = MediaNavigation.create(url, "http://site.test/watch")!!
            var redirected: MediaNavigation? = null
            val response = MediaRequestInterceptor.intercept(request(url, main = true), original, null,
                onNativeNavigation = { redirected = it; true }) { true }!!
            assertEquals(204, response.statusCode)
            val selected = requireNotNull(redirected)
            assertTrue(selected.url.endsWith("/auth.mp4"))
            assertEquals("", selected.referer)
            assertSame(original.loadId, selected.loadId)
            assertEquals(MediaRequestAccess.AuthenticatedRequest.DOCUMENT, selected.loadId.access.authenticate("127.0.0.1", "Video", "test", "pure"))
            selected.loadId.nativeRequests = false
            MediaRequestInterceptor.intercept(request(selected.url, headers = mapOf("Range" to "bytes=0-")), selected, null) { true }!!.data.use {
                assertEquals(1, it.read())
            }
            assertEquals(listOf(listOf(null, null), listOf("Basic dGVzdDpwdXJl", null)), seen)
        } finally { server.stop(0) }
    }

    @Test fun redirectedHtmlTransfersTheFetchedResponseAtItsRealOriginAndRetainsItsPolicy() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4") { exchange ->
            exchange.responseHeaders.add("Location", "/reduced")
            exchange.responseHeaders.add("Referrer-Policy", "origin")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/reduced") { exchange ->
            exchange.responseHeaders.add("Location", "/login")
            exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/login") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 1); exchange.responseBody.use { it.write(7) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val source = "$base/watch?private=secret"
            val navigation = MediaNavigation.create("$base/media.mp4", source, source)!!
            var destination: MediaNavigation? = null
            var fetched: android.webkit.WebResourceResponse? = null
            val response = MediaRequestInterceptor.intercept(request(navigation.url, main = true), navigation, null,
                onRedirectResponse = { next, body -> destination = next; fetched = body; true }) { true }!!
            assertEquals(204, response.statusCode)
            val login = destination!!
            assertEquals("$base/login", login.url)
            assertEquals("", login.referer)
            assertEquals("", login.downloadRefererFor(login.url))
            assertEquals("", login.forLoad().referer)
            assertSame(navigation.loadId, login.loadId)
            assertEquals("text/html", fetched!!.mimeType)
            assertArrayEquals(byteArrayOf(7), fetched!!.data.use { it.readBytes() })
        } finally { server.stop(0) }
    }

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
                "Authorization" to "Bearer scoped", "If-None-Match" to "stale-cache")), MediaNavigation.create(url, ref, ref), null) { true }!!
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
                "Authorization" to "Bearer secret")), MediaNavigation.create(url, ref, ref), null) { true }!!
            assertEquals(403, response.statusCode)
            assertTrue(response.data.use { it.readBytes() }.isNotEmpty())
            assertEquals(listOf(null, null, ref), seen.single())
            assertEquals(403, MediaRequestInterceptor.intercept(request(url, main = true), MediaNavigation.create(url, ref, ref), null) { true }!!.statusCode)
        } finally { source.stop(0); target.stop(0) }
    }

    @Test fun unrelatedRequestsStayWithWebViewAndExpiredPagesDoNotFetch() {
        val url = "http://127.0.0.1:1/video.mp4"
        val ref = "http://site.example/watch"
        val context = MediaNavigation.create(url, ref, ref)
        assertNull(MediaRequestInterceptor.intercept(request(url + "?other", main = true), context, null) { true })
        assertNull(MediaRequestInterceptor.intercept(request(url + "?another"), context, null) { true })
        val failures = mutableListOf<MediaRequestInterceptor.Failure>()
        assertEquals(499, MediaRequestInterceptor.intercept(request(url), context, null, onFailure = failures::add) { false }!!.statusCode)
        assertTrue(failures.isEmpty())
        assertEquals(502, MediaRequestInterceptor.intercept(request(url), context, null, onFailure = failures::add) { true }!!.statusCode)
        assertTrue("A failed subresource must not replace the document", failures.isEmpty())
        assertEquals(502, MediaRequestInterceptor.intercept(request(url, main = true), context, null, onFailure = failures::add) { true }!!.statusCode)
        assertEquals(MediaRequestInterceptor.FailureKind.NETWORK, failures.single().kind)
    }

    @Test fun failedRangesAndTruncatedSubresourcesDoNotReportDocumentFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/denied.mp4") { e -> e.sendResponseHeaders(403, -1); e.close() }
        server.createContext("/short.mp4") { e ->
            e.responseHeaders.add("Content-Type", "video/mp4")
            e.sendResponseHeaders(200, 100)
            runCatching { e.responseBody.use { it.write(byteArrayOf(1, 2)) } }
            e.close()
        }
        server.start()
        try {
            for (path in listOf("denied.mp4", "short.mp4")) for (main in listOf(false, true)) {
                val url = "http://127.0.0.1:${server.address.port}/$path"
                val failures = mutableListOf<MediaRequestInterceptor.Failure>()
                val response = MediaRequestInterceptor.intercept(request(url, main = main, headers = mapOf("Range" to "bytes=0-")),
                    MediaNavigation.create(url, url), null, onFailure = failures::add) { true }!!
                runCatching { response.data.use { it.readBytes() } }
                assertEquals("$path, main=$main", if (main) 1 else 0, failures.size)
            }
        } finally { server.stop(0) }
    }

    @Test fun sameOriginRedirectReadsTheUpdatedCookieInsteadOfReplayingTheOriginalHeader() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<String>()
        server.createContext("/start.mp4") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "session=new; Path=/")
            exchange.responseHeaders.add("Location", "/final.mp4")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/final.mp4") { exchange ->
            seen += exchange.requestHeaders.getFirst("Cookie").orEmpty()
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, 1); exchange.responseBody.use { it.write(7) }
        }
        server.start()
        val cookies = android.webkit.CookieManager.getInstance()
        try {
            val url = "http://127.0.0.1:${server.address.port}/start.mp4"
            cookies.setCookie(url, "session=old; Path=/")
            val response = MediaRequestInterceptor.intercept(request(url, main = true, headers = mapOf("Cookie" to "session=old")),
                MediaNavigation.create(url, url), cookies) { true }!!
            response.data.close()
            assertTrue(seen.single().contains("session=new"))
            assertFalse(seen.single().contains("session=old"))
        } finally { cookies.removeAllCookies(null); server.stop(0) }
    }

    @Test fun closingTheResponseDoesNotReportALateReadAsANetworkFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mp4"
            val failures = mutableListOf<MediaRequestInterceptor.Failure>()
            val response = MediaRequestInterceptor.intercept(request(url), MediaNavigation.create(url, url), null,
                onFailure = failures::add) { true }!!
            response.data.close()
            assertThrows(java.io.IOException::class.java) { response.data.read() }
            assertTrue(failures.isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun rangesBeyondInt32UseUnknownAvailableWithoutRepeatingTheGet() {
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
            val response = MediaRequestInterceptor.intercept(request(url, headers = mapOf("Range" to "bytes=3000000000-")),
                MediaNavigation.create(url, "http://site.example/watch"), null) { true }!!
            assertEquals(0, response.data.available())
            var left = 3000000000L
            while (left > 0) {
                // The native bridge in WebView 113 stores each skip result in an int.
                val skipped = response.data.skip(left).toInt()
                assertTrue(skipped > 0)
                left -= skipped
            }
            assertArrayEquals(byteArrayOf(3, 4, 5), response.data.use { it.readBytes() })
        } finally { server.stop(0) }
    }
}
