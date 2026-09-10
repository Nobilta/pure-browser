package com.mybrowser.download

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

class HttpDownloadEngineTest {

    private var server: HttpServer? = null
    private var redirectTarget: HttpServer? = null
    private var tempDirectory: File? = null

    @After
    fun tearDown() {
        server?.stop(0)
        redirectTarget?.stop(0)
        tempDirectory?.deleteRecursively()
    }

    @Test
    fun `range server without a valid entity validator uses one stream`() = runBlocking {
        val body = ByteArray(3 * 1024 * 1024 + 137) { index -> (index % 251).toByte() }
        val seenRanges = ConcurrentLinkedQueue<String>()
        val seenCookies = ConcurrentLinkedQueue<String>()
        val seenReferers = ConcurrentLinkedQueue<String>()
        val seenIfRanges = ConcurrentLinkedQueue<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/payload") { exchange ->
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                val cookieHeader = exchange.requestHeaders.getFirst("Cookie")
                val refererHeader = exchange.requestHeaders.getFirst("Referer")
                val ifRangeHeader = exchange.requestHeaders.getFirst("If-Range")
                if (cookieHeader != null) seenCookies += cookieHeader
                if (refererHeader != null) seenReferers += refererHeader
                if (ifRangeHeader != null) seenIfRanges += ifRangeHeader
                // Deliberately malformed: a bare ETag is not a valid RFC entity tag and
                // must not be echoed into If-Range (some servers turn that into HTTP 304).
                exchange.responseHeaders.add("ETag", "bare-etag")
                if (rangeHeader == null) {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    seenRanges += rangeHeader
                    val bounds = rangeHeader.removePrefix("bytes=").split('-', limit = 2)
                    val start = bounds[0].toInt()
                    val end = bounds[1].toInt()
                    val response = body.copyOfRange(start, end + 1)
                    exchange.responseHeaders.add(
                        "Content-Range",
                        "bytes $start-$end/${body.size}",
                    )
                    exchange.sendResponseHeaders(206, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            start()
        }
        tempDirectory = Files.createTempDirectory("pure-browser-download-test").toFile()
        var reportedThreads = 0

        val payload = HttpDownloadEngine().download(
            url = "http://127.0.0.1:${server!!.address.port}/payload",
            headers = DownloadRequestHeaders(
                userAgent = "PureBrowser/Test",
                cookie = "session=test-value",
                referer = "https://example.test/page",
            ),
            requestedThreads = 8,
            tempDirectory = tempDirectory!!,
            onProgress = { _, _, actualThreads -> reportedThreads = actualThreads },
        )
        val merged = ByteArrayOutputStream().use { output ->
            payload.parts.forEach { it.inputStream().use { input -> input.copyTo(output) } }
            output.toByteArray()
        }

        assertEquals(1, payload.actualThreadCount)
        assertEquals(1, reportedThreads)
        assertEquals(body.size.toLong(), payload.totalBytes)
        assertArrayEquals(body, merged)
        assertEquals(listOf("bytes=0-0"), seenRanges.toList())
        assertTrue(seenCookies.all { it == "session=test-value" })
        assertTrue(seenReferers.all { it == "https://example.test/page" })
        assertTrue(seenIfRanges.isEmpty())
    }

    @Test
    fun `same origin redirects preserve cookies and range headers`() = verifyRedirect(false)

    @Test
    fun `cross origin redirects drop source cookies for probes and file ranges`() = verifyRedirect(true)

    private fun verifyRedirect(crossOrigin: Boolean) = runBlocking {
        val body = ByteArray(3 * 1024 * 1024 + 17) { (it % 193).toByte() }
        val targetCookies = ConcurrentLinkedQueue<String>()
        val sourceCookies = ConcurrentLinkedQueue<String>()
        val ranges = ConcurrentLinkedQueue<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val target = if (crossOrigin) {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { redirectTarget = it }
        } else server!!
        target.createContext("/payload") { exchange ->
            exchange.requestHeaders.getFirst("Cookie")?.let(targetCookies::add)
            val range = exchange.requestHeaders.getFirst("Range")
            val response = if (range == null) body else {
                ranges.add(range)
                val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                body.copyOfRange(start, end + 1)
            }
            exchange.responseHeaders.add("ETag", "\"unchanged\"")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server!!.createContext("/redirect") { exchange ->
            exchange.requestHeaders.getFirst("Cookie")?.let(sourceCookies::add)
            exchange.responseHeaders.add("Location", if (crossOrigin)
                "http://127.0.0.1:${target.address.port}/payload" else "/payload")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server!!.start()
        redirectTarget?.start()
        tempDirectory = Files.createTempDirectory("pure-browser-redirect-test").toFile()
        for (threads in listOf(1, 4)) {
            val payload = HttpDownloadEngine().download(
                "http://127.0.0.1:${server!!.address.port}/redirect",
                DownloadRequestHeaders("PureBrowser/Test", "session=private", null), threads,
                File(tempDirectory, threads.toString()), { _, _, _ -> },
            )
            val merged = ByteArrayOutputStream().use { output ->
                payload.parts.forEach { output.write(it.readBytes()) }
                output.toByteArray()
            }
            assertArrayEquals(body, merged)
            assertEquals(if (threads == 1) 1 else 3, payload.actualThreadCount)
        }
        assertTrue(sourceCookies.isNotEmpty())
        assertTrue(sourceCookies.all { it == "session=private" })
        if (crossOrigin) assertTrue(targetCookies.isEmpty())
        else assertEquals(sourceCookies.size, targetCookies.size)
        assertEquals(4, ranges.size)
    }

    @Test
    fun `unsolicited partial responses cannot become successful full downloads`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/partial") { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 0-2/100")
                exchange.sendResponseHeaders(206, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            start()
        }
        tempDirectory = Files.createTempDirectory("pure-browser-partial-test").toFile()
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                HttpDownloadEngine().download("http://127.0.0.1:${server!!.address.port}/partial",
                    DownloadRequestHeaders("", null, null), 1, tempDirectory!!, { _, _, _ -> })
            }
        }
        assertTrue(tempDirectory!!.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `redirect loops stop within the redirect budget`() {
        var requests = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/loop") { exchange ->
                requests++
                exchange.responseHeaders.add("Location", "/loop")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        tempDirectory = Files.createTempDirectory("pure-browser-loop-test").toFile()
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                HttpDownloadEngine().download("http://127.0.0.1:${server!!.address.port}/loop",
                    DownloadRequestHeaders("", null, null), 1, tempDirectory!!, { _, _, _ -> })
            }
        }
        assertEquals(6, requests)
    }
}
