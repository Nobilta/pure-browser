package com.mybrowser.download

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadLifecycleRegressionTest {
    @Test fun slowDirectoryQueryDoesNotBlockTaskControlOrCommitACancelledSelection() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controlled = CountDownLatch(1)
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return android.database.MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf("Slow folder")) }
            }
            override fun getType(uri: Uri) = null
            override fun insert(uri: Uri, values: android.content.ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "slow.directory" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("slow.directory", provider)
        val handler = DownloadHandler(context)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val selection = handler.directorySelection()
        val pending = launch(kotlinx.coroutines.Dispatchers.IO) {
            selection.select(Uri.parse("content://slow.directory/tree/opaque-id"))
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val control = executor.submit {
                handler.cancel(-999)
                selection.close()
                pending.cancel()
                controlled.countDown()
            }
            assertTrue("Task control must not wait for DocumentProvider.query", controlled.await(2, TimeUnit.SECONDS))
            control.get()
            release.countDown()
            pending.join()
            assertNull(selection.destination)
            assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        } finally {
            release.countDown()
            pending.cancel()
            pending.join()
            executor.shutdownNow()
            handler.close()
        }
    }

    @Test fun anEndedPrivateShareDoesNotCreateFilesOrStartARequest() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(ImageSharing.share(context, "https://files.test/image", "test", null, "private=secret", "Share",
            privateSession = true, sessionActive = { false }, engine = HttpDownloadEngine()))
        assertFalse(File(context.cacheDir, "web-capture/private").exists())
    }

    @Test fun aRefusedServiceDoesNotRetainALazyJobOrMutex() {
        val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw IllegalStateException("Foreground start refused")
            }
        }
        val handler = DownloadHandler(context)
        try {
            val id = requireNotNull(handler.enqueue("https://files.test/a", null, null, null))
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
            assertTrue(registry(handler, "jobs").isEmpty())
            assertTrue(registry(handler, "transferLocks").isEmpty())
        } finally { handler.close() }
    }

    @Test fun cancellingAReplacementWaitsForTheOriginalWriterBeforeDeletingItsDirectory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val response = DownloadResponse(object : ByteArrayInputStream(ByteArray(32)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(finishRead.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
            // A kernel/provider read can still be unwinding after close/cancellation returns.
            override fun close() = Unit
        }, 32)
        val handler = DownloadHandler(context)
        try {
            val id = (handler.enqueueOrGetExisting("https://files.test/a", null, null, null,
                response = response) as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val writerLock = registry(handler, "transferLocks")[id]
            val directory = File(context.noBackupFilesDir, "browser-downloads/$id")
            handler.pause(id)
            assertEquals(id, handler.retry(id))
            handler.cancel(id)
            withTimeout(5_000) { while (registry(handler, "jobs").containsKey(id)) delay(10) }
            delay(100)
            assertSame(writerLock, registry(handler, "transferLocks")[id])
            assertTrue(directory.exists())
            finishRead.countDown()
            withTimeout(5_000) {
                while (directory.exists() || registry(handler, "transferLocks").containsKey(id)) delay(10)
            }
        } finally { finishRead.countDown(); handler.close() }
    }

    @Test fun pendingDirectoriesSurviveCleanupAndReleaseOnlyAfterTheirOwnerFinishes() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            val first = handler.directorySelection()
            val second = handler.directorySelection()
            val tree = Uri.parse("content://provider/tree/pending")
            assertNotNull(first.select(tree))
            assertNotNull(second.select(tree))
            handler.releaseUnusedDirectoryGrants()
            assertTrue(DownloadSettingsRepository.hasWritableGrant(context, tree.toString()))
            first.close()
            handler.releaseUnusedDirectoryGrants()
            assertTrue(DownloadSettingsRepository.hasWritableGrant(context, tree.toString()))
            second.close()
            handler.releaseUnusedDirectoryGrants()
            assertFalse(DownloadSettingsRepository.hasWritableGrant(context, tree.toString()))
            assertNull(second.select(tree))
        } finally { handler.close() }
    }

    @Test fun cancelledDirectorySelectionCannotReplaceTheCurrentGrant() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            val selection = handler.directorySelection()
            val lock = DownloadHandler::class.java.getDeclaredField("taskLock").apply { isAccessible = true }.get(handler)
            val stale = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val queued = CountDownLatch(1)
            val old = synchronized(lock) {
                val job = stale.launch {
                    queued.countDown()
                    selection.select(Uri.parse("content://provider/tree/old"))
                }
                assertTrue(queued.await(5, TimeUnit.SECONDS))
                job.cancel()
                job
            }
            old.join()
            val tree = Uri.parse("content://provider/tree/new")
            assertEquals(tree.toString(), selection.select(tree)?.treeUri)
            handler.releaseUnusedDirectoryGrants()
            assertEquals(tree.toString(), selection.destination?.treeUri)
            assertTrue(DownloadSettingsRepository.hasWritableGrant(context, tree.toString()))
            selection.close()
        } finally { handler.close() }
    }

    @Test fun sourceDisplaySkipsBlankPagesAndRedactsCredentials() {
        assertEquals("ftp://files.test/a", DownloadValidation.displaySource(null, "about:blank", "ftp://user:secret@files.test/a#fragment"))
        assertEquals("https://page.test/path", DownloadValidation.displaySource(null, "https://user:secret@page.test/path", "https://files.test/a"))
    }

    private fun registry(handler: DownloadHandler, name: String): Map<*, *> =
        DownloadHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(handler) as Map<*, *>
}
