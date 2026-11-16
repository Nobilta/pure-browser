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

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.merge(path, 1, Int::plus)
            val body = routes[path]
            val failing = failures.remove(path) == true
            when {
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
        val iv = ByteArray(16) { (0x40 + it).toByte() }
        val first = "first plaintext".toByteArray()
        val second = "second plaintext, a little longer".toByteArray()
        routes["/key.bin"] = key
        routes["/e1.ts"] = encrypt(first, key, iv)
        routes["/e2.ts"] = encrypt(second, key, iv)
        routes["/enc.m3u8"] = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x${iv.joinToString("") { "%02x".format(it) }}
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

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }
}
