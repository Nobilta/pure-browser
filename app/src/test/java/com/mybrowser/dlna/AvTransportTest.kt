package com.mybrowser.dlna

import android.app.Application
import com.mybrowser.media.MediaSniffer
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AvTransportTest {
    @Test fun playbackStatusAndRemoteActionsUseTheDeviceSoapEndpoints() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        server.createContext("/control") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += action to body
            val fields = when (action) {
                "GetTransportInfo" -> "<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"
                "GetPositionInfo" -> "<RelTime>00:01:09.500</RelTime><TrackDuration>01:02:03</TrackDuration>"
                "GetVolume" -> "<CurrentVolume>35</CurrentVolume>"
                else -> ""
            }
            val bytes = ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" + fields + "</s:Body></s:Envelope>").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val endpoint = "http://127.0.0.1:" + server.address.port + "/control"
        val device = DlnaDevice("uuid:tv", "TV", null, endpoint, endpoint)
        try {
            assertEquals(AvTransport.PlaybackStatus("PAUSED_PLAYBACK", 69, 3723, 35), AvTransport.status(device).getOrThrow())
            assertTrue(AvTransport.seek(device, 3661).isSuccess)
            assertTrue(AvTransport.setVolume(device, 42).isSuccess)
            assertTrue(AvTransport.pause(device).isSuccess)
            assertTrue(AvTransport.play(device).isSuccess)
            assertTrue(AvTransport.stop(device).isSuccess)
            assertTrue(requests.single { it.first == "Seek" }.second.contains("<Target>01:01:01</Target>"))
            assertTrue(requests.single { it.first == "SetVolume" }.second.contains("<DesiredVolume>42</DesiredVolume>"))
        } finally { server.stop(0) }
    }

    @Test fun missingRenderingControlIsUnsupportedAndInvalidDurationsAreNotSeekable() = runBlocking {
        val device = DlnaDevice("uuid:tv", "TV", null, "http://127.0.0.1/control", null)
        assertTrue(AvTransport.setVolume(device, 50).isFailure)
        listOf(null, "NOT_IMPLEMENTED", "-1:00:00", "00:60:01", "00:00:60", "bad").forEach { assertNull(AvTransport.parseTime(it)) }
        assertEquals(0L, AvTransport.parseTime("00:00:00"))
        assertEquals("00:00:00", AvTransport.formatTime(-1))
    }

    @Test fun malformedOrOversizedStatusNeverClaimsPlayback() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var response = "<!DOCTYPE x><CurrentTransportState>PLAYING</CurrentTransportState>"
        server.createContext("/control") { exchange ->
            exchange.requestBody.close()
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { exchange.responseBody.use { it.write(bytes) } }
        }
        server.start()
        val device = DlnaDevice("uuid:tv", "TV", null, "http://127.0.0.1:" + server.address.port + "/control", null)
        try {
            assertTrue(AvTransport.status(device).isFailure)
            response = "x".repeat(256 * 1024 + 1)
            assertTrue(AvTransport.status(device).isFailure)
        } finally { server.stop(0) }
    }

    @Test fun metadataSeparatesDashHlsAudioAndVideoAndEscapesSignedUrls() {
        val dash = AvTransport.didlLite("https://example.com/a.mpd?x=1&y=2", "a < b", MediaSniffer.Kind.DASH)
        assertTrue(dash.contains("application/dash+xml"))
        assertTrue(dash.contains("DLNA.ORG_OP=00"))
        assertTrue(dash.contains("x=1&amp;y=2"))
        assertTrue(dash.contains("a &lt; b"))
        assertTrue(AvTransport.didlLite("https://example.com/a.m3u8", "Live", MediaSniffer.Kind.HLS).contains("application/x-mpegURL"))
        val audio = AvTransport.didlLite("https://example.com/a.mp3", "Audio", MediaSniffer.Kind.AUDIO)
        assertTrue(audio.contains("audio/mpeg"))
        assertTrue(audio.contains("object.item.audioItem"))
        assertEquals("video/webm", AvTransport.mediaType("https://example.com/a.webm?format=mp4", MediaSniffer.Kind.PROGRESSIVE))
        assertEquals("*", AvTransport.mediaType("https://example.com/media?id=1", MediaSniffer.Kind.PROGRESSIVE))
    }

    @Test fun soapUsesAdvertisedVersionAndStopsBeforePlayOnFault() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val headers = mutableListOf<String>()
        var fault = false
        server.createContext("/control") { exchange ->
            headers += exchange.requestHeaders.getFirst("SOAPACTION")
            exchange.requestBody.close()
            val response = if (fault) "<s:Envelope><s:Body><s:Fault><errorCode>701</errorCode></s:Fault></s:Body></s:Envelope>" else ""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val device = DlnaDevice("uuid:tv", "TV", null, "http://127.0.0.1:" + server.address.port + "/control", null,
            avTransportServiceType = "urn:schemas-upnp-org:service:AVTransport:2")
        try {
            assertTrue(AvTransport.playMedia(device, "https://example.com/video.mp4", "Video", MediaSniffer.Kind.PROGRESSIVE).isSuccess)
            assertEquals(2, headers.size)
            assertTrue(headers[0].contains("AVTransport:2#SetAVTransportURI"))
            assertTrue(headers[1].contains("AVTransport:2#Play"))
            headers.clear()
            fault = true
            assertTrue(AvTransport.playMedia(device, "https://example.com/video.mp4", "Video", MediaSniffer.Kind.PROGRESSIVE).isFailure)
            assertEquals(1, headers.size)
        } finally { server.stop(0) }
    }
}
