package com.mybrowser.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress

class TextDownloaderTest {
    @Test fun urlValidationRejectsCredentialsSchemesWhitespaceAndFragments() {
        assertTrue(TextDownloader.isHttpUrl("https://example.com/list.txt?key=abc"))
        assertTrue(TextDownloader.isHttpUrl("http://127.0.0.1:8875/list.txt"))
        for (url in listOf("file:///tmp/list", "https://user:pass@example.com/list", "https://example.com/a b", "https://example.com/list#fragment")) {
            assertFalse(url, TextDownloader.isHttpUrl(url))
        }
    }

    @Test fun boundedUtf8ReadRejectsOversizeAndInvalidEncoding() {
        assertEquals("hello", TextDownloader.readText(ByteArrayInputStream("hello".toByteArray()), 5))
        assertThrows(IOException::class.java) { TextDownloader.readText(ByteArrayInputStream(ByteArray(6)), 5) }
        assertTrue(runCatching { TextDownloader.readText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), 8) }.isFailure)
    }

    @Test fun redirectsAndValidatorsWorkWithoutDownloadingErrorPages() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:" + server.address.port
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/rules")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/rules") { exchange ->
            if (exchange.requestHeaders.getFirst("If-None-Match") == "\"v1\"") exchange.sendResponseHeaders(304, -1)
            else {
                val body = "||ads.example.com^".toByteArray()
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.createContext("/error") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val downloaded = TextDownloader().get(base + "/redirect", 1024)
            assertEquals("||ads.example.com^", downloaded.text)
            assertEquals("\"v1\"", downloaded.etag)
            assertNull(TextDownloader().get(base + "/rules", 1024, "\"v1\"").text)
            assertTrue(runCatching { TextDownloader().get(base + "/error", 1024) }.isFailure)
            assertTrue(runCatching { TextDownloader().get(base + "/loop", 1024) }.isFailure)
        } finally { server.stop(0) }
    }
}
