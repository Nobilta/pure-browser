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

/** Keeps the selected media's Referer on its initial request, redirects and Range requests. */
object MediaRequestInterceptor {
    fun intercept(request: WebResourceRequest, navigation: MediaNavigation?, cookies: CookieManager?,
                  isActive: () -> Boolean): WebResourceResponse? {
        val target = request.url.toString()
        if (navigation == null || request.method != "GET" || !navigation.matches(target)) return null
        var connection: HttpURLConnection? = null
        try {
            val original = URL(target)
            var current = original
            repeat(6) { hop ->
                if (!isActive()) throw IOException("Media page left")
                val context = MediaNavigation.create(current.toString(), navigation.referer)
                    ?: throw IOException("Invalid media redirect")
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
                    setRequestProperty("Referer", context.referer)
                    if (current.host.equals(original.host, true) && current.protocol.equals(original.protocol, true)) {
                        val cookie = (if (sameOrigin) request.requestHeaders.entries.firstOrNull { it.key.equals("Cookie", true) }?.value else null)
                            ?: cookies?.getCookie(current.toString())
                        cookie?.let { setRequestProperty("Cookie", it) }
                    }
                    if (sameOrigin) {
                        request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
                            ?.let { setRequestProperty("Authorization", it) }
                    }
                }
                connection = opened
                val status = opened.responseCode
                if (!isActive()) throw IOException("Media page left")
                if (sameOrigin) opened.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value }.forEach { cookies?.setCookie(current.toString(), it) }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw IOException("Too many media redirects")
                    val next = URL(current, opened.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                    if (next.userInfo != null || current.protocol == "https" && next.protocol != "https") {
                        throw IOException("Unsafe media redirect")
                    }
                    opened.disconnect()
                    connection = null
                    current = next
                } else {
                    if (status !in 100..599 || status in 300..399) throw IOException("Invalid media response")
                    val headers = opened.headerFields.entries.filter { it.key != null &&
                        !it.key.equals("Set-Cookie", true) && !it.key.equals("Transfer-Encoding", true) && !it.key.equals("Connection", true) }
                        .associate { it.key to it.value.joinToString(", ") }.toMutableMap()
                    val contentType = opened.contentType.orEmpty()
                    val mime = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
                    // A redirected HTML/login page must retain its real origin in WebView.
                    if (request.isForMainFrame && !mime.startsWith("video/", true) && !mime.startsWith("audio/", true)) return null
                    val charset = contentType.split(';').firstOrNull { it.trim().startsWith("charset=", true) }
                        ?.substringAfter('=')?.trim()?.trim('"')
                    val body = (if (status >= 400) opened.errorStream else opened.inputStream) ?: ByteArrayInputStream(byteArrayOf())
                    val encoding = opened.contentEncoding
                    if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) {
                        return null
                    }
                    val range = if (status == 206) opened.getHeaderField("Content-Range")?.let { CONTENT_RANGE.matchEntire(it.trim()) } else null
                    val rangeStart = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val virtualEnd = range?.groupValues?.get(2)?.toLongOrNull()?.plus(1)
                        ?: opened.contentLengthLong.coerceAtLeast(0)
                    // The WebView stream bridge represents this bound as a signed 32-bit int.
                    // Keep native handling when its range cannot be represented faithfully.
                    if (virtualEnd > Int.MAX_VALUE || virtualEnd <= 0 && status < 400) return null
                    val stream = object : FilterInputStream(body) {
                        private var positioned = false
                        private fun checkActive() {
                            if (!isActive()) { close(); throw IOException("Media page left") }
                        }
                        // Chromium's InputStreamReader treats available() as the entire resource
                        // size, then calls skip(rangeStart), even for a server's 206 body. The
                        // slice's end makes its computed response length match the real body.
                        // The HTTP Content-Range still supplies the complete media size.
                        override fun available(): Int = if (!positioned) virtualEnd.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else super.available()
                        override fun skip(count: Long): Long {
                            checkActive()
                            if (!positioned && rangeStart > 0 && count == rangeStart) {
                                positioned = true
                                return count
                            }
                            positioned = true
                            return super.skip(count)
                        }
                        override fun read(): Int { checkActive(); positioned = true; return super.read().also { if (it == -1) close() } }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            checkActive(); positioned = true
                            return super.read(buffer, offset, length).also { if (it == -1) close() }
                        }
                        override fun close() { try { super.close() } finally { opened.disconnect() } }
                    }
                    val response = WebResourceResponse(mime, charset, status,
                        opened.responseMessage?.takeIf { it.isNotBlank() && it.all { c -> c.code in 32..126 } }
                            ?: "Media response", headers, stream)
                    connection = null // Chromium owns the stream, which releases the connection on close.
                    return response
                }
            }
        } catch (_: IOException) {
            return WebResourceResponse("text/plain", "UTF-8", 502, "Media request failed", emptyMap(),
                ByteArrayInputStream(byteArrayOf()))
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun URL.portOrDefault() = if (port >= 0) port else defaultPort

    private val OMITTED_REQUEST_HEADERS = setOf("host", "connection", "accept-encoding", "referer", "cookie",
        "authorization", "proxy-authorization", "if-none-match", "if-modified-since")
    private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
}
