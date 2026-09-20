package com.mybrowser.download

import android.app.Application
import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Dedup semantics of the confirmation gate: one dialog per request, one task per
 * identity, bounded interception for everything else. A slow local server keeps the
 * tasks alive so status checks are deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadDedupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private lateinit var handler: DownloadHandler
    private lateinit var coordinator: DownloadRequestCoordinator

    @Before fun setUp() {
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().clear().commit()
        // Robolectric starts with no usable network; the engine would sit in WAITING_NETWORK.
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val connectivityShadow = org.robolectric.Shadows.shadowOf(connectivity)
        connectivityShadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        connectivityShadow.setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.createContext("/file") { exchange ->
            val total = 4 * 1024 * 1024
            exchange.responseHeaders.add("ETag", "\"dedup-stable\"")
            exchange.sendResponseHeaders(200, total.toLong())
            runCatching { exchange.responseBody.use { output ->
                val block = ByteArray(16384) { 7 }
                var remaining = total
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    output.write(block, 0, count); output.flush(); remaining -= count
                    Thread.sleep(20)
                }
            } }
        }
        server.start()
        handler = DownloadHandler(context)
        coordinator = DownloadRequestCoordinator(handler)
    }

    @After fun tearDown() {
        handler.close()
        server.stop(0)
    }

    private fun url(suffix: String = "") = "http://127.0.0.1:${server.address.port}/file$suffix"

    private fun request(target: String, name: String = "file.bin", isPrivate: Boolean = false) =
        DownloadRequestCoordinator.Request(
            url = target, filename = name, mimeType = "application/octet-stream", userAgent = null,
            contentDisposition = null, referer = "https://example.com/page", isPrivate = isPrivate,
            cookieHeader = null, sourceOrigin = "https://example.com",
        )

    private suspend fun awaitDownloading(id: Long) {
        withTimeout(10_000) { handler.downloads.first { list -> list.any { it.id == id && it.bytesDownloaded > 0 } } }
    }

    @Test fun repeatedSubmitsCollapseIntoOneConfirmationAndOneTask() = runBlocking {
        // Ten rapid callbacks for the same URL: one dialog, nothing else.
        repeat(10) { index ->
            val result = coordinator.submit(request(url()))
            when (index) {
                0 -> assertTrue(result is DownloadRequestCoordinator.SubmitResult.Confirm)
                else -> assertTrue("index $index gave $result", result is DownloadRequestCoordinator.SubmitResult.Suppressed)
            }
        }
        assertEquals(0, coordinator.blocked.value.size)
        val outcome = coordinator.confirmPending()
        assertTrue(outcome is DownloadHandler.EnqueueOutcome.Started)
        val id = (outcome as DownloadHandler.EnqueueOutcome.Started).id
        awaitDownloading(id)
        // Repeats now point at the existing task: reported once, silent afterwards.
        assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Existing)
        repeat(5) { assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Suppressed) }
        // Still exactly one task for this URL in the normal context.
        assertEquals(1, handler.downloads.value.count { it.url == url() })
    }

    @Test fun rejectedRequestsStaySilentUntilExplicitlyUnblocked() = runBlocking {
        assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Confirm)
        coordinator.rejectPending()
        repeat(5) { assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Suppressed) }
        val blocked = coordinator.blocked.value
        assertEquals(1, blocked.size)
        assertEquals(DownloadRequestCoordinator.BlockedReason.REJECTED, blocked.single().reason)
        // The explicit retry path removes the rejection and shows the dialog again.
        val unblocked = coordinator.requestBlocked(blocked.single().identity) { null }
        assertTrue(unblocked is DownloadRequestCoordinator.SubmitResult.Confirm)
    }

    @Test fun newRequestsWhileADialogIsUpAreInterceptedAndBounded() = runBlocking {
        assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Confirm)
        val others = (1..7).map { request(url("?variant=$it"), name = "file-$it.bin") }
        others.forEach { result ->
            assertTrue(result.let { coordinator.submit(it) } is DownloadRequestCoordinator.SubmitResult.Intercepted)
        }
        assertEquals(5, coordinator.blocked.value.size)
        // The page leaving clears pending, blocked and rejected state together.
        coordinator.resetTransientState()
        assertNull(coordinator.pending.value)
        assertTrue(coordinator.blocked.value.isEmpty())
        assertTrue(coordinator.submit(request(url("?variant=1"))) is DownloadRequestCoordinator.SubmitResult.Confirm)
    }

    @Test fun automaticDialogBudgetRunsOutAndNotifiesOnce() = runBlocking {
        // Three automatic dialogs per document, each answered before the next arrives.
        repeat(3) { index ->
            val result = coordinator.submit(request(url("?auto=$index"), name = "auto-$index.bin"))
            assertTrue("index $index gave $result", result is DownloadRequestCoordinator.SubmitResult.Confirm)
            assertTrue(coordinator.confirmPending() is DownloadHandler.EnqueueOutcome.Started)
        }
        // The fourth new identity gets no automatic dialog: intercepted with BUDGET.
        val intercepted = coordinator.submit(request(url("?auto=3"), name = "auto-3.bin"))
        assertTrue(intercepted is DownloadRequestCoordinator.SubmitResult.Intercepted)
        assertTrue((intercepted as DownloadRequestCoordinator.SubmitResult.Intercepted).firstNotice)
        assertEquals(1, coordinator.blocked.value.size)
        assertEquals(DownloadRequestCoordinator.BlockedReason.BUDGET, coordinator.blocked.value.single().reason)
        // Further intercepts stay quiet until the list first overflows.
        val quiet = coordinator.submit(request(url("?auto=4"), name = "auto-4.bin"))
        assertTrue(quiet is DownloadRequestCoordinator.SubmitResult.Intercepted)
        assertFalse((quiet as DownloadRequestCoordinator.SubmitResult.Intercepted).firstNotice)
        assertEquals(2, coordinator.blocked.value.size)
        // The user's explicit pick bypasses the exhausted budget with a fresh dialog.
        val picked = coordinator.requestBlocked(coordinator.blocked.value.first().identity) { null }
        assertTrue(picked is DownloadRequestCoordinator.SubmitResult.Confirm)
    }

    @Test fun identitySeparatesPrivateContextsAndMergesOnlyFragments() = runBlocking {
        val normal = handler.enqueueOrGetExisting(url(), null, null, "application/octet-stream")
        val normalId = (normal as DownloadHandler.EnqueueOutcome.Started).id
        awaitDownloading(normalId)
        // Same URL and fragment-only variants merge into the existing task.
        assertTrue(handler.enqueueOrGetExisting(url(), null, null, null) is DownloadHandler.EnqueueOutcome.Existing)
        assertTrue(handler.enqueueOrGetExisting(url("#section"), null, null, null) is DownloadHandler.EnqueueOutcome.Existing)
        // A different query is a different resource; the private context never merges.
        val otherVariant = handler.enqueueOrGetExisting(url("?variant=2"), null, null, null)
        assertTrue(otherVariant is DownloadHandler.EnqueueOutcome.Started)
        val privateTask = handler.enqueueOrGetExisting(url(), null, null, null, isPrivate = true)
        assertTrue(privateTask is DownloadHandler.EnqueueOutcome.Started)
        assertNotEquals(normalId, (privateTask as DownloadHandler.EnqueueOutcome.Started).id)
    }

    @Test fun previewFilenameMatchesTheEngineSanitization() {
        assertEquals("file.bin", handler.previewFilename(url(), null, "application/octet-stream"))
        assertNull(handler.previewFilename("javascript:alert(1)", null, null))
    }
    @Test fun budgetsPendingConfirmationsAndBlockedListsFollowTheirOwnTabDocument() {
        coordinator.selectTab("A")
        repeat(3) { index -> coordinator.submit(request(url("?a=$index"))); coordinator.rejectPending() }
        assertTrue(coordinator.submit(request(url("?a=4"))) is DownloadRequestCoordinator.SubmitResult.Intercepted)
        val blockedA = coordinator.blocked.value
        coordinator.selectTab("B")
        assertTrue(coordinator.blocked.value.isEmpty())
        assertTrue(coordinator.submit(request(url("?b=1"))) is DownloadRequestCoordinator.SubmitResult.Confirm)
        coordinator.selectTab("A")
        assertNull(coordinator.pending.value)
        assertEquals(blockedA, coordinator.blocked.value)
        assertTrue(coordinator.submit(request(url("?a=5"))) is DownloadRequestCoordinator.SubmitResult.Intercepted)
        coordinator.startDocument("A")
        assertTrue(coordinator.blocked.value.isEmpty())
        assertTrue(coordinator.submit(request(url("?a=new"))) is DownloadRequestCoordinator.SubmitResult.Confirm)
        coordinator.selectTab("B")
        assertEquals(url("?b=1"), coordinator.pending.value!!.url)
        coordinator.retainTabs(setOf("A"))
        coordinator.selectTab("B")
        assertNull(coordinator.pending.value)
    }

    @Test fun overflowHasAnExplicitCountAndRecoversAfterAnEntryIsRemoved() {
        coordinator.submit(request(url()))
        (1..7).forEach { coordinator.submit(request(url("?overflow=$it"))) }
        assertEquals(5, coordinator.blocked.value.size)
        assertEquals(2, coordinator.overflow.value)
        coordinator.removeBlocked(url("?overflow=1"))
        coordinator.submit(request(url("?overflow=6")))
        assertTrue(coordinator.blocked.value.any { it.identity == url("?overflow=6") })
        coordinator.resetTransientState()
        assertEquals(0, coordinator.overflow.value)
    }

    @Test fun explicitBlockedRetryDoesNotAuthorizeFutureAutomaticEvents() {
        repeat(3) { index -> coordinator.submit(request(url("?limit=$index"))); coordinator.rejectPending() }
        val target = request(url("?explicit=1")).copy(contentLength = 12345)
        coordinator.submit(target)
        assertTrue(coordinator.requestBlocked(target.identity) { "fresh=1" } is DownloadRequestCoordinator.SubmitResult.Confirm)
        assertEquals(12345L, coordinator.pending.value!!.contentLength)
        assertEquals("fresh=1", coordinator.pending.value!!.cookieHeader)
        coordinator.rejectPending()
        assertTrue(coordinator.submit(target) is DownloadRequestCoordinator.SubmitResult.Suppressed)
    }

    @Test fun completedUrlKeepsAnExplicitNewCopyActionWithoutDeletingTheOldTask() {
        handler.close()
        val entry = org.json.JSONObject().put("id", -99).put("backend", "LOCAL").put("url", url())
            .put("filename", "old.bin").put("status", "COMPLETED").put("autoResumeAllowed", true)
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("entries", org.json.JSONArray().put(entry).toString()).commit()
        handler = DownloadHandler(context)
        coordinator = DownloadRequestCoordinator(handler)
        assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Existing)
        assertTrue(coordinator.submit(request(url())) is DownloadRequestCoordinator.SubmitResult.Suppressed)
        assertEquals(DownloadRequestCoordinator.BlockedReason.EXISTING, coordinator.blocked.value.single().reason)
        assertTrue(coordinator.requestBlocked(url()) { null } is DownloadRequestCoordinator.SubmitResult.Confirm)
        assertTrue(coordinator.confirmPending() is DownloadHandler.EnqueueOutcome.Started)
        assertTrue(handler.downloads.value.any { it.id == -99L && it.status == DownloadStatus.COMPLETED })
    }

}
