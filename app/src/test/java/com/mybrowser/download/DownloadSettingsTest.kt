package com.mybrowser.download

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadSettingsTest {

    private lateinit var context: Context
    private lateinit var repository: DownloadSettingsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(DownloadSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = DownloadSettingsRepository(context)
    }

    @Test
    fun `fresh install uses public downloads and four threads`() {
        val settings = repository.load()

        assertEquals(DownloadDestinationMode.SYSTEM_DOWNLOADS, settings.destinationMode)
        assertEquals(DEFAULT_DOWNLOAD_THREADS, settings.threadCount)
        assertEquals("系统下载目录", settings.destinationLabel)
    }

    @Test
    fun `thread preference persists and is bounded`() {
        assertEquals(16, repository.setThreadCount(99).threadCount)
        assertEquals(16, DownloadSettingsRepository(context).load().threadCount)
        assertEquals(1, repository.setThreadCount(-5).threadCount)
    }

    @Test
    fun `system destination selection does not discard remembered custom label`() {
        context.getSharedPreferences(DownloadSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("custom_tree_uri", "content://provider/tree/primary%3AFilms")
            .putString("custom_directory_label", "Films")
            .commit()

        val settings = repository.useSystemDownloads()

        assertEquals(DownloadDestinationMode.SYSTEM_DOWNLOADS, settings.destinationMode)
        assertEquals("Films", settings.customDirectoryLabel)
    }
}
