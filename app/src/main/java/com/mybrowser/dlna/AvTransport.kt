package com.mybrowser.dlna

import android.util.Log
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
    private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
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
        isStream: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!isHttpEndpoint(cleanUrl)) {
            return@withContext Result.failure(IllegalArgumentException("media URL must be HTTP(S)"))
        }
        val metadata = didlLite(cleanUrl, title, isStream)
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
        SERVICE,
        "SetAVTransportURI",
        // CurrentURIMetaData must be XML-escaped inside the envelope: it is itself a
        // document, and an unescaped one silently breaks the whole SOAP body.
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
    )

    suspend fun play(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        SERVICE,
        "Play",
        "<InstanceID>0</InstanceID><Speed>1</Speed>",
    )

    suspend fun pause(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        SERVICE,
        "Pause",
        "<InstanceID>0</InstanceID>",
    )

    suspend fun stop(device: DlnaDevice): Result<Unit> = invoke(
        device.controlUrl,
        SERVICE,
        "Stop",
        "<InstanceID>0</InstanceID>",
    )

    /** No-ops on renderers without RenderingControl rather than failing. */
    suspend fun setVolume(device: DlnaDevice, volume: Int): Result<Unit> {
        val control = device.renderingControlUrl ?: return Result.success(Unit)
        return invoke(
            control,
            RENDERING,
            "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        )
    }

    private suspend fun invoke(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
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
                // SOAP success bodies are not needed after the status code. Drain only a
                // bounded amount so a faulty LAN device cannot force an unbounded
                // allocation while the connection is being closed.
                connection.inputStream.use { it.drainBounded(MAX_RESPONSE_BYTES) }
                Log.i(TAG, "$action ok")
                Unit
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Pulls the UPnP error out of a SOAP fault so the toast says something useful. */
    private fun faultOf(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val code = Regex("<errorCode>(\\d+)</errorCode>").find(body)?.groupValues?.get(1)
        val description = Regex("<errorDescription>([^<]*)</errorDescription>")
            .find(body)?.groupValues?.get(1)
        return listOfNotNull(code, description).joinToString(" ").trim()
    }

    private fun InputStream.drainBounded(maxBytes: Int) {
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            remaining -= read
        }
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
        val suffix = if (total == maxBytes) "…" else ""
        return output.toString(Charsets.UTF_8.name()) + suffix
    }

    /**
     * Minimal DIDL-Lite. Renderers vary from ignoring this entirely to refusing to play
     * without it, so it is always sent.
     */
    internal fun didlLite(url: String, title: String, isStream: Boolean): String {
        // The protocolInfo DLNA.ORG_OP flag differs by kind: streams are not seekable by
        // byte range, and claiming otherwise makes some renderers fail the request.
        val protocolInfo = if (isStream) {
            "http-get:*:application/x-mpegURL:DLNA.ORG_OP=00;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        } else {
            "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocolInfo">${escape(url)}</res></item></DIDL-Lite>"""
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
