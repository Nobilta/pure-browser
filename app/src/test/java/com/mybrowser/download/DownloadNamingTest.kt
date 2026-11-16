package com.mybrowser.download

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the confirmation dialog offers to save a task as before anything is fetched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadNamingTest {

    private fun preview(url: String, mimeType: String?): String? {
        val handler = DownloadHandler(RuntimeEnvironment.getApplication())
        return try {
            handler.previewFilename(url, null, mimeType)
        } finally {
            handler.close()
        }
    }

    @Test
    fun `an HLS address is named for the stream it produces`() {
        assertEquals("video.ts", preview("https://cdn.example.test/video.m3u8", "application/vnd.apple.mpegurl"))
        assertEquals("video.ts", preview("https://cdn.example.test/video.m3u8", null))
        assertEquals("movie.mp4", preview("https://cdn.example.test/movie.mp4", "video/mp4"))
    }

    @Test
    fun `an FTP address keeps the file name it names`() {
        assertEquals("report.bin", preview("ftp://files.example.test/pub/report.bin", null))
        assertEquals("report final.pdf", preview("ftp://files.example.test/pub/report%20final.pdf", null))
        assertNull(preview("ftp://files.example.test/", null))
    }
}
