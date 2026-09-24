package com.mybrowser.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** Small, bounded text downloads. Never forwards WebView cookies or authentication. */
class TextDownloader {
    data class Response(val text: String?, val etag: String? = null, val lastModified: String? = null)

    suspend fun get(url: String, maxBytes: Int, etag: String? = null, modified: String? = null): Response =
        withContext(Dispatchers.IO) {
            require(isHttpUrl(url)) { "Invalid HTTP(S) URL" }
            var current = URL(url)
            repeat(6) { hop ->
                coroutineContext.ensureActive()
                val connection = current.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "PureBrowser")
                    connection.setRequestProperty("Accept", "text/plain, application/javascript, */*;q=0.1")
                    connection.setRequestProperty("Accept-Encoding", "gzip")
                    etag?.takeIf { it.length <= 1024 && !it.contains('\n') && !it.contains('\r') }
                        ?.let { connection.setRequestProperty("If-None-Match", it) }
                    modified?.takeIf { it.length <= 128 && !it.contains('\n') && !it.contains('\r') }
                        ?.let { connection.setRequestProperty("If-Modified-Since", it) }
                    when (val code = connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            if (hop == RedirectPolicy.MAX_HOPS) throw IOException("Too many redirects")
                            val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                            current = RedirectPolicy.next(current, location) { isHttpUrl(it) }
                                ?: throw IOException("Unsafe redirect")
                        }
                        304 -> {
                            if (etag == null && modified == null) throw IOException("Unexpected HTTP 304")
                            return@withContext Response(null, etag, modified)
                        }
                        200 -> {
                            if (connection.contentType.orEmpty().contains("text/html", true)) throw IOException("HTML response")
                            if (connection.contentLengthLong > maxBytes) throw IOException("Download too large")
                            val input = connection.inputStream.let {
                                if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it
                            }
                            val body = input.use { readText(it, maxBytes) }
                            coroutineContext.ensureActive()
                            return@withContext Response(body,
                                connection.getHeaderField("ETag")?.take(1024),
                                connection.getHeaderField("Last-Modified")?.take(128))
                        }
                        else -> throw IOException("HTTP " + code)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects")
        }

    companion object {
        fun isHttpUrl(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            raw.length <= 2048 && raw.none { it.isWhitespace() || it.isISOControl() } &&
                uri.scheme?.lowercase() in listOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
        }.getOrDefault(false)

        fun readText(input: InputStream, maxBytes: Int): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("Download too large")
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
