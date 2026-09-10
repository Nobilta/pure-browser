package com.mybrowser.download

import android.app.Application
import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadHandlerTest {
    @Test fun repeatedPauseResumeKeepsTheSameTaskAndCancelCleansUpAfterWritersFinish() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val total = 4 * 1024 * 1024
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val (first, last) = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
                ?: listOf(0, total - 1)
            exchange.responseHeaders.add("ETag", "\"handler-stable\"")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$total")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (last - first + 1).toLong())
            runCatching { exchange.responseBody.use { output ->
                var remaining = last - first + 1
                val block = ByteArray(16384) { 3 }
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    output.write(block, 0, count); output.flush(); remaining -= count
                    Thread.sleep(5)
                }
            } }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            val id = requireNotNull(handler.enqueue("http://127.0.0.1:${server.address.port}/file", null, null, null))
            withTimeout(10_000) { handler.downloads.first { list -> list.any { it.id == id && it.bytesDownloaded > 0 } } }
            repeat(10) {
                handler.pause(id)
                assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
                assertEquals(id, handler.retry(id))
            }
            handler.pause(id)
            delay(300)
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
            val directory = File(context.noBackupFilesDir, "browser-downloads/$id")
            assertTrue(directory.exists())
            handler.cancel(id)
            withTimeout(5_000) { while (directory.exists()) delay(20) }
            assertFalse(handler.downloads.value.any { it.id == id })
        } finally { handler.close(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    @Test fun startupMarksInterruptedTasksPausedButDoesNotAutomaticallyResumePrivateTasks() {
        val context = RuntimeEnvironment.getApplication()
        val data = """[{"id":-15,"backend":"LOCAL","url":"https://private.example/file","filename":"private.bin","status":"DOWNLOADING","autoResumeAllowed":false,"bytesDownloaded":123}]"""
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("entries", data).commit()
        val handler = DownloadHandler(context)
        try {
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single().status)
            handler.resumeInterrupted()
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single().status)
            val saved = JSONArray(context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString("entries", "[]"))
            assertFalse(saved.getJSONObject(0).has("cookie"))
        } finally { handler.close() }
    }
}
