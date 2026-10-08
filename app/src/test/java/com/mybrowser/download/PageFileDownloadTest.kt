package com.mybrowser.download

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.first
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The page-file path: a task whose bytes come from the document rather than from an address.
 *
 * The transfer is driven by a fake [PageFileDownload] because the real one needs a WebView, and what
 * is being tested here is everything downstream of it — the record, the slice pump, the temporary
 * file and what happens when the page stops early. The page half itself is covered by
 * `validation/blob-download.test.cjs`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageFileDownloadTest {
    private fun connectedContext(): Context {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setActiveNetworkInfo(
            org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true,
            ),
        )
        shadow.setNetworkCapabilities(
            connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            },
        )
        return context
    }

    private fun offer(size: Long, name: String = "report.bin") = PageFileOffer(
        url = "blob:https://example.com/abc",
        pageUrl = "https://example.com/page",
        mimeType = "application/pdf",
        size = size,
        name = name,
    )

    /**
     * A [PageFileDownload] stand-in that hands slices over the way the real one does: the sink is
     * called on a background thread, and the next slice only after the owner asked for it.
     */
    /**
     * A [PageFileSource] stand-in that behaves like the real channel: the slice for a request is
     * handed to the sink before the request returns, and the request that follows the last one is
     * answered with the page's own end marker.
     */
    private open class FakeSource(
        private val body: ByteArray,
        private val chunk: Int = 64 * 1024,
    ) : PageFileSource {
        private var sink: PageFileSink? = null
        private var live = true

        override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean {
            this.sink = sink
            return true
        }

        override fun requestNext(id: Long, written: Long): Boolean {
            val target = sink ?: return false
            if (written >= body.size) { live = false; return false }
            val end = minOf(body.size, written.toInt() + chunk)
            target.write(body.copyOfRange(written.toInt(), end))
            return true
        }

        override fun isTransferring(id: Long): Boolean = live
        override fun cancel() { live = false }
    }

    private fun tempDirectory(context: Context, id: Long) =
        File(context.noBackupFilesDir, "browser-downloads/$id")

    @Test fun aPageFileIsRecordedAndPublishedWithoutAnyAddress() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val body = ByteArray(150_000) { (it % 251).toByte() }
        val source = FakeSource(body)
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://placeholder", userAgent = null, contentDisposition = null,
                mimeType = "application/pdf", pageFile = offer(body.size.toLong()),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(body.size.toLong()), source))
            // The pump writes on its own coroutine; the temporary file is the observable proof.
            withTimeout(10_000) {
                handler.downloads.first { list ->
                    list.any { it.id == id && (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED) }
                }
            }
            val item = handler.downloads.value.single { it.id == id }
            assertEquals(DownloadStatus.COMPLETED, item.status)
            assertEquals(body.size.toLong(), item.totalBytes)
            // A page file is never offered a pause or a resume it cannot honour.
            assertFalse(item.canPause)
            assertFalse(item.canResume)
            assertFalse(tempDirectory(context, id).exists())
        } finally {
            handler.close()
        }
    }

    @Test fun aShortPageFileIsFailedAndItsPartialFileRemoved() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        // The page promises more than it hands over: the pump reports short, and nothing is published.
        val source = FakeSource(ByteArray(1_000))
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://placeholder-2", userAgent = null, contentDisposition = null,
                mimeType = null, pageFile = offer(9_000, "short.bin").copy(url = "blob:https://example.com/short"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(9_000).copy(url = "blob:https://example.com/short"), source))
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == id && it.status == DownloadStatus.FAILED } }
            }
            assertFalse(tempDirectory(context, id).exists())
        } finally {
            handler.close()
        }
    }

    @Test fun aFailedPublishMarksTheTaskFailedAndRemovesItsParts() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://publish", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(64, "publish.bin").copy(url = "blob:https://example.com/publish"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            // A directory standing in for a part: its length satisfies the size check, and reading
            // it throws. That is exactly what a destination the system refuses to open looks like
            // from the caller's side, without needing a real Storage Access Framework provider.
            val directory = File(context.cacheDir, "not-a-part-\$id").apply { mkdirs() }
            val declared = directory.length()
            assertTrue("a directory must report a size for this probe", declared > 0)
            handler.onPageFileFinished(id, null, listOf(directory), declared)
            withTimeout(5_000) {
                handler.downloads.first { list -> list.any { it.id == id && it.status == DownloadStatus.FAILED } }
            }
            Unit
        } finally { handler.close() }
    }

    @Test fun aPageFileIsNeverResumedAndItsPlaceholderIsNeverFetched() {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://placeholder-3", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(10, "x.bin").copy(url = "blob:https://example.com/x"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            // Nothing may fetch the placeholder, and the record offers no second attempt.
            assertNull(handler.retry(id))
            handler.pause(id)
            assertNotEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
            handler.cancel(id)
            assertTrue(handler.downloads.value.none { it.id == id })
        } finally {
            handler.close()
        }
    }

    @Test fun cancellingAPageFileStopsTheTransfer() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val source = FakeSource(ByteArray(4 * 1024 * 1024))
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://placeholder-4", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(4L * 1024 * 1024, "big.bin").copy(url = "blob:https://example.com/big"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(4L * 1024 * 1024), source))
            delay(120)
            handler.cancel(id)
            withTimeout(5_000) { while (tempDirectory(context, id).exists()) delay(20) }
            assertTrue(handler.downloads.value.none { it.id == id })
        } finally {
            handler.close()
        }
    }

    @Test fun aNavigationEndsAnInFlightPageFile() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val body = ByteArray(2 * 1024 * 1024)
        // A source that never hands over anything: the writer would poll forever if the navigation
        // did not end the transfer.
        val source = object : PageFileSource {
            var live = true
            var sink: PageFileSink? = null
            override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean {
                this.sink = sink
                return true
            }
            override fun requestNext(id: Long, written: Long): Boolean = live
            override fun isTransferring(id: Long): Boolean = live
            override fun cancel() { live = false }
        }
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://navigate", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(body.size.toLong(), "nav.bin").copy(url = "blob:https://example.com/nav"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(body.size.toLong()), source))
            delay(100)
            // What onDocumentChanged() does at the channel level: stop the source. The handler then
            // has to notice on its own poll and fail the task.
            source.cancel()
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == id && it.status == DownloadStatus.FAILED } }
            }
            assertFalse(tempDirectory(context, id).exists())
        } finally { handler.close() }
    }

    @Test fun aRefusedFirstSliceFailsTheTaskAndBindsNothing() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        // start() succeeds, so the transfer looks alive, but the source then refuses the first
        // request — the shape of a channel that closed between the two calls.
        val source = object : PageFileSource {
            override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean = true
            override fun requestNext(id: Long, written: Long): Boolean = false
            override fun isTransferring(id: Long): Boolean = true
            override fun cancel() = Unit
        }
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://refused", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(1_024, "refused.bin").copy(url = "blob:https://example.com/refused"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            // False: the caller must not treat a task that has already failed as started.
            assertFalse(handler.startPageFile(id, offer(1_024), source))
            val item = handler.downloads.value.single { it.id == id }
            assertEquals(DownloadStatus.FAILED, item.status)
            assertFalse(tempDirectory(context, id).exists())
        } finally { handler.close() }
    }

    @Test fun aFailedWriteStopsThePageSource() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val body = ByteArray(64_000)
        val source = object : PageFileSource {
            var cancelled = false
            override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean = true
            override fun requestNext(id: Long, written: Long): Boolean = true
            override fun isTransferring(id: Long): Boolean = true
            override fun cancel() { cancelled = true }
        }
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://stops", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(body.size.toLong(), "stops.bin").copy(url = "blob:https://example.com/stops"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(body.size.toLong()), source))
            // A write failure ends the task; the source must be told, or the page keeps a transfer
            // open and refuses its next blob download.
            handler.failPageFile(id, "write")
            assertTrue("the page transfer has to be stopped", source.cancelled)
        } finally { handler.close() }
    }

    @Test fun aRestartedHandlerDoesNotKeepAPageFilesPartialBytes() = runBlocking {
        val context = connectedContext()
        val first = DownloadHandler(context)
        val outcome = first.enqueueOrGetExisting(
            url = "page-file://orphan", userAgent = null, contentDisposition = null, mimeType = null,
            pageFile = offer(4_096, "orphan.bin").copy(url = "blob:https://example.com/orphan"),
        ) as DownloadHandler.EnqueueOutcome.Started
        val directory = tempDirectory(context, outcome.id)
        directory.mkdirs()
        File(directory, "part-0").writeBytes(ByteArray(16))
        first.close()
        // A page file can never be resumed, so a restart must not keep its partial data. Startup
        // cleanup runs on its own coroutine, so the assertion waits for it rather than racing it.
        DownloadHandler(context).close()
        withTimeout(5_000) { while (directory.exists()) delay(20) }
        assertFalse("partial bytes of a page file must not survive a restart", directory.exists())
    }

    @Test fun aSourceIsNotLeftBoundAfterAnImmediateFailure() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        // start() accepts and then the first slice is refused: the task fails inside startPageFile,
        // which must not leave a source behind for a later cancel or delete to reach.
        val source = object : PageFileSource {
            override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean = true
            override fun requestNext(id: Long, written: Long): Boolean = false
            override fun isTransferring(id: Long): Boolean = true
            override fun cancel() = Unit
        }
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://bind", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(100, "bind.bin").copy(url = "blob:https://example.com/bind"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertFalse(handler.startPageFile(id, offer(100), source))
            assertEquals(DownloadStatus.FAILED, handler.downloads.value.single { it.id == id }.status)
        } finally { handler.close() }
    }

    @Test fun aWriteFailureThroughThePumpStillStopsTheSource() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val source = object : PageFileSource {
            var cancelled = false
            override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean = true
            override fun requestNext(id: Long, written: Long): Boolean = true
            override fun isTransferring(id: Long): Boolean = true
            override fun cancel() { cancelled = true }
        }
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://pump-fail", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(4_096, "pump-fail.bin").copy(url = "blob:https://example.com/pump-fail"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(4_096), source))
            // Exactly what the pump does when a write throws: report the reason through the same
            // hook, which is where the source has to be stopped as well as forgotten.
            handler.onPageFileFinished(id, "write", emptyList(), 0L)
            assertTrue("a failed transfer has to stop the page source", source.cancelled)
        } finally { handler.close() }
    }

    @Test fun aRestoredPageFileRecordOffersNoResume() {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            handler.enqueueOrGetExisting(
                url = "page-file://restored", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(4_096, "restored.bin").copy(url = "blob:https://example.com/restored"),
            )
        } finally {
            handler.close()
        }
        // A second handler sees the same persisted records, which is what a process restart does.
        val restarted = DownloadHandler(context)
        try {
            val item = restarted.downloads.value.first { it.filename == "restored.bin" }
            assertFalse(item.canResume)
            assertFalse(item.canPause)
            assertNull(restarted.retry(item.id))
        } finally {
            restarted.close()
        }
    }
    /**
     * The publish half must not run for a transfer that has been superseded.
     *
     * Pausing and retrying a task replaces its coroutine but keeps its id and its temporary
     * directory. A transfer that was already finishing then published its stale parts under the
     * retry's record, marked it COMPLETED, and deleted the directory the retry was writing into.
     */
    @Test fun aSupersededTransferDoesNotPublish() = runBlocking {
        val context = connectedContext()
        val handler = DownloadHandler(context)
        val source = FakeSource(ByteArray(2_000))
        try {
            val outcome = handler.enqueueOrGetExisting(
                url = "page-file://superseded", userAgent = null, contentDisposition = null, mimeType = null,
                pageFile = offer(2_000, "superseded.bin").copy(url = "blob:https://example.com/superseded"),
            )
            val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
            assertTrue(handler.startPageFile(id, offer(2_000), source))
            // A part that satisfies the declared size, so nothing but the ownership check can
            // decide the outcome: without that check these bytes would be published and the task
            // marked COMPLETED.
            val part = File(tempDirectory(context, id).apply { mkdirs() }, "part-0")
            part.writeBytes(ByteArray(2_000))
            // The job the pump reports with is the one it actually ran as; this is a different
            // one, which is what a retry leaves behind.
            val supersededJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
                .launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { }
            handler.onPageFileFinished(id, null, listOf(part), 2_000, owner = supersededJob)
            supersededJob.cancel()
            val item = handler.downloads.value.single { it.id == id }
            assertNotEquals("a superseded transfer must not publish", DownloadStatus.COMPLETED, item.status)
        } finally { handler.close() }
    }

}
