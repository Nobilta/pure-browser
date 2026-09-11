package com.mybrowser.download

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadFilesTest {
    @Test fun readableFilesCarryContentUriAndTemporaryReadGrantWithoutAutomaticallyStartingAnActivity() {
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
