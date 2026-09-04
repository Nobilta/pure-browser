package com.mybrowser.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * SSDP M-SEARCH discovery for AVTransport renderers.
 *
 * Hand-rolled per the project's locked decision. The protocol is small enough that a
 * library is not worth the dependency: send an M-SEARCH datagram to the multicast
 * group, read unicast replies for a few seconds, keep the ones advertising AVTransport.
 *
 * Deliberately not joining the multicast group as a receiver — replies to M-SEARCH are
 * unicast back to the sending port, so a plain [DatagramSocket] is enough and avoids
 * the multicast-lock and interface-selection problems that come with MulticastSocket on
 * Android.
 */
object SsdpDiscovery {

    /** A device that answered, before its description has been fetched. */
    data class Reply(
        val location: String,
        val usn: String,
        val server: String?,
        val searchTarget: String,
    )

    private const val TAG = "SsdpDiscovery"
    private const val MULTICAST_HOST = "239.255.255.250"
    private const val MULTICAST_PORT = 1900
    private const val MX_SECONDS = 3

    /**
     * Two search targets. MediaRenderer is the correct one, but a fair number of TVs and
     * receivers only answer to ssdp:all, so both go out.
     */
    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
    )

    /**
     * Emits replies as they arrive, deduplicated by USN, until [timeoutMillis] elapses or
     * the collector is cancelled.
     */
    fun search(timeoutMillis: Long = 6_000): Flow<Reply> = flow {
        val seen = mutableSetOf<String>()
        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS

            val group = InetAddress.getByName(MULTICAST_HOST)
            for (target in SEARCH_TARGETS) {
                val message = mSearch(target)
                val bytes = message.toByteArray(Charsets.US_ASCII)
                // Sent twice: SSDP runs over UDP with no retransmission of its own, and a
                // single lost datagram means a device silently never appears.
                repeat(2) {
                    runCatching {
                        socket.send(
                            DatagramPacket(bytes, bytes.size, InetSocketAddress(group, MULTICAST_PORT)),
                        )
                    }.onFailure { Log.w(TAG, "M-SEARCH send failed: ${it.message}") }
                }
            }

            withTimeoutOrNull(timeoutMillis.coerceIn(1L, MAX_SEARCH_TIMEOUT_MILLIS)) {
                val buffer = ByteArray(2048)
                while (coroutineContext.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val reply = parse(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        ?: continue
                    if (seen.add(reply.usn)) {
                        Log.i(TAG, "found ${reply.usn} at ${reply.location}")
                        emit(reply)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun mSearch(searchTarget: String): String =
        // CRLF line endings and the trailing blank line are mandatory; devices drop
        // malformed requests without a word.
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $MX_SECONDS\r\n" +
            "ST: $searchTarget\r\n" +
            "\r\n"

    internal fun parse(response: String): Reply? {
        val lines = response.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        // Accept both the M-SEARCH reply and an unsolicited NOTIFY advertisement.
        val start = lines.first().uppercase()
        if (!start.startsWith("HTTP/1.1 200") && !start.startsWith("NOTIFY")) return null

        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) return@mapNotNull null
            line.substring(0, index).trim().uppercase() to line.substring(index + 1).trim()
        }.toMap()

        val location = headers["LOCATION"]?.trim()?.takeIf { value ->
            value.length <= MAX_URL_LENGTH &&
            runCatching {
                val uri = URI(value)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        } ?: return null
        val target = headers["ST"] ?: headers["NT"] ?: return null
        // USN is the stable identity; LOCATION can change across reboots.
        val usn = headers["USN"]?.takeIf { it.isNotBlank() } ?: location

        return Reply(
            location = location,
            usn = usn,
            server = headers["SERVER"],
            searchTarget = target,
        )
    }

    private const val SOCKET_TIMEOUT_MILLIS = 800
    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_SEARCH_TIMEOUT_MILLIS = 30_000L
}
