package com.mybrowser.ui.download

import com.mybrowser.download.localizeDownloadDirectory
import com.mybrowser.download.DownloadItem
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.download.DownloadStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserMotion
import com.mybrowser.ui.shell.userItemMotion
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.shell.BrowserAlertDialog

/**
 * Downloads management sheet showing active and completed downloads.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    blocked: List<DownloadRequestCoordinator.BlockedEntry> = emptyList(),
    overflowCount: Int = 0,
    onUnblockDownload: (String) -> Unit = {},
    onDismissBlocked: (String) -> Unit = {},
) {
    val textResources = localizedResources()
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClearCompleted by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focusedId, downloads.map { it.id }) {
        val index = downloads.indexOfFirst { it.id == focusedId }
        if (index >= 0) listState.scrollToItem(index)
    }
    // A record can disappear while it is selected (cancelled, retried, deleted elsewhere).
    LaunchedEffect(downloads.map { it.id }) {
        selected = selected.intersect(downloads.map { it.id }.toSet())
        if (downloads.isEmpty()) selecting = false
    }
    val leaveSelection = { selecting = false; selected = emptySet() }

    BrowserBottomSheet(
        onDismissRequest = { if (selecting) leaveSelection() else onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            BrowserSheetHeader(textResources.getString(R.string.ui_download_manager),
                onBack = { if (selecting) leaveSelection() else onDismiss() }) {
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
                        if (hasTerminal && !selecting) {
                            TextButton(onClick = { confirmClearCompleted = true }) {
                                Text(textResources.getString(R.string.cd_clear))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                    expandVertically(animationSpec = BrowserMotion.chromeShow),
                exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                    shrinkVertically(animationSpec = BrowserMotion.chromeHide),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = downloads.isNotEmpty() && downloads.all { it.id in selected },
                        onCheckedChange = { checked ->
                            selected = if (checked) downloads.map { it.id }.toSet() else emptySet()
                        },
                    )
                    Text(textResources.getString(R.string.ui_selected_count, selected.size), Modifier.weight(1f))
                    BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.cd_delete), selected.isNotEmpty()) {
                        confirmDeleteSelected = true
                    }
                    BrowserIconAction(R.drawable.ic_close, stringResource(R.string.ui_close)) { leaveSelection() }
                }
            }

            if (blocked.isNotEmpty() || overflowCount > 0) {
                // Requests the coordinator did not show a dialog for. The user picks one
                // to request it explicitly; nothing here started a transfer.
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = textResources.getString(R.string.download_blocked_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    if (overflowCount > 0) Text(
                        textResources.getString(R.string.download_blocked_overflow, overflowCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    blocked.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.request.filename,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = textResources.getString(
                                        when (entry.reason) {
                                            DownloadRequestCoordinator.BlockedReason.REJECTED -> R.string.download_blocked_rejected
                                            DownloadRequestCoordinator.BlockedReason.LIMIT -> R.string.download_blocked_limit
                                            DownloadRequestCoordinator.BlockedReason.BUDGET -> R.string.download_blocked_budget
                                            DownloadRequestCoordinator.BlockedReason.EXISTING -> R.string.download_blocked_existing
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onUnblockDownload(entry.identity) }) {
                                Text(textResources.getString(R.string.download_blocked_request))
                            }
                            IconButton(onClick = { onDismissBlocked(entry.identity) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = textResources.getString(R.string.ui_close),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

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
                        Column(userItemMotion()) {
                            DownloadItemRow(
                                download = download,
                                selecting = selecting,
                                selected = download.id in selected,
                                onToggleSelection = {
                                    selecting = true
                                    selected = if (download.id in selected) selected - download.id
                                    else selected + download.id
                                },
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

    if (confirmDeleteSelected) {
        val count = selected.size
        DeleteDownloadDialog(
            title = textResources.getString(R.string.ui_delete_download_record),
            message = textResources.getString(R.string.ui_selected_count, count),
            onConfirm = { deleteFiles ->
                confirmDeleteSelected = false
                selected.forEach { id -> onDeleteDownload(id, deleteFiles) }
                leaveSelection()
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
}

@Composable
private fun DownloadItemRow(
    download: DownloadItem,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
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
            .combinedClickable(
                onClick = {
                    if (selecting) onToggleSelection()
                    else if (download.status == DownloadStatus.COMPLETED) onOpen()
                },
                onLongClick = onToggleSelection,
                onLongClickLabel = stringResource(R.string.ui_select_downloads),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            Spacer(modifier = Modifier.width(8.dp))
        }
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
        if (!selecting) when (download.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK, DownloadStatus.SAVING -> {
                Column {
                    if (download.canPause) BrowserIconAction(R.drawable.ic_pause, stringResource(R.string.download_pause), onClick = onPause)
                    BrowserIconAction(R.drawable.ic_close, textResources.getString(R.string.ui_cancel_download), onClick = onCancel)
                }
            }
            DownloadStatus.FAILED, DownloadStatus.PAUSED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A task of another private session cannot be continued here, and offering the
                    // button would only report a failure. View, open and delete stay available.
                    if (download.canResume) {
                        TextButton(onClick = onRetry) {
                            Text(textResources.getString(if (download.status == DownloadStatus.PAUSED) R.string.download_resume else R.string.ui_retry))
                        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A finished URL can be fetched again by explicit choice; the file
                    // may be gone from the file manager, or the server may have newer
                    // bytes. Page-driven repeats never reach this path.
                    if (download.canResume) {
                        TextButton(onClick = onRetry) {
                            Text(textResources.getString(R.string.download_redownload))
                        }
                    }
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
    BrowserAlertDialog(
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
