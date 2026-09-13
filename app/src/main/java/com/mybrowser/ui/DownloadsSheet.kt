package com.mybrowser.ui

import com.mybrowser.download.localizeDownloadDirectory
import com.mybrowser.download.DownloadItem
import com.mybrowser.download.DownloadStatus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import java.text.DecimalFormat

/**
 * Downloads management sheet showing active and completed downloads.
 */
@Composable
fun DownloadsSheet(
    downloads: List<DownloadItem>,
    onDismiss: () -> Unit,
    onCancelDownload: (Long) -> Unit = {},
    onPauseDownload: (Long) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onOpenFile: (Long) -> Unit = {},
    onDeleteDownload: (Long, deleteFile: Boolean) -> Unit = { _, _ -> },
    onClearCompleted: (deleteFiles: Boolean) -> Unit = {},
    focusedId: Long? = null,
) {
    val textResources = localizedResources()
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClearCompleted by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focusedId, downloads.map { it.id }) {
        val index = downloads.indexOfFirst { it.id == focusedId }
        if (index >= 0) listState.scrollToItem(index)
    }

    BrowserBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            BrowserSheetHeader(textResources.getString(R.string.ui_download_manager), onBack = onDismiss) {
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
                            painter = painterResource(R.drawable.ic_download),
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
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep the sheet header and actions visible when DownloadManager
                        // has accumulated many tasks.
                        .weight(1f, fill = false),
                ) {
                    items(downloads, key = { it.id }) { download ->
                        DownloadItemRow(
                            download = download,
                            onCancel = { onCancelDownload(download.id) },
                            onPause = { onPauseDownload(download.id) },
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
    onPause: () -> Unit,
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
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK, DownloadStatus.SAVING -> R.drawable.ic_download
                    DownloadStatus.COMPLETED -> R.drawable.ic_file
                    DownloadStatus.FAILED -> R.drawable.ic_close
                    DownloadStatus.PAUSED -> R.drawable.ic_pause
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            when (download.status) {
                DownloadStatus.DOWNLOADING -> {
                    Text(
                        text = if (download.totalBytes > 0) textResources.getString(R.string.ui_connections, download.progress, download.threadCount, formatBytes(download.bytesDownloaded), formatBytes(download.totalBytes))
                        else textResources.getString(R.string.download_progress_unknown, formatBytes(download.bytesDownloaded), download.threadCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    if (download.bytesPerSecond > 0) Text(textResources.getString(R.string.download_speed_eta,
                        formatBytes(download.bytesPerSecond),
                        if (download.totalBytes > 0) ((download.totalBytes - download.bytesDownloaded).coerceAtLeast(0) / download.bytesPerSecond).toString() else "—"),
                        style = MaterialTheme.typography.bodySmall)
                    if (download.totalBytes <= 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(
                        progress = { download.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK, DownloadStatus.SAVING -> {
                    Text(stringResource(when(download.status) {
                        DownloadStatus.SAVING -> R.string.download_saving
                        DownloadStatus.WAITING_NETWORK -> R.string.download_waiting_network
                        else -> R.string.download_queued
                    }), style = MaterialTheme.typography.bodySmall)
                    if (download.status == DownloadStatus.SAVING) LinearProgressIndicator(progress = { download.savingProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK, DownloadStatus.SAVING -> {
                Column {
                    if (download.canPause) BrowserIconAction(R.drawable.ic_pause, stringResource(R.string.download_pause), onClick = onPause)
                    BrowserIconAction(R.drawable.ic_close, textResources.getString(R.string.ui_cancel_download), onClick = onCancel)
                }
            }
            DownloadStatus.FAILED, DownloadStatus.PAUSED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Text(textResources.getString(if (download.status == DownloadStatus.PAUSED) R.string.download_resume else R.string.ui_retry))
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
