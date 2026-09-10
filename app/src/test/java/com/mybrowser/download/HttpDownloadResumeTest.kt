package com.mybrowser.download

import android.app.Application
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HttpDownloadResumeTest {
    private val body = ByteArray(3 * 1024 * 1024 + 137) { (it % 251).toByte() }
    private val requests = ConcurrentLinkedQueue<Pair<String?, String?>>()
    private var server: HttpServer? = null
    private val directory = Files.createTempDirectory("pure-resume").toFile()
    private val headers = DownloadRequestHeaders("Pure/Test", null, null)

    @After fun cleanup() { server?.stop(0); directory.deleteRecursively() }

    private fun start(etag: String = "\"stable\"", ignoreResume: Boolean = false, encoding: String? = null): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/file") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                val validator = exchange.requestHeaders.getFirst("If-Range")
                requests += range to validator
                exchange.responseHeaders.add("ETag", etag)
                encoding?.let { exchange.responseHeaders.add("Content-Encoding", it) }
                val useRange = range != null && !(ignoreResume && validator != null)
                val (first, last) = if (useRange) range!!.removePrefix("bytes=").split('-').map { it.toInt() }
                    else listOf(0, body.lastIndex)
                if (useRange) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${body.size}")
                exchange.sendResponseHeaders(if (useRange) 206 else 200, (last - first + 1).toLong())
                runCatching { exchange.responseBody.use { it.write(body, first, last - first + 1) } }
            }
            start()
        }
        return "http://127.0.0.1:${server!!.address.port}/file"
    }

    private fun merged(payload: DownloadPayload): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        payload.parts.forEach { output.write(it.readBytes()) }; output.toByteArray()
    }

    @Test fun cancellationKeepsValidatedPrefixAndNewEngineResumesOnlyMissingBytes() = runBlocking {
        val url = start()
        try {
            HttpDownloadEngine().download(url, headers, 1, directory) { bytes, _, _ ->
                if (bytes > 0) throw CancellationException("User paused")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        val prefix = File(directory, "part-0").length()
        assertTrue(prefix in 1 until body.size.toLong())
        assertNotNull(DownloadCheckpoint.read(directory, url))
        requests.clear()
        val payload = HttpDownloadEngine().download(url, headers, 8, directory) { _, _, _ -> }
        assertArrayEquals(body, merged(payload))
        assertEquals(listOf("bytes=0-0", "bytes=$prefix-${body.lastIndex}"), requests.map { it.first })
        assertEquals("\"stable\"", requests.last().second)
        assertEquals(1, payload.actualThreadCount)
    }

    @Test fun processRecoverySkipsCompletedSegmentsAndContinuesPartialSegments() = runBlocking {
        val url = start()
        val ranges = DownloadRanges.split(body.size.toLong(), 3)
        DownloadCheckpoint(url, body.size.toLong(), "\"stable\"", 3).save(directory)
        File(directory, "part-0").writeBytes(body.copyOfRange(0, ranges[0].last.toInt() + 1))
        File(directory, "part-1").writeBytes(body.copyOfRange(ranges[1].first.toInt(), ranges[1].first.toInt() + 777))
        val payload = HttpDownloadEngine().download(url, headers, 16, directory) { _, _, _ -> }
        assertEquals(3, payload.actualThreadCount)
        assertArrayEquals(body, merged(payload))
        assertEquals(setOf("bytes=0-0", "bytes=${ranges[1].first + 777}-${ranges[1].last}",
            "bytes=${ranges[2].first}-${ranges[2].last}"), requests.map { it.first }.toSet())
        assertTrue(requests.filter { it.first != "bytes=0-0" }.all { it.second == "\"stable\"" })
    }

    @Test fun changedEntityDiscardsEveryOldByte() = runBlocking {
        val url = start(etag = "\"new\"")
        DownloadCheckpoint(url, body.size.toLong(), "\"old\"", 1).save(directory)
        File(directory, "part-0").writeBytes(ByteArray(10000) { 99 })
        val payload = HttpDownloadEngine().download(url, headers, 1, directory) { _, _, _ -> }
        assertArrayEquals(body, merged(payload))
        assertEquals(listOf("bytes=0-0", null), requests.map { it.first })
    }

    @Test fun serverIgnoringIfRangeRestartsWithoutAppendingAFullBody() = runBlocking {
        val url = start(ignoreResume = true)
        DownloadCheckpoint(url, body.size.toLong(), "\"stable\"", 1).save(directory)
        File(directory, "part-0").writeBytes(body.copyOfRange(0, 7000))
        val payload = HttpDownloadEngine().download(url, headers, 1, directory) { _, _, _ -> }
        assertArrayEquals(body, merged(payload))
        assertEquals(listOf("bytes=0-0", "bytes=7000-${body.lastIndex}", null), requests.map { it.first })
    }

    @Test fun failedRevalidationKeepsPartialDataForAnotherRetry() = runBlocking {
        val url = start()
        DownloadCheckpoint(url, body.size.toLong(), "\"stable\"", 1).save(directory)
        val part = File(directory, "part-0").apply { writeBytes(body.copyOfRange(0, 1234)) }
        server!!.stop(0)
        try { HttpDownloadEngine().download(url, headers, 1, directory) { _, _, _ -> }; fail() }
        catch (_: java.io.IOException) { }
        assertEquals(1234L, part.length())
        assertNotNull(DownloadCheckpoint.read(directory, url))
    }

    @Test fun encodedResponsesCannotBeSavedAsTheRequestedFile() = runBlocking {
        val url = start(encoding = "gzip")
        try { HttpDownloadEngine().download(url, headers, 1, directory) { _, _, _ -> }; fail() }
        catch (_: java.io.IOException) { }
        assertFalse(File(directory, "part-0").exists())
    }

    @Test fun corruptedCheckpointCannotResumeAnotherUrlOrAnOversizedSegment() {
        DownloadCheckpoint("https://example.com/a", 100, "\"stable\"", 1).save(directory)
        assertNull(DownloadCheckpoint.read(directory, "https://other.example/a"))
        File(directory, "part-0").writeBytes(ByteArray(101))
        assertNull(DownloadCheckpoint.read(directory, "https://example.com/a"))
    }
}
