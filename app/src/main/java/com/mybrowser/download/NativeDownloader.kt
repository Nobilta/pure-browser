package com.mybrowser.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native multi-threaded downloader with fallback to single-threaded download.
 *
 * Uses Rust implementation when available for:
 * - 4-thread concurrent chunk downloads
 * - Automatic resume support
 * - Progress callbacks
 */
class NativeDownloader(private val context: Context) {

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int
    )

    /**
     * Download a file from URL to destination.
     *
     * @param url Source URL
     * @param destination Destination file path
     * @param onProgress Progress callback (called on main thread)
     * @return true if download succeeded
     */
    suspend fun download(
        url: String,
        destination: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val scheme = URL(url).protocol.lowercase()
            if (scheme != "http" && scheme != "https") return@withContext false

            // The JNI primitive is synchronous and cannot report progress. Use it only
            // for callers that do not request progress, and fall back to the checked
            // Kotlin transport if the native attempt fails.
            if (isAvailable && onProgress == null) {
                if (downloadNative(url, destination)) return@withContext true
                File(destination).delete()
            }

            return@withContext downloadFallback(url, destination, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadNative(url: String, destination: String): Boolean =
        withContext(Dispatchers.IO) {
        // The JNI entry point is synchronous by design; this method is already running on
        // Dispatchers.IO, so it cannot block the UI. Progress is not exposed by the legacy
        // ABI, and callers that need durable progress should use DownloadHandler instead.
        runCatching { nativeDownload(url, destination, DEFAULT_THREADS) }
            .getOrDefault(false)
        }

    private suspend fun downloadFallback(
        url: String,
        destination: String,
        onProgress: ((DownloadProgress) -> Unit)?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destination)
            destFile.parentFile?.mkdirs()

            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "PureBrowser/1.0")
                if (connection.responseCode !in 200..299) return@withContext false

                val totalBytes = connection.contentLengthLong
                var bytesDownloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            if (bytesRead == 0) continue
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            onProgress?.let { callback ->
                                val percentage = if (totalBytes > 0) {
                                    (bytesDownloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                                } else 0
                                withContext(Dispatchers.Main) {
                                    callback(DownloadProgress(bytesDownloaded, totalBytes, percentage))
                                }
                            }
                        }
                    }
                }
                onProgress?.let { callback ->
                    withContext(Dispatchers.Main) {
                        callback(DownloadProgress(bytesDownloaded, totalBytes, 100))
                    }
                }
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback download failed", e)
            false
        }
    }

    /**
     * Multi-threaded download with chunk splitting.
     * Downloads file in 4 concurrent chunks for faster throughput.
     */
    suspend fun downloadMultiThreaded(
        url: String,
        destination: String,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Keep progress semantics deterministic: the native/parallel path has no
            // byte-level callback, so callers asking for progress use the streaming path.
            if (onProgress != null) return@withContext downloadFallback(url, destination, onProgress)
            val destFile = File(destination)
            destFile.parentFile?.mkdirs()

            // Check if server supports range requests
            val connection = URL(url).openConnection() as HttpURLConnection
            val totalBytes: Long
            val acceptRanges: Boolean
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                if (connection.responseCode !in 200..299) return@withContext false
                totalBytes = connection.contentLengthLong
                acceptRanges = connection.getHeaderField("Accept-Ranges")
                    ?.equals("bytes", ignoreCase = true) == true
            } finally {
                connection.disconnect()
            }

            if (!acceptRanges || totalBytes <= 0) {
                // Fallback to single-threaded
                return@withContext downloadFallback(url, destination, onProgress)
            }

            // Split into 4 chunks
            // Keep the calculation in Long until the final bounded conversion. Calling
            // toInt() first makes files larger than Int.MAX_VALUE wrap negative and
            // silently collapse a parallel download to one chunk.
            val numThreads = minOf(DEFAULT_THREADS.toLong(), totalBytes)
                .toInt()
                .coerceAtLeast(1)
            val chunkSize = (totalBytes + numThreads - 1) / numThreads
            val chunks = mutableListOf<Pair<Long, Long>>()

            for (i in 0 until numThreads) {
                val start = i * chunkSize
                if (start >= totalBytes) break
                val end = minOf(totalBytes - 1, (i + 1) * chunkSize - 1)
                chunks += start to end
            }

            val chunkFiles = chunks.mapIndexed { index, _ -> File("$destination.part$index") }
            val results = coroutineScope {
                chunks.mapIndexed { index, (start, end) ->
                    async(Dispatchers.IO) {
                        downloadChunk(url, chunkFiles[index], start, end)
                    }
                }.awaitAll()
            }
            if (results.any { !it }) {
                chunkFiles.forEach(File::delete)
                File(destination).delete()
                return@withContext false
            }

            // Merge chunks
            FileOutputStream(destFile).use { output ->
                chunkFiles.forEach { chunkFile ->
                    chunkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    chunkFile.delete()
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Multi-threaded download failed", e)
            // Cleanup partial files
            File(destination).delete()
            false
        }
    }

    private suspend fun downloadChunk(
        url: String,
        destination: File,
        start: Long,
        end: Long
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("User-Agent", "PureBrowser/1.0")
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return@withContext false

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val copied = input.copyTo(output)
                    if (copied != end - start + 1) return@withContext false
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Chunk download failed: $start-$end", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "NativeDownloader"
        private const val DEFAULT_THREADS = 4

        /** Whether native downloader is available */
        val isAvailable: Boolean = try {
            System.loadLibrary("mybrowser_downloader")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native downloader unavailable, using fallback", e)
            false
        }

        @JvmStatic
        private external fun nativeDownload(
            url: String,
            outputPath: String,
            threadCount: Int,
        ): Boolean
    }
}
