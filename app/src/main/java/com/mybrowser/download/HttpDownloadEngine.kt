package com.mybrowser.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

internal data class DownloadRequestHeaders(
    val userAgent: String,
    val cookie: String?,
    val referer: String?,
)

internal data class DownloadPayload(
    val parts: List<File>,
    val totalBytes: Long,
    val actualThreadCount: Int,
)

/**
 * HTTP transfer engine used for newly-created browser downloads.
 *
 * It downloads into app-owned temporary files first. That makes segmented transfers work
 * with every Storage Access Framework provider, including providers whose final document is
 * not seekable. The caller publishes the parts to MediaStore or the selected tree only after
 * every byte has been validated.
 */
internal class HttpDownloadEngine {
    private val connections = ConcurrencyBudget(total = 8, perHost = 4)
    private val leases = java.util.concurrent.ConcurrentHashMap<HttpURLConnection, ConcurrencyBudget.Lease>()

    suspend fun download(
        url: String,
        headers: DownloadRequestHeaders,
        requestedThreads: Int,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
    ): DownloadPayload = withContext(Dispatchers.IO) {
        check(tempDirectory.mkdirs() || tempDirectory.isDirectory) {
            "Unable to create download temporary directory"
        }

        val threadLimit = DownloadSettingsRepository.normalizeThreadCount(requestedThreads)
        currentCoroutineContext().ensureActive()
        val checkpoint = DownloadCheckpoint.read(tempDirectory, url)
        val probe = if (checkpoint != null) {
            // A failed revalidation preserves the partial files for a later retry.
            probeRangeSupport(url, headers)
        } else if (threadLimit > 1) {
            try { probeRangeSupport(url, headers) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) {
                Log.w(TAG, "Range probe failed; using one stream", error)
                null
            }
        } else {
            null
        }
        if (checkpoint != null && probe?.supportsRanges == true && probe.totalBytes == checkpoint.total &&
            probe.validator == checkpoint.validator && probe.entityUrl == checkpoint.entityUrl) {
            try {
                return@withContext downloadParallel(url, headers, tempDirectory, checkpoint.total,
                    checkpoint.threads, checkpoint.validator, checkpoint.entityUrl, onProgress)
            } catch (rejected: ResumeRejected) {
                Log.w(TAG, "Server rejected resume; restarting the entity", rejected)
            }
        }
        // Changed/missing validators and invalid sidecars invalidate every old byte.
        currentCoroutineContext().ensureActive()
        tempDirectory.deleteRecursively()
        check(tempDirectory.mkdirs() || tempDirectory.isDirectory)
        val threadCount = probe?.totalBytes
            ?.let { total -> DownloadRanges.effectiveThreadCount(total, threadLimit) }
            ?.takeIf { probe.supportsRanges && probe.validator != null && it > 1 }
            ?: 1

        if (threadCount == 1) {
            return@withContext downloadSingle(url, headers, tempDirectory, onProgress)
        }
        val rangeProbe = requireNotNull(probe)
        DownloadCheckpoint(url, rangeProbe.totalBytes, requireNotNull(rangeProbe.validator), threadCount, rangeProbe.entityUrl).save(tempDirectory)

        try {
            downloadParallel(
                url = url,
                headers = headers,
                tempDirectory = tempDirectory,
                totalBytes = rangeProbe.totalBytes,
                threadCount = threadCount,
                validator = rangeProbe.validator,
                entityUrl = rangeProbe.entityUrl,
                onProgress = onProgress,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ResumeRejected) {
            // Some CDNs advertise ranges but reject concurrent ranges or change the entity
            // between requests. A clean single-stream retry is safer than leaving corrupt
            // chunks behind.
            Log.w(TAG, "Segmented transfer failed; retrying with one stream", error)
            tempDirectory.deleteRecursively()
            check(tempDirectory.mkdirs() || tempDirectory.isDirectory) {
                "Unable to recreate download temporary directory"
            }
            onProgress(0L, rangeProbe.totalBytes, 1)
            downloadSingle(url, headers, tempDirectory, onProgress)
        }
    }

    private suspend fun probeRangeSupport(
        url: String,
        headers: DownloadRequestHeaders,
    ): RangeProbe {
        val connection = openConnection(url, headers, range = "bytes=0-0")
        return try {
            val code = connection.responseCode
            requireIdentityEncoding(connection)
            val finalScheme = connection.url.protocol.lowercase()
            if (finalScheme != "http" && finalScheme != "https") {
                throw IOException("Download redirected to unsupported scheme")
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = connection.getHeaderField("Content-Range")
                val total = parseContentRangeTotal(contentRange)
                val returnedRange = parseContentRange(contentRange)
                val validator = entityValidator(connection)
                connection.inputStream.use { input -> input.read() }
                RangeProbe(
                    totalBytes = total,
                    supportsRanges = total > 0L && returnedRange == 0L..0L,
                    validator = validator,
                    entityUrl = connection.url.toExternalForm(),
                )
            } else {
                if (code !in 200..299 && code != 416) throw DownloadHttpException(code)
                // Do not consume a server that ignored Range: disconnecting immediately
                // avoids downloading the full file twice.
                RangeProbe(
                    totalBytes = connection.contentLengthLong.coerceAtLeast(0L),
                    supportsRanges = false,
                    validator = null,
                    entityUrl = connection.url.toExternalForm(),
                )
            }
        } finally {
            releaseConnection(connection)
        }
    }

    private suspend fun downloadSingle(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        onProgress: (Long, Long, Int) -> Unit,
    ): DownloadPayload {
        val destination = File(tempDirectory, "part-0")
        val connection = openConnection(url, headers)
        try {
            val code = connection.responseCode
            requireIdentityEncoding(connection)
            if (code !in 200..299 || code == HttpURLConnection.HTTP_PARTIAL) {
                throw DownloadHttpException(code)
            }
            val finalScheme = connection.url.protocol.lowercase()
            if (finalScheme != "http" && finalScheme != "https") {
                throw IOException("Download redirected to unsupported scheme")
            }

            val declaredTotal = connection.contentLengthLong.coerceAtLeast(0L)
            val validator = entityValidator(connection)
            if (declaredTotal > 0 && validator != null) DownloadCheckpoint(url, declaredTotal, validator, 1,
                connection.url.toExternalForm()).save(tempDirectory)
            var downloaded = 0L
            var lastUpdateNanos = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (declaredTotal > 0 && downloaded + count > declaredTotal) throw ResumeRejected("Full response exceeded Content-Length")
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastUpdateNanos >= PROGRESS_INTERVAL_NANOS) {
                            lastUpdateNanos = now
                            onProgress(downloaded, declaredTotal, 1)
                        }
                    }
                }
            }
            val total = declaredTotal.takeIf { it > 0L } ?: downloaded
            if (declaredTotal > 0L && downloaded != declaredTotal) {
                throw IOException("Length mismatch: expected $declaredTotal, got $downloaded")
            }
            onProgress(downloaded, total, 1)
            return DownloadPayload(listOf(destination), total, 1)
        } catch (cancelled: CancellationException) {
            if (DownloadCheckpoint.read(tempDirectory, url) == null) destination.delete()
            throw cancelled
        } catch (error: Exception) {
            if (error is ResumeRejected || DownloadCheckpoint.read(tempDirectory, url) == null) destination.delete()
            throw error
        } finally {
            releaseConnection(connection)
        }
    }

    private suspend fun downloadParallel(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        totalBytes: Long,
        threadCount: Int,
        validator: String?,
        entityUrl: String,
        onProgress: (Long, Long, Int) -> Unit,
    ): DownloadPayload = coroutineScope {
        val ranges = DownloadRanges.split(totalBytes, threadCount)
        val parts = ranges.indices.map { index -> File(tempDirectory, "part-$index") }
        val downloaded = AtomicLong(parts.sumOf { it.length() })
        val lastUpdateNanos = AtomicLong(0L)

        fun reportProgress(force: Boolean = false) {
            val now = System.nanoTime()
            val last = lastUpdateNanos.get()
            if (force || now - last >= PROGRESS_INTERVAL_NANOS) {
                if (force || lastUpdateNanos.compareAndSet(last, now)) {
                    onProgress(downloaded.get(), totalBytes, ranges.size)
                }
            }
        }

        reportProgress(force = true)
        ranges.mapIndexed { index, range ->
                async(Dispatchers.IO) {
                    downloadRange(
                        url = url,
                        headers = headers,
                        destination = parts[index],
                        range = range,
                        totalBytes = totalBytes,
                        validator = validator,
                        entityUrl = entityUrl,
                        downloaded = downloaded,
                        reportProgress = ::reportProgress,
                    )
                }
        }.awaitAll()
        reportProgress(force = true)
        DownloadPayload(parts, totalBytes, ranges.size)
    }

    private suspend fun downloadRange(
        url: String,
        headers: DownloadRequestHeaders,
        destination: File,
        range: LongRange,
        totalBytes: Long,
        validator: String?,
        entityUrl: String,
        downloaded: AtomicLong,
        reportProgress: () -> Unit,
    ) {
        val expected = range.last - range.first + 1L
        val offset = destination.length()
        if (offset == expected) return
        if (offset > expected) throw ResumeRejected("Partial file exceeded its range")
        val remaining = (range.first + offset)..range.last
        val connection = openConnection(url, headers, "bytes=${remaining.first}-${remaining.last}", validator)
        try {
            requireIdentityEncoding(connection)
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val code = connection.responseCode
                if (code in listOf(200, 400, 416, 429)) throw ResumeRejected("Range request returned HTTP $code")
                throw DownloadHttpException(code)
            }
            val contentRange = connection.getHeaderField("Content-Range")
            val responseRange = parseContentRange(contentRange)
            val responseTotal = parseContentRangeTotal(contentRange)
            if (responseRange != remaining || responseTotal != totalBytes || connection.url.toExternalForm() != entityUrl ||
                (validator != null && entityValidator(connection) != validator)) {
                throw ResumeRejected("Range response no longer matches the entity")
            }

            var written = offset
            connection.inputStream.use { input ->
                FileOutputStream(destination, true).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        if (written > expected) throw ResumeRejected("Range response exceeded request")
                        output.write(buffer, 0, count)
                        downloaded.addAndGet(count.toLong())
                        reportProgress()
                    }
                }
            }
            if (written != expected) {
                throw IOException("Range length mismatch: expected $expected, got $written")
            }
        } finally {
            releaseConnection(connection)
        }
    }

    private suspend fun openConnection(
        url: String,
        headers: DownloadRequestHeaders,
        range: String? = null,
        validator: String? = null,
    ): HttpURLConnection {
        val original = URL(url)
        var current = original
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (current.protocol !in setOf("http", "https") || current.host.isBlank()) {
                throw IOException("Unsupported download URL")
            }
            val lease = connections.acquire(current.host)
            val connection = try {
                currentCoroutineContext().ensureActive()
                (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                headers.userAgent.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                // A raw Cookie header is scoped to the URL that supplied it. Platform
                // auto-redirects can forward that header to an unrelated download host.
                if (sameOrigin(original, current)) {
                    headers.cookie?.takeIf(String::isNotBlank)?.let { setRequestProperty("Cookie", it) }
                }
                headers.referer?.takeIf(String::isNotBlank)?.let { setRequestProperty("Referer", it) }
                range?.let { setRequestProperty("Range", it) }
                validator?.let { setRequestProperty("If-Range", it) }
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
            leases[connection] = lease
            try {
                if (connection.responseCode !in REDIRECT_CODES) return connection
                if (hop == MAX_REDIRECTS) throw IOException("Too many download redirects")
                val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect URL")
                val next = URL(current, location)
                if (next.protocol !in setOf("http", "https") || next.host.isBlank() ||
                    next.userInfo != null || (current.protocol == "https" && next.protocol != "https")) {
                    throw IOException("Unsafe download redirect")
                }
                current = next
            } catch (error: Exception) {
                releaseConnection(connection)
                throw error
            }
            releaseConnection(connection)
        }
        throw IOException("Too many download redirects")
    }

    private fun releaseConnection(connection: HttpURLConnection) {
        try { connection.disconnect() } finally { leases.remove(connection)?.close() }
    }

    private fun entityValidator(connection: HttpURLConnection): String? {
        val etag = connection.getHeaderField("ETag")?.takeIf { value ->
            value.length in 2..1024 && value.first() == '"' && value.last() == '"' &&
                value.substring(1, value.lastIndex).none { it == '"' || it.isISOControl() }
        }
        if (etag != null) return etag
        val modified = connection.getHeaderFieldDate("Last-Modified", -1)
        val date = connection.getHeaderFieldDate("Date", -1)
        return connection.getHeaderField("Last-Modified")?.takeIf {
            it.length <= 1024 && modified > 0 && date - modified >= 60_000 && it.none(Char::isISOControl)
        }
    }

    private fun requireIdentityEncoding(connection: HttpURLConnection) {
        val encoding = connection.getHeaderField("Content-Encoding")?.trim()
        if (!encoding.isNullOrEmpty() && !encoding.equals("identity", ignoreCase = true)) {
            throw IOException("Server ignored the requested identity encoding")
        }
    }

    private class ResumeRejected(message: String) : IOException(message)

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, true) && first.host.equals(second.host, true) &&
            (if (first.port >= 0) first.port else first.defaultPort) ==
            (if (second.port >= 0) second.port else second.defaultPort)

    private fun parseContentRangeTotal(value: String?): Long {
        val match = CONTENT_RANGE_WITH_TOTAL.matchEntire(value?.trim().orEmpty()) ?: return 0L
        return match.groupValues[3].toLongOrNull()?.takeIf { it > 0L } ?: 0L
    }

    private fun parseContentRange(value: String?): LongRange? {
        val match = CONTENT_RANGE_WITH_TOTAL.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        return if (start >= 0L && end >= start) start..end else null
    }

    private data class RangeProbe(
        val totalBytes: Long,
        val supportsRanges: Boolean,
        val validator: String?,
        val entityUrl: String,
    )

    private companion object {
        const val TAG = "HttpDownloadEngine"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE_WITH_TOTAL = Regex(
            "bytes\\s+(\\d+)-(\\d+)/(\\d+)",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Pure range math kept separate so boundary cases can be covered without network I/O. */
internal object DownloadRanges {
    private const val MIN_BYTES_PER_THREAD = 1024L * 1024L

    fun effectiveThreadCount(totalBytes: Long, requestedThreads: Int): Int {
        if (totalBytes <= 0L) return 1
        val usefulThreads = (totalBytes / MIN_BYTES_PER_THREAD).coerceAtLeast(1L)
        return minOf(
            DownloadSettingsRepository.normalizeThreadCount(requestedThreads).toLong(),
            usefulThreads,
            totalBytes,
        ).toInt().coerceAtLeast(1)
    }

    fun split(totalBytes: Long, threadCount: Int): List<LongRange> {
        if (totalBytes <= 0L) return emptyList()
        val count = threadCount.coerceIn(1, MAX_DOWNLOAD_THREADS)
            .coerceAtMost(totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val baseSize = totalBytes / count
        val remainder = totalBytes % count
        var nextStart = 0L
        return List(count) { index ->
            val length = baseSize + if (index < remainder) 1L else 0L
            val range = nextStart..(nextStart + length - 1L)
            nextStart = range.last + 1L
            range
        }
    }
}

internal class DownloadHttpException(val code: Int) : IOException("Download returned HTTP $code")
