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
        controller.cast(candidate, device, onResult = messages::add)
        controller.cast(candidate, device, onResult = messages::add)
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

    @Test fun startPositionForIgnoresWhatIsNotWorthHandingOver() = runTest {
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.success(Unit) }
        // Nothing played, nothing to continue.
        assertNull(controller.startPositionFor(0.0, 3_600.0))
        assertNull(controller.startPositionFor(9.9, 3_600.0))
        assertEquals(10L, controller.startPositionFor(10.0, 3_600.0))
        // A live stream reports no duration: the renderer's own edge is the right place to be.
        assertNull(controller.startPositionFor(600.0, 0.0))
        assertNull(controller.startPositionFor(600.0, Double.NaN))
        assertNull(controller.startPositionFor(Double.POSITIVE_INFINITY, 3_600.0))
        // A position past the end (a page that changed streams) is clamped, not handed over as is.
        assertEquals(3_600L, controller.startPositionFor(99_999.0, 3_600.0))
        controller.close()
    }

    @Test fun aCastHandsOffWhereThePhoneWas() = kotlinx.coroutines.runBlocking {
        // Both states a seek is legal in, including the one a user reaches by pausing the TV while
        // the cast is still settling.
        for (transportState in listOf("PLAYING", "PAUSED_PLAYBACK")) {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            val actions = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val seeks = java.util.concurrent.ConcurrentLinkedQueue<String>()
            server.createContext("/control") { exchange ->
                val action = exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
                val body = exchange.requestBody.readBytes().decodeToString()
                actions += action
                if (action == "Seek") seeks += body
                val response = when (action) {
                    "GetTransportInfo" -> "<CurrentTransportState>$transportState</CurrentTransportState>"
                    else -> ""
                }
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            server.start()
            val receiver = device.copy(controlUrl = "http://127.0.0.1:${server.address.port}/control")
            val messages = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.success(Unit) }
            try {
                controller.cast(candidate, receiver, startAtSeconds = 42) { messages += it }
                kotlinx.coroutines.withTimeout(5_000) { while (seeks.isEmpty()) kotlinx.coroutines.delay(10) }
                assertEquals(receiver, controller.connected)
                // The send itself is faked in this test (see the constructor), so what is asserted
                // here is the hand-off: it happens, and it asks for REL_TIME at the phone's position.
                assertTrue(actions.toString(), actions.contains("Seek"))
                val seek = seeks.single()
                assertTrue(seek, seek.contains("<Unit>REL_TIME</Unit>"))
                assertTrue(seek, seek.contains("<Target>00:00:42</Target>"))
                assertFalse(messages.toString(), messages.any {
                    it == RuntimeEnvironment.getApplication().getString(com.mybrowser.R.string.cast_seek_failed)
                })
            } finally { controller.close(); server.stop(0) }
        }
    }

    @Test fun aRefusedStartPositionIsReportedAndTheCastStillStands() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/control") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            exchange.requestBody.close()
            if (action == "Seek") {
                val fault = "<s:Fault><detail><UPnPError><errorCode>710</errorCode>" +
                    "<errorDescription>Seek mode not supported</errorDescription></UPnPError></detail></s:Fault>"
                exchange.sendResponseHeaders(200, fault.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(fault.toByteArray()) }
            } else {
                val response = if (action == "GetTransportInfo")
                    "<CurrentTransportState>PLAYING</CurrentTransportState>" else ""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
        }
        server.start()
        val receiver = device.copy(controlUrl = "http://127.0.0.1:${server.address.port}/control")
        val messages = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.success(Unit) }
        try {
            controller.cast(candidate, receiver, startAtSeconds = 42) { messages += it }
            kotlinx.coroutines.withTimeout(5_000) { while (messages.none { it != null && it.contains("skip") }) kotlinx.coroutines.delay(10) }
            // Refusing to jump is not a failed cast: the stream is on the device either way.
            assertEquals(receiver, controller.connected)
            assertTrue(messages.toString(), messages.any { it == RuntimeEnvironment.getApplication()
                .getString(com.mybrowser.R.string.cast_seek_failed) })
        } finally { controller.close(); server.stop(0) }
    }

    @Test fun aRendererThatNeverPlaysIsReportedRatherThanWaitedOnForever() = runTest {
        val messages = mutableListOf<String>()
        val controller = CastController(RuntimeEnvironment.getApplication(), this, readStatus = {
            Result.success(AvTransport.PlaybackStatus("TRANSITIONING"))
        }) { _, _ -> Result.success(Unit) }
        controller.cast(candidate, device, startAtSeconds = 42) { messages += it }
        advanceTimeBy(60_000)
        runCurrent()
        // The cast itself still reports, then one sentence about the start position — not a second
        // attempt that would fail the same way.
        assertEquals(messages.toString(), 2, messages.size)
        assertEquals(RuntimeEnvironment.getApplication().getString(com.mybrowser.R.string.cast_seek_failed),
            messages.last())
        controller.close()
    }

    @Test fun aSkipOrASeekSaysWhyItCannotRatherThanDoingNothing() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val seeks = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/control") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            val body = exchange.requestBody.readBytes().decodeToString()
            if (action == "Seek") seeks += body
            val response = when (action) {
                "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                "GetPositionInfo" -> "<RelTime>00:01:35</RelTime><TrackDuration>00:01:40</TrackDuration>"
                else -> ""
            }
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        val receiver = device.copy(controlUrl = "http://127.0.0.1:${server.address.port}/control")
        val controller = CastController(RuntimeEnvironment.getApplication(), this) { _, _ -> Result.success(Unit) }
        try {
            // Without a reported position there is nothing to skip from, and silence would read as a
            // dead button.
            val silent = mutableListOf<String>()
            controller.seek(10) { silent += it }
            controller.skip(10) { silent += it }
            assertEquals(2, silent.size)
            assertTrue(silent.toString(), silent.all { it == RuntimeEnvironment.getApplication()
                .getString(com.mybrowser.R.string.cast_seek_unknown) })

            controller.cast(candidate, receiver) {}
            controller.setVisible(true)
            kotlinx.coroutines.withTimeout(5_000) { while (controller.state.value.playback?.positionSeconds != 95L) kotlinx.coroutines.delay(10) }
            assertEquals(95L, controller.state.value.playback?.positionSeconds)
            controller.skip(10) {}
            kotlinx.coroutines.withTimeout(5_000) { while (seeks.isEmpty()) kotlinx.coroutines.delay(10) }
            // 95 + 10 runs past the reported end, so the request is for the end.
            assertTrue(seeks.single(), seeks.single().contains("<Target>00:01:40</Target>"))
        } finally { controller.close(); server.stop(0) }
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
