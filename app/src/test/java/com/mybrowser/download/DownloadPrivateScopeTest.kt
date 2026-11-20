package com.mybrowser.download

import android.app.Application
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadPrivateScopeTest {
    @Test fun endingTheSessionLetsAnAcceptedResponseFinishAndKeepsItsRecord() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(
            org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI, 0, true, true,
            ),
        )
        shadowOf(connectivity).setNetworkCapabilities(
            connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            },
        )
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val payload = ByteArray(2048) { 7 }
        val response = DownloadResponse(object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(proceed.await(5, TimeUnit.SECONDS)) { "Test did not release the response" }
                return super.read(buffer, offset, length)
            }

            override fun close() {
                closed.set(true)
                proceed.countDown()
                super.close()
            }
        }, payload.size.toLong())
        val handler = DownloadHandler(context)
        try {
            handler.rotatePrivateScope()
            val id = (handler.enqueueOrGetExisting(
                "https://private.test/accepted.bin", null, null, "application/octet-stream",
                isPrivate = true, response = response,
            ) as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            handler.endPrivateScope()
            val running = handler.downloads.value.single { it.id == id }
            assertEquals(DownloadStatus.DOWNLOADING, running.status)
            assertFalse(running.canResume)
            assertFalse("Session exit must not close the active response", closed.get())
            assertNull(handler.retry(id))
            handler.rotatePrivateScope()
            assertFalse(handler.downloads.value.single { it.id == id }.canResume)
            proceed.countDown()
            val completed = withTimeout(5_000) {
                handler.downloads.first { rows -> rows.any { it.id == id && it.status == DownloadStatus.COMPLETED } }
            }.single { it.id == id }
            assertEquals(payload.size.toLong(), completed.totalBytes)
            assertFalse(completed.canResume)
            assertTrue(closed.get())
            assertEquals(DownloadStatus.COMPLETED,
                DownloadMetadataStore(context.getSharedPreferences("downloads", Context.MODE_PRIVATE))
                    .load().entries.single { it.id == id }.status)
        } finally {
            proceed.countDown()
            handler.closeAndAwait()
        }
    }

    @Test fun endingTheSessionRemovesPausedPartsButKeepsThePausedRecord() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            handler.rotatePrivateScope()
            val id = requireNotNull(handler.enqueue("https://private.test/paused.bin?token=secret", null, null, null, referer = "https://private.test/page", isPrivate = true, cookieHeader = "secret=1"))
            handler.pause(id)
            assertTrue(handler.downloads.value.single { it.id == id }.canResume)
            val directory = File(context.noBackupFilesDir, "browser-downloads/$id")
            assertTrue(directory.mkdirs())
            File(directory, "part-0").writeBytes(byteArrayOf(1, 2, 3))
            handler.endPrivateScope()
            withTimeout(5_000) { while (directory.exists()) delay(10) }
            val paused = handler.downloads.value.single { it.id == id }
            assertEquals(DownloadStatus.PAUSED, paused.status)
            assertFalse(paused.canResume)
            assertNull(handler.retry(id))
            withTimeout(5_000) { handler.downloads.first { rows -> rows.single { it.id == id }.url.isEmpty() } }
            val metadata = DownloadHandler::class.java.getDeclaredField("metadata").apply { isAccessible = true }
                .get(handler) as Map<*, *>
            val entry = metadata[id] as DownloadMetadata
            assertNull(entry.cookie)
            assertNull(entry.referer)
            assertEquals("", entry.url)
        } finally {
            handler.closeAndAwait()
        }
    }
    @Test fun acceptedHlsRetryFinishesAfterExitThenDropsItsPlaylist() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Keep this reproduction independent of Robolectric's fake video-directory restriction.
        val savedFile = File(context.cacheDir, "hls-review-output.ts")
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
                android.database.MatrixCursor(projection ?: arrayOf("_id"))
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = android.net.Uri.withAppendedPath(uri, "1")
            override fun getType(uri: android.net.Uri) = "video/mp2t"
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 1
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = if (savedFile.delete()) 1 else 0
            override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(savedFile,
                android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        val calls = AtomicInteger()
        val playlistRequests = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/one-shot.m3u8") {
            playlistRequests.incrementAndGet()
            it.sendResponseHeaders(410, -1); it.close()
        }
        server.createContext("/seg.ts") { exchange ->
            when (calls.incrementAndGet()) {
                1 -> exchange.sendResponseHeaders(404, -1)
                2 -> {
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS))
                    exchange.sendResponseHeaders(503, -1)
                }
                else -> {
                    val bytes = ByteArray(188) { 0x47 }
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            exchange.close()
        }
        server.start()
        val handler = DownloadHandler(context)
        try {
            handler.rotatePrivateScope()
            val playlist = "#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"
            val id = (handler.enqueueOrGetExisting(
                "http://127.0.0.1:${server.address.port}/one-shot.m3u8", null, null, "application/vnd.apple.mpegurl",
                isPrivate = true, response = DownloadResponse(playlist.byteInputStream(), playlist.length.toLong()))
                as DownloadHandler.EnqueueOutcome.Started).id
            withTimeout(10000) {
                while (handler.downloads.value.none { it.id == id && it.status == DownloadStatus.FAILED }) delay(10)
                while ((registry(handler, "jobs") as Map<*, *>).containsKey(id)) delay(10)
            }
            @Suppress("UNCHECKED_CAST")
            val cache = registry(handler, "privatePlaylists") as MutableMap<Long, String>
            assertEquals(playlist, cache[id])
            assertEquals(id, handler.retry(id))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            handler.endPrivateScope()
            assertEquals(playlist, cache[id])
            proceed.countDown()
            withTimeout(10000) {
                while (handler.downloads.value.none { it.id == id && it.status in setOf(DownloadStatus.FAILED, DownloadStatus.COMPLETED) }) delay(10)
            }
            val terminal = handler.downloads.value.single { it.id == id }
            assertEquals(DownloadStatus.COMPLETED, terminal.status)
            assertEquals(0, playlistRequests.get())
            assertEquals(188, savedFile.length())
            withTimeout(5_000) {
                while (cache.containsKey(id) || (registry(handler, "metadata") as Map<*, *>)[id]
                    .let { (it as DownloadMetadata).url.isNotEmpty() }) delay(10)
            }
        } finally { proceed.countDown(); handler.closeAndAwait(); server.stop(0) }
    }
    private fun registry(handler: DownloadHandler, name: String): Any =
        DownloadHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(handler)
}
