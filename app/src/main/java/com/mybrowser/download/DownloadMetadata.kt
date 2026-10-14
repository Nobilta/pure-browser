package com.mybrowser.download

internal enum class DownloadBackend { LEGACY_SYSTEM, LOCAL }

internal data class DownloadMetadata(
    val id: Long,
    val backend: DownloadBackend,
    val url: String,
    val userAgent: String,
    val cookie: String?,
    val contentDisposition: String?,
    val mimeType: String,
    val filename: String,
    val referer: String?,
    val timestamp: Long,
    val status: DownloadStatus,
    /** Who this task belongs to; every mode-dependent policy is read from it. */
    val identity: DownloadIdentity = DownloadIdentity.Normal,
    val unmeteredOnly: Boolean = false,
    val bytesPerSecond: Long = 0,
    val savingProgress: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val configuredThreadCount: Int = 1,
    val actualThreadCount: Int = 1,
    val destinationMode: DownloadDestinationMode = DownloadDestinationMode.SYSTEM_DOWNLOADS,
    val customTreeUri: String? = null,
    val destinationLabel: String = SYSTEM_DIRECTORY_LABEL,
    val destinationUri: String? = null,
)
