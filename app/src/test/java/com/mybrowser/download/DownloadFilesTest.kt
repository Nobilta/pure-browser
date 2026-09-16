package com.mybrowser.download

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.mybrowser.R
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadFilesTest {
    /**
     * "name:tag:path" for every entry of a provider's path configuration: the contract that keeps
     * URI grants inside one directory tree.
     */
    private fun declaredPaths(context: Context, resource: Int): List<String> {
        val parser = context.resources.getXml(resource)
        val declared = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name == "paths") continue
            declared += "${parser.getAttributeValue(null, "name")}:${parser.name}:" +
                parser.getAttributeValue(null, "path")
        }
        return declared.sorted()
    }

    private fun assertCameraAndUpdateUrisStayIsolated() {
        val context = RuntimeEnvironment.getApplication()
        val cameraAuthority = context.packageName + ".captures"
        val updateAuthority = context.packageName + ".updates"
        val camera = context.packageManager.resolveContentProvider(cameraAuthority, 0)!!
        val update = context.packageManager.resolveContentProvider(updateAuthority, 0)!!
        // Android's provider registry keys the component by class name, even when
        // the manifest declares different authorities. Aliasing breaks URI grants.
        assertNotEquals(camera.name, update.name)
        // Each provider may expose its own cache directory and nothing else.
        assertEquals(listOf("capture:cache-path:web-capture/"), declaredPaths(context, R.xml.capture_paths))
        assertEquals(listOf("updates:cache-path:updates/"), declaredPaths(context, R.xml.update_paths))
        // The round trip below relies on FileProvider matching a canonical file path against its
        // configured roots. A Windows host hands the JVM its temp directory in 8.3 short form, which
        // makes those two sides disagree for reasons no device can reproduce, so the structural
        // checks above carry the contract there.
        assumeFalse(
            "FileProvider root matching is unreliable under Robolectric on Windows",
            System.getProperty("os.name").orEmpty().startsWith("Windows"),
        )
        val photo = File(context.cacheDir, "web-capture/photo.jpg").apply { parentFile!!.mkdirs(); writeText("camera") }
        val apk = File(context.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeText("update") }
        try {
            val photoUri = FileProvider.getUriForFile(context, cameraAuthority, photo)
            val apkUri = FileProvider.getUriForFile(context, updateAuthority, apk)
            assertEquals("camera", context.contentResolver.openInputStream(photoUri)!!.bufferedReader().use { it.readText() })
            assertEquals("update", context.contentResolver.openInputStream(apkUri)!!.bufferedReader().use { it.readText() })
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, cameraAuthority, apk) }
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, updateAuthority, photo) }
        } catch (error: IllegalArgumentException) {
            // The stock message does not say which path pair failed to match, which is the only way
            // to tell a real misconfiguration from a host filesystem difference.
            throw AssertionError(
                "cacheDir=${context.cacheDir.path} | cameraRoot=${File(context.cacheDir, "web-capture").path} | " +
                    "photo=${photo.path} | canonical=${photo.canonicalPath} | root=${File(context.cacheDir, "web-capture").canonicalPath}",
                error,
            )
        } finally { photo.delete(); apk.delete() }
    }

    @Test fun readableFilesCarryContentUriAndTemporaryReadGrantWithoutAutomaticallyStartingAnActivity() {
        assertCameraAndUpdateUrisStayIsolated()
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "web-capture/open-test.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".captures", file)
        val ready = DownloadFiles.inspect(context, uri, file.name) as DownloadOpenResult.Ready
        assertEquals(Intent.ACTION_VIEW, ready.intent.action)
        assertNull(ready.intent.type)
        assertEquals(uri, ready.intent.data)
        assertEquals(uri, ready.intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, ready.intent.flags)
        assertNull(org.robolectric.Shadows.shadowOf(context).nextStartedActivity)
        file.delete()
        assertEquals(DownloadOpenResult.Unavailable, DownloadFiles.inspect(context, uri, file.name))
    }

    @Test fun downloadRecordsCannotLaunchRemoteUrlsOrPrivateFilePaths() {
        val context = RuntimeEnvironment.getApplication()
        for (uri in listOf("https://example.com/app.apk", "file:///data/private.apk", "intent://install")) {
            assertEquals(DownloadOpenResult.Unavailable, DownloadFiles.inspect(context, uri.toUri(), "app.apk"))
        }
    }

    @Test fun unfinishedAndMissingDownloadsDoNotProduceAnOpenIntent() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("entries", """
            [{"id":-71,"backend":"LOCAL","url":"https://example.com/test.apk","filename":"test.apk","status":"PAUSED"}]
        """.trimIndent()).commit()
        val handler = DownloadHandler(context)
        try {
            assertEquals(DownloadOpenResult.NotCompleted, handler.fileToOpen(-71))
            assertEquals(DownloadOpenResult.Unavailable, handler.fileToOpen(-72))
        } finally { handler.close() }
    }
}
