package com.mybrowser.download

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

class HttpDownloadEngineTest {

    private var server: HttpServer? = null
    private var tempDirectory: File? = null

    @After
    fun tearDown() {
        server?.stop(0)
        tempDirectory?.deleteRecursively()
    }

    @Test
    fun `range server is downloaded in validated parallel parts`() = runBlocking {
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

        assertEquals(3, payload.actualThreadCount)
        assertEquals(3, reportedThreads)
        assertEquals(body.size.toLong(), payload.totalBytes)
        assertArrayEquals(body, merged)
        assertEquals(4, seenRanges.size) // one-byte probe plus three file ranges
        assertTrue(seenCookies.all { it == "session=test-value" })
        assertTrue(seenReferers.all { it == "https://example.test/page" })
        assertTrue(seenIfRanges.isEmpty())
    }
}
