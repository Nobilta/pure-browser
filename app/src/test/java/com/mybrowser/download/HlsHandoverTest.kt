package com.mybrowser.download

import android.app.Application
import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A playlist that arrives as an already opened response, paused mid-transfer.
 *
 * The response can only be read once, so the playlist text has to survive the pause for the task
 * to continue; the segments it names are ordinary requests and remain fetchable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HlsHandoverTest {

    private lateinit var server: HttpServer
    private lateinit var handler: DownloadHandler
    private val requests = ConcurrentHashMap<String, Int>()

    @Before
    fun setUp() {
        // A slow segment keeps the transfer running long enough to pause it deterministically.
        val chunk = ByteArray(64 * 1024) { 's'.code.toByte() }
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            requests.merge(exchange.requestURI.path, 1, Int::plus)
            val path = exchange.requestURI.path
            if (!path.endsWith(".ts")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(200, (chunk.size * 40).toLong())
            exchange.responseBody.use { body ->
                repeat(40) {
                    body.write(chunk)
                    body.flush()
                    Thread.sleep(20)
                }
            }
            exchange.close()
        }
        server.start()
        handler = DownloadHandler(connectedContext())
    }

    @After
    fun tearDown() {
        handler.close()
        server.stop(0)
    }

    private fun connectedContext(): Context {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadow.setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        return context
    }

    @Test
    fun `a paused handed-over playlist keeps its text for the resume`() = runBlocking {
        val base = "http://127.0.0.1:${server.address.port}"
        val playlist = "#EXTM3U\n#EXTINF:4.0,\none.ts\n#EXTINF:4.0,\ntwo.ts\n#EXT-X-ENDLIST\n"
        val started = handler.enqueueOrGetExisting(
            url = "$base/live?token=1",   // no .m3u8 suffix: the MIME identifies the playlist
            userAgent = null,
            contentDisposition = null,
            mimeType = "application/vnd.apple.mpegurl",
            response = DownloadResponse(ByteArrayInputStream(playlist.toByteArray()), playlist.length.toLong()),
        ) as DownloadHandler.EnqueueOutcome.Started

        withTimeout(10_000) {
            handler.downloads.first { list ->
                list.any { it.id == started.id && it.bytesDownloaded > 0 }
            }
        }
        handler.pause(started.id)
        delay(300)

        val paused = handler.downloads.value.single { it.id == started.id }
        // The playlist was kept on disk, so continuing needs no new request for the one-shot body.
        assertEquals(false, paused.requiresNewRequest)
        val temporary = File(
            File(RuntimeEnvironment.getApplication().noBackupFilesDir, DownloadHandler.TEMP_DIRECTORY_NAME),
            started.id.toString(),
        )
        assertTrue(File(temporary, HlsDownloadEngine.PLAYLIST_NAME).isFile)
        // The playlist address itself was never requested: the response was the only copy.
        assertFalse(requests.containsKey("/live"))
    }
}
