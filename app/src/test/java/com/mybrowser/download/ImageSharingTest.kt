package com.mybrowser.download

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.mybrowser.tabs.MediaNavigation
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageSharingTest {
    private lateinit var server: HttpServer
    private lateinit var activity: Activity
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    private val directory get() = File(activity.cacheDir, "web-capture")

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        directory.deleteRecursively()
        // FileProvider caches canonical roots; Robolectric gives every test a different cache dir.
        val authority = activity.packageName + ".captures"
        val provider = androidx.core.content.FileProvider()
        provider.attachInfo(activity, activity.packageManager.resolveContentProvider(authority,
            android.content.pm.PackageManager.GET_META_DATA)!!)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun teardown() {
        server.stop(0)
        directory.deleteRecursively()
        activity.finish()
        Dispatchers.resetMain()
    }

    private fun response(path: String, type: String?, body: ByteArray) {
        server.createContext(path) { e ->
            type?.let { e.responseHeaders.add("Content-Type", it) }
            e.sendResponseHeaders(200, body.size.toLong())
            e.responseBody.use { it.write(body) }; e.close()
        }
    }

    private suspend fun share(path: String, navigation: MediaNavigation? = null, cookie: String? = null) = ImageSharing.share(activity,
        "http://127.0.0.1:${server.address.port}$path", "test", navigation, cookie, "Share",
        privateSession = false, engine = HttpDownloadEngine(), sessionActive = { true })

    private suspend fun privateShare(path: String, active: Boolean) = ImageSharing.share(activity,
        "http://127.0.0.1:${server.address.port}$path", "test", null, null, "Share",
        privateSession = true, engine = HttpDownloadEngine(), sessionActive = { active })

    private fun assertPngShare() {
        val chooser = shadowOf(activity).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("image/png", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(0).endsWith(".png"))
        }
        activity.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(png, it.readBytes()) }
    }

    @Test fun aDeliveredPrivateShareLivesOutsideTheSessionDirectory() = runBlocking {
        response("/image", "image/png", png)
        assertTrue(privateShare("/image", active = true))
        assertPngShare()
        // The working directory is cleared when the session ends; a delivered file must not be.
        assertTrue(File(directory, "private").listFiles().orEmpty().isEmpty())
        assertEquals(1, File(directory, "share").listFiles().orEmpty().size)
    }

    @Test fun cancellationAfterTheMoveLeavesNoDeliveredFileBehind() = runBlocking {
        response("/image", "image/png", png)
        var checks = 0
        val failure = runCatching {
            ImageSharing.share(activity,
                "http://127.0.0.1:${server.address.port}/image", "test", null, null, "Share",
                privateSession = true,
                engine = HttpDownloadEngine(), sessionActive = {
                    checks++
                    if (checks <= 2) true else throw kotlinx.coroutines.CancellationException("session ended")
                })
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(File(directory, "share").listFiles().orEmpty().isEmpty())
    }

    @Test fun aSessionThatEndsBeforeDeliveryDoesNotOpenAChooser() = runBlocking {
        response("/image", "image/png", png)
        var checks = 0
        val result = ImageSharing.share(activity,
            "http://127.0.0.1:${server.address.port}/image", "test", null, null, "Share",
            privateSession = true, engine = HttpDownloadEngine(), sessionActive = { checks++ < 2 })
        assertFalse(result)
        assertNull(shadowOf(activity).nextStartedActivity)
        // The IO check passed and the file was moved, so delivery must remove it again.
        assertTrue(File(directory, "share").listFiles().orEmpty().isEmpty())
    }

    @Test fun aPrivateShareThatOutlivedItsSessionWritesNothing() = runBlocking {
        response("/image", "image/png", png)
        assertFalse(privateShare("/image", active = false))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(File(directory, "private").listFiles().orEmpty().isEmpty())
        assertTrue(File(directory, "share").listFiles().orEmpty().isEmpty())
    }

    @Test fun extensionlessPngUsesTheResponseTypeAndFilename() = runBlocking {
        response("/image", "image/png; charset=binary", png)
        assertTrue(share("/image"))
        assertPngShare()
    }

    @Test fun redirectResponseTypeOverridesTheOriginalExtension() = runBlocking {
        server.createContext("/wrong.jpg") { e ->
            e.responseHeaders.add("Location", "/image")
            e.sendResponseHeaders(302, -1); e.close()
        }
        response("/image", "image/png", png)
        assertTrue(share("/wrong.jpg"))
        assertPngShare()
    }

    @Test fun redirectToAnotherOriginReceivesOnlyThePageOrigin() = runBlocking {
        val origin = "http://127.0.0.1:${server.address.port}"
        val target = "$origin/redirect"
        val page = "$origin/account?entry=private"
        val cdn = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val headers = java.util.concurrent.ConcurrentHashMap<String, String>()
        server.createContext("/redirect") { e ->
            headers["first"] = e.requestHeaders.getFirst("Referer").orEmpty()
            e.responseHeaders.add("Location", "http://127.0.0.1:${cdn.address.port}/picture")
            e.sendResponseHeaders(302, -1)
            e.close()
        }
        cdn.createContext("/picture") { e ->
            headers["last"] = e.requestHeaders.getFirst("Referer").orEmpty()
            headers["cookie"] = e.requestHeaders.getFirst("Cookie").orEmpty()
            e.responseHeaders.add("Content-Type", "image/png")
            e.sendResponseHeaders(200, png.size.toLong())
            e.responseBody.use { it.write(png) }
            e.close()
        }
        cdn.start()
        try {
            assertTrue(share("/redirect", MediaNavigation.create(target, "$page#section"), "session=private"))
            assertPngShare()
            assertEquals(mapOf("first" to page, "last" to "$origin/", "cookie" to ""), headers)
        } finally { cdn.stop(0) }
    }

    @Test fun genericContentTypeFallsBackToTheFinalUrl() = runBlocking {
        response("/image.png", "application/octet-stream", png)
        assertTrue(share("/image.png"))
        assertPngShare()
    }

    @Test fun htmlLoginPageIsNotSharedAndItsCacheFileIsDeleted() = runBlocking {
        response("/image.jpg", "text/html; charset=UTF-8", "<html>Sign in</html>".toByteArray())
        assertFalse(share("/image.jpg"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun chunkedImageOver64MiBIsNotSharedAndItsCacheFileIsDeleted() = runBlocking {
        server.createContext("/large") { e ->
            e.responseHeaders.add("Content-Type", "image/png")
            e.sendResponseHeaders(200, 0)
            try {
                e.responseBody.use { out ->
                    val chunk = ByteArray(64 * 1024)
                    repeat(1025) { out.write(chunk) }
                }
            } catch (_: java.io.IOException) {
                // The client closes the transfer as soon as it reaches the share limit.
            } finally { e.close() }
        }
        assertFalse(share("/large"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
