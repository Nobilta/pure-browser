package com.mybrowser.download

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a playlist keeps its source MIME and is published as a transport stream`() {
        val handler = DownloadHandler(RuntimeEnvironment.getApplication())
        try {
            // No .m3u8 suffix: only the MIME identifies this as a playlist, which is the case a
            // record that had already been rewritten to video/mp2t could no longer recognise.
            val id = requireNotNull(
                handler.enqueue("http://127.0.0.1:1/live?token=1", null, null, "application/vnd.apple.mpegurl"),
            )
            val prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("downloads", android.content.Context.MODE_PRIVATE)
            val stored = DownloadMetadataStore(prefs).load().entries.single { it.id == id }

            assertEquals("application/vnd.apple.mpegurl", stored.mimeType)
            assertEquals("live.ts", stored.filename)
            assertTrue(HlsDownloadEngine.handles(stored.url, stored.mimeType))
            assertEquals("video/mp2t", HlsDownloadEngine.outputMime(stored.url, stored.mimeType))
        } finally {
            handler.close()
        }
    }

    @Test
    fun `single-connection transports report one connection`() {
        val handler = DownloadHandler(RuntimeEnvironment.getApplication())
        try {
            val ftp = requireNotNull(handler.enqueue("ftp://127.0.0.1:1/file.bin", null, null, null))
            val hls = requireNotNull(
                handler.enqueue("http://127.0.0.1:1/live?token=1", null, null, "application/vnd.apple.mpegurl"),
            )
            val items = handler.downloads.value.associateBy { it.id }
            // Both transports move bytes over one socket; before the first progress callback the
            // detail sheet used to claim the configured thread count instead.
            assertEquals(1, items.getValue(ftp).threadCount)
            assertEquals(1, items.getValue(hls).threadCount)
        } finally {
            handler.close()
        }
    }

    @Test
    fun `an FTP address keeps the file name it names`() {
        assertEquals("report.bin", preview("ftp://files.example.test/pub/report.bin", null))
        assertEquals("report final.pdf", preview("ftp://files.example.test/pub/report%20final.pdf", null))
        assertNull(preview("ftp://files.example.test/", null))
    }
}
