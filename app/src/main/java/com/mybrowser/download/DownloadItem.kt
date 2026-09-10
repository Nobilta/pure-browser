package com.mybrowser.download

/** Transfer state shared by the service, notification and downloads UI. */
data class DownloadItem(
    val id: Long,
    val filename: String,
    val url: String,
    val status: DownloadStatus,
    val progress: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val threadCount: Int = 1,
    val destinationLabel: String = SYSTEM_DIRECTORY_LABEL,
    val canPause: Boolean = false,
    val bytesPerSecond: Long = 0,
    val savingProgress: Int = 0,
)

enum class DownloadStatus { DOWNLOADING, COMPLETED, FAILED, PAUSED, QUEUED, WAITING_NETWORK, SAVING;
    val active: Boolean get() = this == DOWNLOADING || this == QUEUED || this == WAITING_NETWORK || this == SAVING
}
