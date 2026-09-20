package com.mybrowser.download

import android.app.Application
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.webkit.CookieManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadRedownloadTest {
    private class OldFileProvider(val file: File) : ContentProvider() {
        val deletes = AtomicInteger()
        override fun onCreate() = true
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes.incrementAndGet(); return if (file.delete()) 1 else 0
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun failedNewCopyRetainsOldFileAndUsesCurrentCookie() = exercise(cancelNew = false)
    @Test fun cancelledNewCopyRetainsOldFileAndRepeatedTapsCoalesce() = exercise(cancelNew = true)

    private fun exercise(cancelNew: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cookie: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            cookie = exchange.requestHeaders.getFirst("Cookie")
            requested.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(403, -1); exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/file"
        val file = File(context.cacheDir, "original.bin").apply { writeText("keep these original bytes") }
        val provider = OldFileProvider(file)
        ShadowContentResolver.registerProviderInternal("redownload.test", provider)
        val entry = JSONObject().put("id", -123).put("backend", "LOCAL").put("url", url)
            .put("filename", "original.bin").put("status", "COMPLETED").put("autoResumeAllowed", true)
            .put("destinationUri", "content://redownload.test/original")
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("entries", JSONArray().put(entry).toString()).commit()
        CookieManager.getInstance().setCookie(url, "session=fresh")
        val handler = DownloadHandler(context)
        try {
            val replacement = requireNotNull(handler.retry(-123))
            assertNotEquals(-123L, replacement)
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            assertEquals(replacement, handler.retry(-123))
            assertEquals("session=fresh", cookie)
            if (cancelNew) handler.cancel(replacement)
            release.countDown()
            withTimeout(5000) {
                handler.downloads.first { rows ->
                    if (cancelNew) rows.none { it.id == replacement }
                    else rows.any { it.id == replacement && it.status == DownloadStatus.FAILED }
                }
            }
            assertTrue(handler.downloads.value.any { it.id == -123L && it.status == DownloadStatus.COMPLETED })
            assertEquals("keep these original bytes", file.readText())
            assertEquals(0, provider.deletes.get())
        } finally { release.countDown(); handler.close(); server.stop(0) }
    }
}
