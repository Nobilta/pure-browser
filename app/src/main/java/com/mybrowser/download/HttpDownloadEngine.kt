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

    suspend fun download(
        url: String,
        headers: DownloadRequestHeaders,
        requestedThreads: Int,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
    ): DownloadPayload = withContext(Dispatchers.IO) {
        tempDirectory.deleteRecursively()
        check(tempDirectory.mkdirs() || tempDirectory.isDirectory) {
            "Unable to create download temporary directory"
        }

        val threadLimit = DownloadSettingsRepository.normalizeThreadCount(requestedThreads)
        val probe = if (threadLimit > 1) {
            runCatching { probeRangeSupport(url, headers) }
                .onFailure { Log.w(TAG, "Range probe failed; using one stream", it) }
                .getOrNull()
        } else {
            null
        }
        val threadCount = probe?.totalBytes
            ?.let { total -> DownloadRanges.effectiveThreadCount(total, threadLimit) }
            ?.takeIf { probe.supportsRanges && it > 1 }
            ?: 1

        if (threadCount == 1) {
            return@withContext downloadSingle(url, headers, tempDirectory, onProgress)
        }
        val rangeProbe = requireNotNull(probe)

        try {
            downloadParallel(
                url = url,
                headers = headers,
                tempDirectory = tempDirectory,
                totalBytes = rangeProbe.totalBytes,
                threadCount = threadCount,
                validator = rangeProbe.validator,
                onProgress = onProgress,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
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

    private fun probeRangeSupport(
        url: String,
        headers: DownloadRequestHeaders,
    ): RangeProbe {
        val connection = openConnection(url, headers).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            val code = connection.responseCode
            val finalScheme = connection.url.protocol.lowercase()
            if (finalScheme != "http" && finalScheme != "https") {
                throw IOException("Download redirected to unsupported scheme")
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = connection.getHeaderField("Content-Range")
                val total = parseContentRangeTotal(contentRange)
                val returnedRange = parseContentRange(contentRange)
                val strongEtag = connection.getHeaderField("ETag")
                    // RFC 9110 entity tags are quoted. Some small HTTP servers emit a bare
                    // token and then mishandle it as If-None-Match when echoed in If-Range.
                    ?.takeIf {
                        !it.startsWith("W/", ignoreCase = true) &&
                            it.length >= 2 && it.first() == '"' && it.last() == '"'
                    }
                val validator = strongEtag ?: connection.getHeaderField("Last-Modified")
                connection.inputStream.use { input -> input.read() }
                RangeProbe(
                    totalBytes = total,
                    supportsRanges = total > 0L && returnedRange == 0L..0L,
                    validator = validator,
                )
            } else {
                // Do not consume a server that ignored Range: disconnecting immediately
                // avoids downloading the full file twice.
                RangeProbe(
                    totalBytes = connection.contentLengthLong.coerceAtLeast(0L),
                    supportsRanges = false,
                    validator = null,
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadSingle(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        onProgress: (Long, Long, Int) -> Unit,
    ): DownloadPayload {
        val destination = File(tempDirectory, "part-0")
        val connection = openConnection(url, headers).apply { requestMethod = "GET" }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GET returned HTTP $code")
            val finalScheme = connection.url.protocol.lowercase()
            if (finalScheme != "http" && finalScheme != "https") {
                throw IOException("Download redirected to unsupported scheme")
            }

            val declaredTotal = connection.contentLengthLong.coerceAtLeast(0L)
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
            destination.delete()
            throw cancelled
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadParallel(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        totalBytes: Long,
        threadCount: Int,
        validator: String?,
        onProgress: (Long, Long, Int) -> Unit,
    ): DownloadPayload = coroutineScope {
        val ranges = DownloadRanges.split(totalBytes, threadCount)
        val parts = ranges.indices.map { index -> File(tempDirectory, "part-$index") }
        val downloaded = AtomicLong(0L)
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

        try {
            ranges.mapIndexed { index, range ->
                async(Dispatchers.IO) {
                    downloadRange(
                        url = url,
                        headers = headers,
                        destination = parts[index],
                        range = range,
                        totalBytes = totalBytes,
                        validator = validator,
                        downloaded = downloaded,
                        reportProgress = ::reportProgress,
                    )
                }
            }.awaitAll()
            reportProgress(force = true)
            DownloadPayload(parts, totalBytes, ranges.size)
        } catch (error: Exception) {
            parts.forEach(File::delete)
            throw error
        }
    }

    private suspend fun downloadRange(
        url: String,
        headers: DownloadRequestHeaders,
        destination: File,
        range: LongRange,
        totalBytes: Long,
        validator: String?,
        downloaded: AtomicLong,
        reportProgress: () -> Unit,
    ) {
        val connection = openConnection(url, headers).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            validator?.let { setRequestProperty("If-Range", it) }
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Range request returned HTTP ${connection.responseCode}")
            }
            val contentRange = connection.getHeaderField("Content-Range")
            val responseRange = parseContentRange(contentRange)
            val responseTotal = parseContentRangeTotal(contentRange)
            if (responseRange != range || responseTotal != totalBytes) {
                throw IOException("Unexpected Content-Range: $responseRange")
            }

            val expected = range.last - range.first + 1L
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        if (written > expected) throw IOException("Range response exceeded request")
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
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        headers: DownloadRequestHeaders,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
        headers.userAgent.takeIf { it.isNotBlank() }
            ?.let { setRequestProperty("User-Agent", it) }
        headers.cookie?.takeIf { it.isNotBlank() }
            ?.let { setRequestProperty("Cookie", it) }
        headers.referer?.takeIf { it.isNotBlank() }
            ?.let { setRequestProperty("Referer", it) }
    }

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
    )

    private companion object {
        const val TAG = "HttpDownloadEngine"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
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
