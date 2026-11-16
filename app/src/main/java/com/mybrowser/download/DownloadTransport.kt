package com.mybrowser.download

import java.io.File

/**
 * The half of a task that turns an address into bytes.
 *
 * Everything the rest of the task does — the confirmation, the temporary parts, publishing to
 * MediaStore or a tree, the terminal record, the notification and the way the file opens — is one
 * implementation for every source. Only "where do the bytes come from" differs, so this is the seam:
 * an HTTP range transfer, one FTP stream and a playlist's segments all leave the same thing behind,
 * parts in the task's temporary directory plus the total they add up to.
 *
 * Implementations own their temporary state. A transport that can resume reads what an interrupted
 * attempt left in [tempDirectory]; one that cannot simply overwrites it.
 */
internal interface DownloadTransport {
    suspend fun download(
        url: String,
        headers: DownloadRequestHeaders,
        requestedThreads: Int,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
    ): DownloadPayload
}

/**
 * Progress at the cadence a task row can use.
 *
 * The HTTP engine reports at most five times a second; a transport that copies its own bytes
 * (FTP) or walks a playlist (HLS) has to hold the same line, because every report republishes the
 * task snapshot and rewrites the list.
 */
internal class TransferProgress(private val report: (Long, Long, Int) -> Unit) {
    private var lastNanos = 0L

    fun update(downloaded: Long, total: Long, threads: Int = 1, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastNanos < INTERVAL_NANOS) return
        lastNanos = now
        report(downloaded, total, threads)
    }

    private companion object {
        const val INTERVAL_NANOS = 200_000_000L
    }
}
