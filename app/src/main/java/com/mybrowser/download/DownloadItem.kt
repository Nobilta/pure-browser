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
    /**
     * False for a private-session task: the running notification shows a generic title instead,
     * because it outlives the session and is readable from the lock screen.
     */
    val showsFilenameInNotification: Boolean = true,
    /**
     * False when this task belongs to another private session. It can be viewed, opened and
     * deleted, but neither continued nor fetched again here — its session's credentials are gone
     * and its persisted URL no longer carries the query — so the UI offers no action that could
     * only answer with a failure.
     */
    val canResume: Boolean = true,
)

enum class DownloadStatus { DOWNLOADING, COMPLETED, FAILED, PAUSED, QUEUED, WAITING_NETWORK, SAVING;
    val active: Boolean get() = this == DOWNLOADING || this == QUEUED || this == WAITING_NETWORK || this == SAVING
}
