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
    @Test fun aWritableDirectoryRemainsSelectableWhenSystemDownloadsIsTheDefault() {
        val tree = android.net.Uri.parse("content://provider/tree/primary%3AFilms")
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        repository.useCustomDirectory(tree, "Films")
        val settings = repository.useSystemDownloads()
        assertEquals(DownloadDestinationMode.SYSTEM_DOWNLOADS, settings.destinationMode)
        assertEquals(tree.toString(), settings.customTreeUri)
        assertEquals(tree.toString(), DownloadSettingsRepository(context).load().customTreeUri)
        context.contentResolver.releasePersistableUriPermission(tree, flags)
        assertEquals(null, repository.load().customTreeUri)
        context.contentResolver.takePersistableUriPermission(tree, flags)
        assertEquals(tree.toString(), repository.load().customTreeUri)
    }

    @Test fun aRevokedDefaultFallsBackWithoutOfferingTheRevokedDirectory() {
        repository.useCustomDirectory(android.net.Uri.parse("content://provider/tree/missing"), "Missing")
        val restored = DownloadSettingsRepository(context).load()
        assertEquals(DownloadDestinationMode.SYSTEM_DOWNLOADS, restored.destinationMode)
        assertEquals(null, restored.customTreeUri)
    }

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
        assertEquals(SYSTEM_DIRECTORY_LABEL, settings.destinationLabel)
    }

    @Test
    fun `thread preference persists and is bounded`() {
        assertEquals(16, repository.setThreadCount(99).threadCount)
        assertEquals(16, DownloadSettingsRepository(context).load().threadCount)
        assertEquals(1, repository.setThreadCount(-5).threadCount)
    }

    @Test fun `transfer policy persists and imports leave absent fields alone`() {
        repository.setMaxConcurrentDownloads(2)
        repository.setSpeedLimitKib(512)
        repository.importSettings(8, null, false)
        val restored = DownloadSettingsRepository(context).load()
        assertEquals(2, restored.maxConcurrentDownloads)
        assertEquals(512, restored.speedLimitKib)
        assertEquals(5, repository.setMaxConcurrentDownloads(99).maxConcurrentDownloads)
        assertEquals(0, repository.setSpeedLimitKib(-1).speedLimitKib)
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
