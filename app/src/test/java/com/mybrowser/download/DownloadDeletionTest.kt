package com.mybrowser.download

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Job
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
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadDeletionTest {
    @Test fun deletingActiveTransfersStopsWritersAndRemovesPartialFiles() = runBlocking {
        withSlowDownload { handler, id ->
            val job = jobs(handler)[id] as Job
            handler.delete(id, false)
            awaitDeleted(handler, id)
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
        }
    }

    @Test fun deletionUsesTheCurrentRecordWhenPauseWinsTheTaskLock() = runBlocking {
        withSlowDownload { handler, id ->
            val lock = handler.javaClass.getDeclaredField("taskLock").apply { isAccessible = true }.get(handler)
            lateinit var deletion: Thread
            synchronized(lock) {
                deletion = thread { handler.delete(id, true) }
                val deadline = System.nanoTime() + 2_000_000_000L
                while (deletion.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, deletion.state)
                // Like a progress callback, pause replaces the immutable metadata record.
                // Deletion must still work if that replacement happens before cancellation.
                handler.pause(id)
            }
            deletion.join(2000)
            assertFalse(deletion.isAlive)
            awaitDeleted(handler, id)
        }
    }

    private suspend fun awaitDeleted(handler: DownloadHandler, id: Long) {
        val directory = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "browser-downloads/$id")
        withTimeout(5_000) {
            while (handler.downloads.value.any { it.id == id } || directory.exists() || jobs(handler).containsKey(id)) delay(20)
        }
    }

    private fun jobs(handler: DownloadHandler): Map<*, *> =
        handler.javaClass.getDeclaredField("jobs").apply { isAccessible = true }.get(handler) as Map<*, *>

    private suspend fun withSlowDownload(check: suspend (DownloadHandler, Long) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val total = 64 * 1024 * 1024
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val (first, last) = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
                ?: listOf(0, total - 1)
            exchange.responseHeaders.add("ETag", "\"deletion-test\"")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$total")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (last - first + 1).toLong())
            runCatching { exchange.responseBody.use { output ->
                val block = ByteArray(16384) { 7 }
                var remaining = last - first + 1
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    output.write(block, 0, count); output.flush(); remaining -= count
                    Thread.sleep(10)
                }
            } }
        }
        server.executor = workers
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
        val handler = DownloadHandler(context)
        try {
            val id = requireNotNull(handler.enqueue("http://127.0.0.1:${server.address.port}/file", null, null, null))
            withTimeout(10_000) { handler.downloads.first { rows -> rows.any { it.id == id && it.bytesDownloaded > 0 } } }
            check(handler, id)
        } finally { handler.close(); server.stop(0); workers.shutdownNow() }
    }
}
