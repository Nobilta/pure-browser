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

/** Keeps the selected media's Referer on its initial request, redirects and Range requests. */
object MediaRequestInterceptor {
    enum class FailureKind { NETWORK, TIMEOUT, CERTIFICATE, REDIRECT, HTTP }
    data class Failure(val kind: FailureKind, val status: Int = 0)
    private class RedirectFailure : IOException()
    private class Cancelled : IOException()
    fun intercept(request: WebResourceRequest, navigation: MediaNavigation?, cookies: CookieManager?,
                  allowThirdPartyCookies: Boolean = false,
                  onFailure: (Failure) -> Unit = {}, isActive: () -> Boolean): WebResourceResponse? {
        val target = request.url.toString()
        if (navigation == null || request.method != "GET" || !navigation.matches(target)) return null
        val jar = MediaCookies(cookies, if (request.isForMainFrame) navigation.sourcePageUrl else navigation.url,
            request.isForMainFrame, allowThirdPartyCookies)
        val notified = AtomicBoolean(false)
        val redirects = mutableListOf<MediaNavigation>()
        fun fallback(): WebResourceResponse? {
            if (request.isForMainFrame && isActive()) navigation.loadId.fallbackRedirects = redirects.toList()
            return null
        }
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
                    request.requestHeaders.forEach { (key, value) ->
                        if (key.lowercase() !in OMITTED_REQUEST_HEADERS) {
                            setRequestProperty(key, value)
                        }
                    }
                    setRequestProperty("Accept-Encoding", "identity")
                    context.headersFor(current.toString())["Referer"]?.let { setRequestProperty("Referer", it) }
                    val initialCookie = if (hop == 0) request.requestHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value else null
                    jar.header(current.toString(), initialCookie)?.let { setRequestProperty("Cookie", it) }
                    if (sameOrigin) {
                        request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
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
                    redirects += context
                    opened.disconnect()
                    connection = null
                    current = next
                } else {
                    if (status !in 100..599 || status in 300..399) throw IOException("Invalid media response")
                    if (status >= 400) {
                        report(Failure(FailureKind.HTTP, status))
                        return failedResponse(status)
                    }
                    val headers = opened.headerFields.entries.filter { it.key != null &&
                        !it.key.equals("Set-Cookie", true) && !it.key.equals("Transfer-Encoding", true) && !it.key.equals("Connection", true) }
                        .associate { it.key to it.value.joinToString(", ") }.toMutableMap()
                    val contentType = opened.contentType.orEmpty()
                    val mime = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
                    // A redirected HTML/login page must retain its real origin in WebView.
                    if (request.isForMainFrame && !mime.startsWith("video/", true) && !mime.startsWith("audio/", true)) return fallback()
                    val charset = contentType.split(';').firstOrNull { it.trim().startsWith("charset=", true) }
                        ?.substringAfter('=')?.trim()?.trim('"')
                    val body = opened.inputStream
                    val encoding = opened.contentEncoding
                    if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                        return fallback()
                    }
                    val range = if (status == 206) opened.getHeaderField("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) } else null
                    val rangeStart = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val virtualEnd = range?.groupValues?.get(2)?.toLongOrNull()?.plus(1)
                        ?: opened.contentLengthLong.coerceAtLeast(0)
                    // The WebView stream bridge represents this bound as a signed 32-bit int.
                    // Keep native handling when its range cannot be represented faithfully.
                    if (virtualEnd > Int.MAX_VALUE || virtualEnd <= 0 && status < 400) return fallback()
                    val stream = object : FilterInputStream(body) {
                        private var positioned = false
                        private val closed = AtomicBoolean(false)
                        private var remaining = opened.contentLengthLong
                        private fun received(count: Int): Int {
                            if (count < 0) {
                                if (remaining > 0) throw java.io.EOFException("Incomplete media response")
                                close()
                            } else if (remaining >= 0) remaining -= count
                            return count
                        }
                        private fun checkActive() {
                            if (closed.get() || !isActive()) { close(); throw Cancelled() }
                        }
                        // Chromium's InputStreamReader treats available() as the entire resource
                        // size, then calls skip(rangeStart), even for a server's 206 body. The
                        // slice's end makes its computed response length match the real body.
                        // The HTTP Content-Range still supplies the complete media size.
                        override fun available(): Int = if (!positioned) virtualEnd.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else super.available()
                        override fun skip(count: Long): Long = reading {
                            if (!positioned && rangeStart > 0 && count == rangeStart) {
                                positioned = true
                                return@reading count
                            }
                            positioned = true
                            super.skip(count).also { if (remaining >= 0) remaining -= it }
                        }
                        private inline fun <T> reading(block: () -> T): T = try {
                            checkActive(); block()
                        } catch (error: IOException) {
                            if (!closed.get()) reportIo(error)
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

    private fun URL.portOrDefault() = if (port >= 0) port else defaultPort

    private val OMITTED_REQUEST_HEADERS = setOf("host", "connection", "accept-encoding", "referer", "cookie",
        "authorization", "proxy-authorization", "if-none-match", "if-modified-since")
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
}
