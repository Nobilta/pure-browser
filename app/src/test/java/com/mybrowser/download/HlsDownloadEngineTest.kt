package com.mybrowser.download

import android.app.Application
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HlsDownloadEngineTest {

    private lateinit var server: HttpServer
    private val routes = ConcurrentHashMap<String, ByteArray>()
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val requests = ConcurrentHashMap<String, Int>()
    private val cookies = ConcurrentHashMap<String, String>()
    private val redirects = ConcurrentHashMap<String, String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.merge(path, 1, Int::plus)
            cookies[path] = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = routes[path]
            val location = redirects[path]
            val failing = failures.remove(path) == true
            when {
                location != null -> {
                    exchange.responseHeaders.add("Location", location)
                    exchange.sendResponseHeaders(302, -1)
                }
                body == null -> exchange.sendResponseHeaders(404, -1)
                failing -> exchange.sendResponseHeaders(500, -1)
                else -> {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun directory() = Files.createTempDirectory("hls-test").toFile()

    @Test
    fun `external audio is refused before any video segment is saved`() = runBlocking {
        routes["/master.m3u8"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100000,AUDIO="aud"
            video.m3u8
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio.m3u8"
        """.trimIndent().toByteArray()
        val dir = directory()
        val failure = runCatching {
            HlsDownloadEngine(HttpDownloadEngine()).download(url("/master.m3u8"),
                DownloadRequestHeaders("test", null, null), 1, dir, { _, _, _ -> })
        }.exceptionOrNull()
        assertTrue(failure is UnsupportedHlsException)
        assertEquals(setOf("/master.m3u8"), requests.keys)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a byte range in an initialization map is refused before fetching the whole file`() = runBlocking {
        routes["/media.m3u8"] = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="4@0"
            #EXTINF:4,
            seg.m4s
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val failure = runCatching {
            HlsDownloadEngine(HttpDownloadEngine()).download(url("/media.m3u8"),
                DownloadRequestHeaders("test", null, null), 1, directory(), { _, _, _ -> })
        }.exceptionOrNull()
        assertTrue(failure is UnsupportedHlsException)
        assertEquals(setOf("/media.m3u8"), requests.keys)
    }

    @Test
    fun `bounded HTTP reads stop before writing beyond the limit and release their connections`() = runBlocking {
        routes["/large"] = ByteArray(256 * 1024)
        routes["/key"] = ByteArray(16) { it.toByte() }
        val http = HttpDownloadEngine()
        val headers = DownloadRequestHeaders("test", null, null)
        // More attempts than the per-host lease budget expose a leaked lease on the error path.
        repeat(5) {
            val sink = java.io.ByteArrayOutputStream()
            val failure = runCatching { http.fetchTo(url("/large"), headers, sink, maxBytes = 32) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(sink.size() <= 32)
        }
        val sink = java.io.ByteArrayOutputStream()
        http.fetchTo(url("/key"), headers, sink, maxBytes = 16)
        assertArrayEquals(routes["/key"], sink.toByteArray())
    }

    @Test
    fun `oversized playlists and keys fail without downloading segments`() = runBlocking {
        val headers = DownloadRequestHeaders("test", null, null)
        routes["/media.m3u8"] = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }
        val engine = HlsDownloadEngine(HttpDownloadEngine())
        assertTrue(runCatching {
            engine.download(url("/media.m3u8"), headers, 1, directory(), { _, _, _ -> })
        }.exceptionOrNull() is IOException)
        routes["/media.m3u8"] = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST\n".toByteArray()
        routes["/key"] = ByteArray(17)
        assertTrue(runCatching {
            engine.download(url("/media.m3u8"), headers, 1, directory(), { _, _, _ -> })
        }.exceptionOrNull() is IOException)
        assertEquals(null, requests["/seg.ts"])
    }

    @Test
    fun `every segment is appended into one file`() = runBlocking {
        val first = ByteArray(1_000) { it.toByte() }
        val second = ByteArray(1_500) { (it * 3).toByte() }
        routes["/one.ts"] = first
        routes["/two.ts"] = second
        routes["/media.m3u8"] = """
            #EXTM3U
            #EXTINF:4.0,
            one.ts
            #EXTINF:4.0,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val result = HlsDownloadEngine(HttpDownloadEngine()).download(
            url = url("/media.m3u8"),
            headers = DownloadRequestHeaders("test-agent", null, null),
            requestedThreads = 4,
            tempDirectory = directory(),
            onProgress = { _, _, _ -> },
        )

        assertArrayEquals(first + second, result.parts.single().readBytes())
        assertEquals((first.size + second.size).toLong(), result.totalBytes)
        assertEquals(1, result.actualThreadCount)
    }

    @Test
    fun `AES-128 segments are decrypted before they are appended`() = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16).also { it[15] = 1 }
        val first = "first plaintext".toByteArray()
        val second = "second plaintext, a little longer".toByteArray()
        routes["/key.bin"] = key
        routes["/e1.ts"] = encrypt(first, key, iv)
        routes["/e2.ts"] = encrypt(second, key, iv)
        routes["/enc.m3u8"] = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1
            #EXTINF:4.0,
            e1.ts
            #EXTINF:4.0,
            e2.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()

        val result = HlsDownloadEngine(HttpDownloadEngine()).download(
            url = url("/enc.m3u8"),
            headers = DownloadRequestHeaders("test-agent", null, null),
            requestedThreads = 1,
            tempDirectory = directory(),
            onProgress = { _, _, _ -> },
        )

        assertArrayEquals(first + second, result.parts.single().readBytes())
    }

    @Test
    fun `a segment on another origin never receives the playlist cookie`() = runBlocking {
        val second = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val elsewhere = ConcurrentHashMap<String, String>()
        second.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            elsewhere[path] = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = ByteArray(200) { 'b'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        second.start()
        try {
            val sameOrigin = ByteArray(100) { 'a'.code.toByte() }
            routes["/same.ts"] = sameOrigin
            routes["/media.m3u8"] = (
                "#EXTM3U\n#EXTINF:4.0,\nsame.ts\n#EXTINF:4.0,\n" +
                    "http://127.0.0.1:${second.address.port}/elsewhere.ts\n#EXT-X-ENDLIST\n"
                ).toByteArray()

            val result = HlsDownloadEngine(HttpDownloadEngine()).download(
                url = url("/media.m3u8"),
                headers = DownloadRequestHeaders("test-agent", "SESSION=secret", null),
                requestedThreads = 1,
                tempDirectory = directory(),
                onProgress = { _, _, _ -> },
            )

            assertEquals(300, result.parts.single().length())
            // The playlist and a segment on the same origin carry the cookie the task supplied…
            assertEquals("SESSION=secret", cookies["/media.m3u8"])
            assertEquals("SESSION=secret", cookies["/same.ts"])
            // …and the one on another origin does not, because the cookie belongs to this origin.
            assertEquals("", elsewhere["/elsewhere.ts"])
        } finally {
            second.stop(0)
        }
    }

    @Test
    fun `a redirected playlist resolves relative segments against where it was served`() = runBlocking {
        val first = ByteArray(320) { 'r'.code.toByte() }
        routes["/cdn/one.ts"] = first
        routes["/cdn/index.m3u8"] = "#EXTM3U\n#EXTINF:4.0,\none.ts\n#EXT-X-ENDLIST\n".toByteArray()
        redirects["/live.m3u8"] = "/cdn/index.m3u8"

        val result = HlsDownloadEngine(HttpDownloadEngine()).download(
            url = url("/live.m3u8"),
            headers = DownloadRequestHeaders("test-agent", null, null),
            requestedThreads = 1,
            tempDirectory = directory(),
            onProgress = { _, _, _ -> },
        )

        assertArrayEquals(first, result.parts.single().readBytes())
        // "one.ts" is next to /cdn/index.m3u8, not next to the address that redirected there.
        assertEquals(null, requests["/one.ts"])
        assertEquals(1, requests["/cdn/one.ts"])
    }

    @Test
    fun `a handed-over playlist is merged without fetching it again`() = runBlocking {
        val first = ByteArray(500) { 'c'.code.toByte() }
        val second = ByteArray(700) { 'd'.code.toByte() }
        routes["/one.ts"] = first
        routes["/two.ts"] = second
        // The playlists themselves are unreachable: only the text that came with the response can
        // drive the merge, which is exactly the one-shot case.
        val directory = directory()

        val result = HlsDownloadEngine(HttpDownloadEngine()).downloadHandedOver(
            url = url("/handed.m3u8"),
            headers = DownloadRequestHeaders("test-agent", null, null),
            tempDirectory = directory,
            onProgress = { _, _, _ -> },
            playlistText = "#EXTM3U\n#EXTINF:4.0,\none.ts\n#EXTINF:4.0,\ntwo.ts\n#EXT-X-ENDLIST\n",
        )

        assertArrayEquals(first + second, result.parts.single().readBytes())
        assertEquals(0, requests["/handed.m3u8"] ?: 0)
        // No staged segment survives the transfer.
        assertTrue(directory.listFiles()!!.none { it.name == "hls.segment" })
    }

    @Test
    fun `a failed segment keeps the completed ones and the next attempt continues`() = runBlocking {
        val first = ByteArray(1_024) { 'a'.code.toByte() }
        val second = ByteArray(2_048) { 'b'.code.toByte() }
        routes["/one.ts"] = first
        routes["/two.ts"] = second
        routes["/resume.m3u8"] = """
            #EXTM3U
            #EXTINF:4.0,
            one.ts
            #EXTINF:4.0,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        failures += "/two.ts"
        val directory = directory()
        val engine = HlsDownloadEngine(HttpDownloadEngine())

        val firstAttempt = runCatching {
            engine.download(url("/resume.m3u8"), DownloadRequestHeaders("test-agent", null, null), 1, directory) { _, _, _ -> }
        }

        assertTrue(firstAttempt.exceptionOrNull() is IOException)
        assertEquals(1, requests["/one.ts"] ?: 0)

        val result = engine.download(
            url = url("/resume.m3u8"),
            headers = DownloadRequestHeaders("test-agent", null, null),
            requestedThreads = 1,
            tempDirectory = directory,
            onProgress = { _, _, _ -> },
        )

        assertArrayEquals(first + second, result.parts.single().readBytes())
        // The first segment was already on disk; only the failed one was fetched again.
        assertEquals(1, requests["/one.ts"] ?: 0)
    }

    @Test
    fun `maps keep their own encryption across key rotation and clear transitions`() = runBlocking {
        val init = "initialization bytes".toByteArray()
        val segment = "media segment bytes".toByteArray()
        val keyA = ByteArray(16) { it.toByte() }
        val keyB = ByteArray(16) { (it + 20).toByte() }
        val ivA = HlsPlaylist.defaultIv(1)
        val ivB = HlsPlaylist.defaultIv(2)
        routes["/a.key"] = keyA
        routes["/b.key"] = keyB
        for ((encryptedInit, encryptedMedia) in listOf(true to true, true to false, false to true)) {
            routes["/init.mp4"] = if (encryptedInit) encrypt(init, keyA, ivA) else init
            routes["/segment.m4s"] = if (encryptedMedia) encrypt(segment, keyB, ivB) else segment
            val before = if (encryptedInit) "#EXT-X-KEY:METHOD=AES-128,URI=\"a.key\",IV=0x1" else "#EXT-X-KEY:METHOD=NONE"
            val after = if (encryptedMedia) "#EXT-X-KEY:METHOD=AES-128,URI=\"b.key\",IV=0x2" else "#EXT-X-KEY:METHOD=NONE"
            routes["/map.m3u8"] = "#EXTM3U\n$before\n#EXT-X-MAP:URI=\"init.mp4\"\n$after\n#EXTINF:4,\nsegment.m4s\n#EXT-X-ENDLIST\n".toByteArray()
            val dir = directory()
            try {
                val result = HlsDownloadEngine(HttpDownloadEngine()).download(url("/map.m3u8"),
                    DownloadRequestHeaders("test", null, null), 1, dir, { _, _, _ -> })
                assertArrayEquals(init + segment, result.parts.single().readBytes())
            } finally { dir.deleteRecursively() }
        }
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }
}
