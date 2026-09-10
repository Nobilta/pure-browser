package com.mybrowser.dlna

import android.util.Log
import com.mybrowser.media.MediaSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * SOAP calls against a renderer's AVTransport service.
 *
 * Hand-rolled envelopes per the project's locked decision. Only the actions a browser
 * needs are here: hand over a URL, then basic transport control.
 */
object AvTransport {

    private const val TAG = "AvTransport"
    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 8_000

    /**
     * Points the renderer at [url] and starts it.
     *
     * Two calls, in this order, because a renderer will reject Play while its transport
     * URI is unset. Some also need a moment between the two, which is why a failed Play
     * is reported rather than retried — retrying tends to restart playback from zero on
     * devices that actually did accept it.
     */
    suspend fun playMedia(
        device: DlnaDevice,
        url: String,
        title: String,
        kind: MediaSniffer.Kind,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!isHttpEndpoint(cleanUrl)) {
            return@withContext Result.failure(IllegalArgumentException("media URL must be HTTP(S)"))
        }
        val metadata = didlLite(cleanUrl, title, kind)
        setAvTransportUri(device, cleanUrl, metadata).fold(
            onSuccess = { play(device) },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun setAvTransportUri(
        device: DlnaDevice,
        url: String,
        metadata: String,
    ): Result<Unit> = invoke(
        device.controlUrl,
        device.avTransportServiceType,
        "SetAVTransportURI",
        // CurrentURIMetaData must be XML-escaped inside the envelope: it is itself a
        // document, and an unescaped one silently breaks the whole SOAP body.
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
    )

    suspend fun play(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        device.avTransportServiceType,
        "Play",
        "<InstanceID>0</InstanceID><Speed>1</Speed>",
    )

    suspend fun pause(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        device.avTransportServiceType,
        "Pause",
        "<InstanceID>0</InstanceID>",
    )

    suspend fun stop(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        device.avTransportServiceType,
        "Stop",
        "<InstanceID>0</InstanceID>",
    )

    /** A missing service is an unsupported action, never a successful volume change. */
    suspend fun setVolume(device: DlnaDevice, volume: Int): Result<Unit> {
        val control = device.renderingControlUrl ?: return Result.failure(UnsupportedOperationException("Volume control unavailable"))
        return invoke(
            control,
            device.renderingServiceType,
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    private suspend fun invoke(controlUrl: String, serviceType: String, action: String, arguments: String): Result<Unit> =
        request(controlUrl, serviceType, action, arguments).map { Unit }

    private suspend fun request(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isHttpEndpoint(controlUrl)) {
            return@withContext Result.failure(IllegalArgumentException("control URL must be HTTP(S)"))
        }
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$serviceType">$arguments</u:$action></s:Body>
</s:Envelope>"""

        runCatching {
            val connection = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                // The quotes around SOAPACTION are required by the spec and enforced by
                // most renderers.
                setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
                setRequestProperty("Connection", "close")
            }
            try {
                connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = runCatching {
                        connection.errorStream?.use { it.readBoundedText(MAX_RESPONSE_BYTES) }
                    }.getOrNull()
                    error("$action failed: HTTP $code ${faultOf(detail)}")
                }
                // Some receivers send SOAP Fault with HTTP 200. Inspect a bounded
                // body before acknowledging the request or issuing Play.
                val body = connection.inputStream.use { it.readBoundedText(MAX_RESPONSE_BYTES) }
                if (Regex("<(?:[A-Za-z_][\\w.-]*:)?Fault\\b").containsMatchIn(body)) {
                    error("SOAP fault: " + faultOf(body))
                }
                Log.i(TAG, "$action ok")
                body
            } finally {
                connection.disconnect()
            }
        }
    }

    data class PlaybackStatus(
        val transportState: String,
        val positionSeconds: Long? = null,
        val durationSeconds: Long? = null,
        val volume: Int? = null,
    )

    suspend fun status(device: DlnaDevice): Result<PlaybackStatus> {
        val transport = request(device.controlUrl, device.avTransportServiceType,
            "GetTransportInfo", "<InstanceID>0</InstanceID>")
        if (transport.isFailure) return Result.failure(requireNotNull(transport.exceptionOrNull()))
        return try {
            val state = fields(transport.getOrThrow())["CurrentTransportState"]
                ?.takeIf { it in setOf("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING", "NO_MEDIA_PRESENT") }
                ?: error("Missing transport state")
            val position = request(device.controlUrl, device.avTransportServiceType,
                "GetPositionInfo", "<InstanceID>0</InstanceID>").getOrNull()?.let { runCatching { fields(it) }.getOrNull() }
            val volume = device.renderingControlUrl?.let { control ->
                request(control, device.renderingServiceType, "GetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel>").getOrNull()
                    ?.let { runCatching { fields(it)["CurrentVolume"]?.toIntOrNull()?.takeIf { value -> value in 0..100 } }.getOrNull() }
            }
            Result.success(PlaybackStatus(state, parseTime(position?.get("RelTime")), parseTime(position?.get("TrackDuration")), volume))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun seek(device: DlnaDevice, seconds: Long): Result<Unit> = invoke(
        device.controlUrl, device.avTransportServiceType, "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatTime(seconds)}</Target>")

    internal fun parseTime(value: String?): Long? {
        val parts = value?.substringBefore('.')?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull()?.takeIf { it in 0..9999 } ?: return null
        val minutes = parts[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        val seconds = parts[2].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
        return hours * 3600 + minutes * 60 + seconds
    }

    internal fun formatTime(seconds: Long): String {
        val value = seconds.coerceIn(0, 35_999_999)
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
    }

    private fun fields(xml: String): Map<String, String> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true))
        val parser = android.util.Xml.newPullParser().apply {
            setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(xml.reader())
        }
        val names = setOf("CurrentTransportState", "RelTime", "TrackDuration", "CurrentVolume")
        val result = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name in names) result[parser.name] = parser.nextText().trim()
            event = parser.next()
        }
        return result
    }

    /** Pulls the UPnP error out of a SOAP fault so the toast says something useful. */
    private fun faultOf(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val code = Regex("<errorCode>(\\d+)</errorCode>").find(body)?.groupValues?.get(1)
        val description = Regex("<errorDescription>([^<]*)</errorDescription>")
            .find(body)?.groupValues?.get(1)
        return listOfNotNull(code, description).joinToString(" ").trim()
    }

    private fun InputStream.readBoundedText(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        check(total < maxBytes || read() < 0) { "SOAP response is too large" }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Minimal DIDL-Lite. Renderers vary from ignoring this entirely to refusing to play
     * without it, so it is always sent.
     */
    internal fun didlLite(url: String, title: String, kind: MediaSniffer.Kind): String {
        val mime = mediaType(url, kind)
        val stream = kind == MediaSniffer.Kind.HLS || kind == MediaSniffer.Kind.DASH
        val protocol = "http-get:*:" + mime + ":DLNA.ORG_OP=" + (if (stream) "00" else "01") +
            ";DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val itemClass = if (kind == MediaSniffer.Kind.AUDIO) "object.item.audioItem" else "object.item.videoItem"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + escape(title) +
            "</dc:title><upnp:class>" + itemClass + "</upnp:class><res protocolInfo=\"" + protocol +
            "\">" + escape(url) + "</res></item></DIDL-Lite>"
    }

    internal fun mediaType(url: String, kind: MediaSniffer.Kind): String {
        if (kind == MediaSniffer.Kind.HLS) return "application/x-mpegURL"
        if (kind == MediaSniffer.Kind.DASH) return "application/dash+xml"
        val ext = runCatching { URI(url).path.orEmpty().substringAfterLast('.').lowercase() }.getOrDefault("")
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "mpg", "mpeg" -> "video/mpeg"
            "wmv" -> "video/x-ms-wmv"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "*"
        }
    }

    internal fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun isHttpEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() &&
            value.length <= MAX_URL_LENGTH
    }.getOrDefault(false)

    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_RESPONSE_BYTES = 256 * 1024
}
