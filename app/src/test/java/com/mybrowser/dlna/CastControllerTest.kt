package com.mybrowser.dlna

import android.app.Application
import com.mybrowser.media.MediaSniffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CastControllerTest {
    private val device = DlnaDevice("uuid:tv", "TV", null, "http://127.0.0.1/control", null)
    private val candidate = MediaSniffer.Candidate("https://example.com/video.mp4", MediaSniffer.Kind.PROGRESSIVE, "video", null)

    @Test fun hidingStopsPollingAndLateStatusCannotOverwriteTheNewSession() = runTest {
        val late = CompletableDeferred<Result<AvTransport.PlaybackStatus>>()
        var reads = 0
        val controller = CastController(RuntimeEnvironment.getApplication(), this, readStatus = {
            reads++
            if (reads == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { late.await() }
            else Result.success(AvTransport.PlaybackStatus("PLAYING"))
        }) { _, _ -> Result.success(Unit) }
        controller.cast(candidate, device) {}
        runCurrent()
        controller.setVisible(true)
        runCurrent()
        assertEquals(1, reads)
        controller.setVisible(false)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, reads)
        controller.setVisible(true)
        runCurrent()
        assertEquals("PLAYING", controller.state.value.playback?.transportState)
        late.complete(Result.success(AvTransport.PlaybackStatus("STOPPED")))
        runCurrent()
        assertEquals("PLAYING", controller.state.value.playback?.transportState)
        controller.close()
    }

    @Test fun remoteControlsAreSerializedAndDisconnectDoesNotSendStop() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val actions = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/control") { exchange ->
            actions += exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            exchange.requestBody.close()
            Thread.sleep(40)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val receiver = device.copy(controlUrl = "http://127.0.0.1:${server.address.port}/control")
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.success(Unit) }
        try {
            controller.cast(candidate, receiver) {}
            kotlinx.coroutines.yield()
            assertEquals(receiver, controller.connected)
            controller.pause {}
            controller.resume {}
            controller.pause {}
            kotlinx.coroutines.withTimeout(5_000) {
                while (controller.state.value.isControlling) kotlinx.coroutines.delay(10)
            }
            assertEquals(listOf("Pause"), actions.toList())
            controller.disconnect()
            assertNull(controller.connected)
            assertEquals(listOf("Pause"), actions.toList())
        } finally { controller.close(); server.stop(0) }
    }

    @Test fun connectionRequiresTransportAcknowledgementAndDuplicateSendsAreIgnored() = runTest {
        val response = CompletableDeferred<Result<Unit>>()
        var calls = 0
        val messages = mutableListOf<String>()
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> calls++; response.await() }
        controller.cast(candidate, device, messages::add)
        controller.cast(candidate, device, messages::add)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(controller.state.value.isCasting)
        assertNull(controller.connected)
        response.complete(Result.success(Unit))
        runCurrent()
        assertEquals(device, controller.connected)
        assertFalse(controller.state.value.isCasting)
        assertEquals(1, messages.size)
        controller.close()
    }

    @Test fun failedPlaybackDoesNotClaimConnection() = runTest {
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.failure(IOException("fixture")) }
        controller.cast(candidate, device) {}
        runCurrent()
        assertNull(controller.connected)
        assertFalse(controller.state.value.isCasting)
        assertNotNull(controller.lastError)
        controller.close()
    }

    @Test fun closingControllerCancelsPendingRequestsAndSuppressesLateReplies() = runTest {
        val response = CompletableDeferred<Result<Unit>>()
        var delivered = false
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> response.await() }
        controller.cast(candidate, device) { delivered = true }
        runCurrent()
        controller.close()
        response.complete(Result.success(Unit))
        runCurrent()
        assertFalse(delivered)
        assertNull(controller.connected)
        assertFalse(controller.state.value.isCasting)
    }
}
