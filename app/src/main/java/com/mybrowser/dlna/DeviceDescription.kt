package com.mybrowser.dlna

import com.mybrowser.R
import android.content.res.Resources
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A renderer that is ready to be sent media. */
data class DlnaDevice(
    val usn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val controlUrl: String,
    /** Absent on renderers that do not implement RenderingControl; volume then stays fixed. */
    val renderingControlUrl: String?,
) {
    fun displayName(resources: Resources): String = friendlyName.ifBlank {
        manufacturer?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.ui_unnamed_device)
    }
}

/**
 * Fetches a device description document and extracts the AVTransport control URL.
 *
 * XmlPullParser rather than DOM: these documents come off the local network from
 * devices of wildly varying quality, and a pull parser cannot be talked into resolving
 * an external entity.
 */
object DeviceDescription {

    private const val TAG = "DeviceDescription"
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val CONNECT_TIMEOUT_MILLIS = 4_000
    private const val READ_TIMEOUT_MILLIS = 4_000
    private const val MAX_DOCUMENT_BYTES = 256 * 1024

    suspend fun fetch(reply: SsdpDiscovery.Reply): DlnaDevice? = withContext(Dispatchers.IO) {
        val body = runCatching { get(reply.location) }
            .onFailure { Log.w(TAG, "description fetch failed for ${reply.location}: ${it.message}") }
            .getOrNull()
            ?: return@withContext null

        runCatching { parse(body, reply) }
            .onFailure { Log.w(TAG, "description parse failed: ${it.message}") }
            .getOrNull()
    }

    private fun get(location: String): String {
        require(location.length <= MAX_URL_LENGTH)
        val connection = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "text/xml, application/xml")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}")
            }
            // Bounded read: a device claiming a huge Content-Length must not be able to
            // exhaust memory on the phone.
            return connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_DOCUMENT_BYTES) {
                        error("device description is too large")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(xml: String, reply: SsdpDiscovery.Reply): DlnaDevice? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }

        var friendlyName = ""
        var manufacturer: String? = null
        var avTransportPath: String? = null
        var renderingControlPath: String? = null

        // Service blocks are flat siblings, so the parse tracks which service it is inside
        // rather than building a tree.
        var serviceType: String? = null
        var serviceControlUrl: String? = null
        var inService = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "service" -> {
                        inService = true
                        serviceType = null
                        serviceControlUrl = null
                    }
                    "friendlyname" -> if (!inService) {
                        friendlyName = parser.nextText().trim().take(MAX_TEXT_LENGTH)
                    }
                    "manufacturer" -> if (!inService) {
                        manufacturer = parser.nextText().trim().take(MAX_TEXT_LENGTH)
                    }
                    "servicetype" -> if (inService) {
                        serviceType = parser.nextText().trim().take(MAX_TEXT_LENGTH)
                    }
                    "controlurl" -> if (inService) {
                        serviceControlUrl = parser.nextText().trim().take(MAX_URL_LENGTH)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("service", true)) {
                    val url = serviceControlUrl
                    when {
                        url.isNullOrBlank() -> Unit
                        serviceType.equals(AV_TRANSPORT, true) -> avTransportPath = url
                        serviceType.equals(RENDERING_CONTROL, true) -> renderingControlPath = url
                    }
                    inService = false
                }
            }
            event = parser.next()
        }

        val control = avTransportPath ?: return null
        return DlnaDevice(
            usn = reply.usn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            controlUrl = absolute(reply.location, control) ?: return null,
            renderingControlUrl = renderingControlPath?.let { absolute(reply.location, it) },
        )
    }

    /** Control URLs are usually relative to the description's location, but not always. */
    internal fun absolute(location: String, path: String): String? = runCatching {
        val resolved = URI(location).resolve(path)
        val scheme = resolved.scheme?.lowercase()
        require((scheme == "http" || scheme == "https") && !resolved.host.isNullOrBlank())
        resolved.toString().takeIf { it.length <= MAX_URL_LENGTH }
    }.getOrNull()

    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_TEXT_LENGTH = 512
}
