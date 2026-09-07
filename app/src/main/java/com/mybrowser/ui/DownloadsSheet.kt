package com.mybrowser.ui

import com.mybrowser.download.localizeDownloadDirectory
import com.mybrowser.download.SYSTEM_DIRECTORY_LABEL
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import java.text.DecimalFormat

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
)

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

/**
 * Downloads management sheet showing active and completed downloads.
 */
@Composable
fun DownloadsSheet(
    downloads: List<DownloadItem>,
    onDismiss: () -> Unit,
    onCancelDownload: (Long) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onOpenFile: (Long) -> Unit = {},
    onDeleteDownload: (Long, deleteFile: Boolean) -> Unit = { _, _ -> },
    onClearCompleted: (deleteFiles: Boolean) -> Unit = {},
) {
    val textResources = localizedResources()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClearCompleted by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = textResources.getString(R.string.ui_download_manager),
                    style = MaterialTheme.typography.titleLarge
                )

                if (downloads.isEmpty()) {
                    Text(
                        text = textResources.getString(R.string.ui_no_downloads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = textResources.getString(R.string.ui_items, downloads.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val hasTerminal = downloads.any {
                            it.status == DownloadStatus.COMPLETED ||
                                it.status == DownloadStatus.FAILED
                        }
                        if (hasTerminal) {
                            TextButton(onClick = { confirmClearCompleted = true }) {
                                Text(textResources.getString(R.string.cd_clear))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            if (downloads.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = textResources.getString(R.string.ui_no_download_tasks),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Downloads list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep the sheet header and actions visible when DownloadManager
                        // has accumulated many tasks.
                        .heightIn(max = 600.dp),
                ) {
                    items(downloads) { download ->
                        DownloadItemRow(
                            download = download,
                            onCancel = { onCancelDownload(download.id) },
                            onRetry = { onRetryDownload(download.id) },
                            onOpen = { onOpenFile(download.id) },
                            onDelete = { pendingDelete = download },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { download ->
        DeleteDownloadDialog(
            title = textResources.getString(R.string.ui_delete_download_record),
            message = download.filename,
            onConfirm = { deleteFile ->
                pendingDelete = null
                onDeleteDownload(download.id, deleteFile)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (confirmClearCompleted) {
        DeleteDownloadDialog(
            title = textResources.getString(R.string.ui_clear_finished_records),
            message = textResources.getString(R.string.ui_completed_and_failed_download_records_will_be_cleared),
            onConfirm = { deleteFiles ->
                confirmClearCompleted = false
                onClearCompleted(deleteFiles)
            },
            onDismiss = { confirmClearCompleted = false },
        )
    }
}

@Composable
private fun DownloadItemRow(
    download: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val textResources = localizedResources()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = download.status == DownloadStatus.COMPLETED,
                onClick = onOpen
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon based on status
        Icon(
            painter = painterResource(
                when (download.status) {
                    DownloadStatus.DOWNLOADING -> R.drawable.ic_history
                    DownloadStatus.COMPLETED -> R.drawable.ic_bookmark
                    DownloadStatus.FAILED -> R.drawable.ic_close
                    DownloadStatus.PAUSED -> R.drawable.ic_incognito
                }
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = when (download.status) {
                DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = download.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            when (download.status) {
                DownloadStatus.DOWNLOADING -> {
                    Text(
                        text = textResources.getString(R.string.ui_connections, download.progress, download.threadCount, formatBytes(download.bytesDownloaded), formatBytes(download.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    LinearProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DownloadStatus.COMPLETED -> {
                    Text(
                        text = textResources.getString(R.string.ui_completed, formatBytes(download.totalBytes), localizeDownloadDirectory(textResources, download.destinationLabel)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = textResources.getString(R.string.ui_download_failed, localizeDownloadDirectory(textResources, download.destinationLabel)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                DownloadStatus.PAUSED -> {
                    Text(
                        text = textResources.getString(R.string.ui_download_interrupted_retry_available, download.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Action button
        when (download.status) {
            DownloadStatus.DOWNLOADING -> {
                IconButton(onClick = onCancel) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = textResources.getString(R.string.ui_cancel_download)
                    )
                }
            }
            DownloadStatus.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Text(textResources.getString(R.string.ui_retry))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = textResources.getString(R.string.ui_delete_download),
                        )
                    }
                }
            }
            DownloadStatus.PAUSED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Text(textResources.getString(R.string.ui_retry))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = textResources.getString(R.string.ui_delete_download),
                        )
                    }
                }
            }
            DownloadStatus.COMPLETED -> {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = textResources.getString(R.string.ui_delete_download),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteDownloadDialog(
    title: String,
    message: String,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val textResources = localizedResources()
    var deleteFiles by remember(title, message) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFiles = !deleteFiles },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = { deleteFiles = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(textResources.getString(R.string.ui_also_delete_local_files))
                        Text(
                            text = textResources.getString(R.string.ui_when_off_only_records_are_removed_from_pure),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFiles) }) {
                Text(if (deleteFiles) textResources.getString(R.string.ui_delete_records_and_files) else textResources.getString(R.string.ui_delete_records_only))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(textResources.getString(R.string.action_cancel)) }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0))
        .toInt()
        .coerceIn(0, units.lastIndex)

    val format = DecimalFormat("#,##0.#")
    return format.format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}
