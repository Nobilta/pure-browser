package com.mybrowser.media

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mybrowser.tabs.MediaNavigation
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Keeps the selected media's Referer on its initial request, redirects and Range requests. */
object MediaRequestInterceptor {
    enum class FailureKind { NETWORK, TIMEOUT, CERTIFICATE, REDIRECT, HTTP }
    data class Failure(val kind: FailureKind, val status: Int = 0)
    private class RedirectFailure : IOException()
    private class Cancelled : IOException()
    fun intercept(request: WebResourceRequest, navigation: MediaNavigation?, cookies: CookieManager?,
                  allowThirdPartyCookies: Boolean = false,
                  onRedirectResponse: (MediaNavigation, WebResourceResponse) -> Boolean = { _, _ -> false },
                  onDownloadResponse: ((MediaNavigation, WebResourceResponse) -> Boolean)? = null,
                  onFailure: (Failure) -> Unit = {}, isActive: () -> Boolean): WebResourceResponse? {
        val target = request.url.toString()
        if (navigation == null || navigation.loadId.nativeRequests || request.method != "GET" || !navigation.matches(target)) return null
        val jar = MediaCookies(cookies, if (request.isForMainFrame) navigation.sourcePageUrl else navigation.url,
            request.isForMainFrame, allowThirdPartyCookies)
        val notified = AtomicBoolean(false)
        fun report(failure: Failure) { if (isActive() && notified.compareAndSet(false, true)) onFailure(failure) }
        fun reportIo(error: IOException) {
            if (error !is Cancelled) report(Failure(when (error) {
                is SSLException -> FailureKind.CERTIFICATE
                is SocketTimeoutException -> FailureKind.TIMEOUT
                is RedirectFailure -> FailureKind.REDIRECT
                else -> FailureKind.NETWORK
            }))
        }
        var connection: HttpURLConnection? = null
        var downloadOwned = false
        try {
            val original = URL(target)
            var current = original
            var context: MediaNavigation = navigation
            repeat(6) { hop ->
                if (!isActive()) throw Cancelled()
                jar.visit(current.toString())
                val sameOrigin = current.protocol.equals(original.protocol, true) &&
                    current.host.equals(original.host, true) && current.portOrDefault() == original.portOrDefault()
                val opened = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    request.requestHeaders.orEmpty().forEach { (key, value) ->
                        if (key.lowercase() !in OMITTED_REQUEST_HEADERS) {
                            setRequestProperty(key, value)
                        }
                    }
                    setRequestProperty("Accept-Encoding", "identity")
                    context.headersFor(current.toString())["Referer"]?.let { setRequestProperty("Referer", it) }
                    val initialCookie = if (hop == 0) request.requestHeaders?.entries?.firstOrNull { it.key.equals("Cookie", true) }?.value else null
                    jar.header(current.toString(), initialCookie)?.let { setRequestProperty("Cookie", it) }
                    if (sameOrigin) {
                        request.requestHeaders?.entries?.firstOrNull { it.key.equals("Authorization", true) }?.value
                            ?.let { setRequestProperty("Authorization", it) }
                    }
                }
                connection = opened
                val status = opened.responseCode
                if (!isActive()) throw Cancelled()
                opened.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value }.forEach { if (!isActive()) throw Cancelled(); jar.store(current.toString(), it) }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw RedirectFailure()
                    val next = URL(current, opened.getHeaderField("Location") ?: throw RedirectFailure())
                    if (next.userInfo != null || current.protocol == "https" && next.protocol != "https") {
                        throw RedirectFailure()
                    }
                    context = context.follow(next.toString(), true, opened.getHeaderField("Referrer-Policy")) ?: throw RedirectFailure()
                    opened.disconnect()
                    connection = null
                    current = next
                } else {
                    if (status !in 100..599 || status in 300..399) throw IOException("Invalid media response")
                    if (status == 401 && !opened.getHeaderField("WWW-Authenticate").isNullOrBlank() ||
                        status == 407 && !opened.getHeaderField("Proxy-Authenticate").isNullOrBlank()) {
                        // This is a challenge, not a consumed successful response. Chromium
                        // must perform the retry so its auth dialog/cache can answer it.
                        navigation.loadId.nativeRequests = true
                        return null
                    }
                    if (status >= 400) {
                        report(Failure(FailureKind.HTTP, status))
                        return failedResponse(status)
                    }
                    val headers = opened.headerFields.entries.filter { it.key != null &&
                        !it.key.equals("Set-Cookie", true) && !it.key.equals("Transfer-Encoding", true) && !it.key.equals("Connection", true) }
                        .associate { it.key to it.value.joinToString(", ") }.toMutableMap()
                    val contentType = opened.contentType.orEmpty()
                    val mime = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
                    val charset = contentType.split(';').firstOrNull { it.trim().startsWith("charset=", true) }
                        ?.substringAfter('=')?.trim()?.trim('"')
                    val encoding = opened.contentEncoding
                    val compressed = !encoding.isNullOrBlank() && !encoding.equals("identity", true)
                    val body = when (encoding?.lowercase(java.util.Locale.ROOT)) {
                        null, "", "identity" -> opened.inputStream
                        "gzip", "x-gzip" -> GZIPInputStream(opened.inputStream)
                        "deflate" -> InflaterInputStream(opened.inputStream)
                        else -> throw IOException("Unsupported media content encoding")
                    }
                    if (compressed) {
                        headers.keys.removeAll { it.equals("Content-Encoding", true) || it.equals("Content-Length", true) }
                    }
                    val range = if (status == 206) opened.getHeaderField("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) } else null
                    val rangeStart = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val virtualEnd = range?.groupValues?.get(2)?.toLongOrNull()?.plus(1)
                        ?: opened.contentLengthLong.coerceAtLeast(0)
                    // Zero means unknown to InputStreamReader: it skips the int32 range
                    // bound calculation but still reads to EOF. The HTTP headers retain
                    // the real length/range, including offsets beyond 2 GiB.
                    val availableSize = if (!compressed && virtualEnd in 1..Int.MAX_VALUE.toLong()) virtualEnd.toInt() else 0
                    val stream = object : FilterInputStream(body) {
                        private var positioned = false
                        private var virtualSkipRemaining = rangeStart
                        private val closed = AtomicBoolean(false)
                        private var remaining = if (compressed) -1L else opened.contentLengthLong
                        private fun received(count: Int): Int {
                            if (count < 0) {
                                if (remaining > 0) throw java.io.EOFException("Incomplete media response")
                                close()
                            } else if (remaining >= 0) remaining -= count
                            return count
                        }
                        private fun checkActive() {
                            if (closed.get() || !downloadOwned && !isActive()) { close(); throw Cancelled() }
                        }
                        // Chromium's InputStreamReader treats available() as the entire resource
                        // size, then calls skip(rangeStart), even for a server's 206 body. The
                        // slice's end makes its computed response length match the real body.
                        // The HTTP Content-Range still supplies the complete media size.
                        override fun available(): Int = if (!positioned) availableSize else super.available()
                        override fun skip(count: Long): Long = reading {
                            if (!positioned && virtualSkipRemaining > 0 && count == virtualSkipRemaining) {
                                // Older WebViews narrow Java's skip result to int. Their
                                // caller loops, so acknowledge large offsets in int-sized
                                // steps without consuming the already ranged response.
                                val skipped = count.coerceAtMost(Int.MAX_VALUE.toLong())
                                virtualSkipRemaining -= skipped
                                positioned = virtualSkipRemaining == 0L
                                return@reading skipped
                            }
                            positioned = true
                            super.skip(count).also { if (remaining >= 0) remaining -= it }
                        }
                        private inline fun <T> reading(block: () -> T): T = try {
                            checkActive(); block()
                        } catch (error: IOException) {
                            if (!closed.get() && !downloadOwned) reportIo(error)
                            runCatching { close() }
                            throw error
                        }
                        override fun read(): Int = reading { positioned = true; super.read().also { received(if (it == -1) -1 else 1) } }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reading {
                            positioned = true
                            received(super.read(buffer, offset, length))
                        }
                        override fun close() { if (closed.compareAndSet(false, true)) try { super.close() } finally { opened.disconnect() } }
                    }
                    val response = WebResourceResponse(mime, charset, status,
                        opened.responseMessage?.takeIf { it.isNotBlank() && it.all { c -> c.code in 32..126 } }
                            ?: "Media response", headers, stream)
                    if (request.isForMainFrame && onDownloadResponse != null && isDownload(mime, opened.getHeaderField("Content-Disposition"))) {
                        downloadOwned = true
                        if (!isActive() || !onDownloadResponse(context, response)) {
                            response.data.close()
                            throw Cancelled()
                        }
                        connection = null
                        return emptyResponse()
                    }
                    // Rendering a redirected HTML response under the original media URL
                    // would assign the wrong origin. Transfer this very response to a new
                    // navigation at its final URL; never issue another speculative GET.
                    if (request.isForMainFrame && current != original &&
                        !mime.startsWith("video/", true) && !mime.startsWith("audio/", true)) {
                        if (!isActive() || !onRedirectResponse(context, response)) {
                            response.data.close()
                            throw Cancelled()
                        }
                        connection = null
                        return emptyResponse()
                    }
                    connection = null // Chromium owns the stream, which releases the connection on close.
                    return response
                }
            }
        } catch (error: IOException) {
            reportIo(error)
            return failedResponse(if (error is Cancelled) 499 else 502)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun failedResponse(status: Int) = WebResourceResponse("text/plain", "UTF-8", status,
        "Media request stopped", mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("Media request stopped".toByteArray()))

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", 204,
        "No Content", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private fun isDownload(mime: String, disposition: String?): Boolean {
        if (disposition?.substringBefore(';')?.trim().equals("attachment", true)) return true
        val type = mime.lowercase(java.util.Locale.ROOT)
        if (type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")) return true
        return !type.startsWith("text/") && !type.startsWith("image/") && !type.startsWith("video/") &&
            !type.startsWith("audio/") && type !in setOf("application/xhtml+xml", "application/xml", "application/json")
    }

    private fun URL.portOrDefault() = if (port >= 0) port else defaultPort

    private val OMITTED_REQUEST_HEADERS = setOf("host", "connection", "accept-encoding", "referer", "cookie",
        "authorization", "proxy-authorization", "if-none-match", "if-modified-since")
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
}
