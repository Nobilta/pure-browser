package com.mybrowser.ui.download

import com.mybrowser.download.localizeDownloadDirectory
import com.mybrowser.download.DownloadItem
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.download.DownloadStatus
import com.mybrowser.download.DownloadFailure
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.BrowserDropdownMenu
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.formatBytes
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserMotion
import com.mybrowser.ui.shell.userItemMotion
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.LibrarySelectionBar
import com.mybrowser.ui.shell.libraryItemSelection
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
    onFocusConsumed: () -> Unit = {},
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
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    val visible = remember(downloads, query, filter) {
        downloads.filter { filter.matches(it.status) && (query.isBlank() || it.filename.contains(query.trim(), ignoreCase = true)) }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focusedId, query, filter, visible.map { it.id }) {
        val target = focusedId ?: return@LaunchedEffect
        if (query.isNotEmpty() || filter != DownloadFilter.ALL) {
            query = ""
            filter = DownloadFilter.ALL
            return@LaunchedEffect
        }
        val index = visible.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            onFocusConsumed()
        }
    }
    // A record can disappear while it is selected (cancelled, retried, deleted elsewhere).
    LaunchedEffect(visible.map { it.id }) {
        selected = selected.intersect(visible.map { it.id }.toSet())
        if (visible.isEmpty()) selecting = false
    }
    val leaveSelection = { selecting = false; selected = emptySet() }

    BrowserBottomSheet(
        onDismissRequest = { if (selecting) leaveSelection() else onDismiss() },
        dragHandleOnly = true,
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

            if (downloads.isNotEmpty()) {
                LibrarySearchField(query, stringResource(R.string.download_search), {
                    query = it; leaveSelection()
                }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadFilter.entries.forEach { option ->
                        BrowserActionPill(stringResource(option.label), {
                            filter = option; leaveSelection()
                        }, modifier = Modifier.semantics { this.selected = filter == option }, active = filter == option)
                    }
                }
            }

            LibrarySelectionBar(
                visible = selecting,
                allSelected = visible.isNotEmpty() && visible.all { it.id in selected },
                onToggleSelectAll = { checked ->
                    selected = if (checked) visible.map { it.id }.toSet() else emptySet()
                },
                countText = textResources.getString(R.string.ui_selected_count, selected.size),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                val chosen = downloads.filter { it.id in selected }
                BrowserIconAction(R.drawable.ic_ui_pause, stringResource(R.string.download_pause_selected), chosen.any { it.status.active && it.canPause }) {
                    chosen.filter { it.status.active && it.canPause }.forEach { onPauseDownload(it.id) }
                }
                BrowserIconAction(R.drawable.ic_ui_play, stringResource(R.string.download_resume_selected), chosen.any { it.canResume && (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) }) {
                    chosen.filter { it.canResume && (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) }.forEach { onRetryDownload(it.id) }
                }
                BrowserIconAction(R.drawable.ic_ui_delete, stringResource(R.string.cd_delete), selected.isNotEmpty()) {
                    confirmDeleteSelected = true
                }
                BrowserIconAction(R.drawable.ic_ui_close, stringResource(R.string.ui_close)) { leaveSelection() }
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
                        textResources.getQuantityString(R.plurals.download_blocked_overflow, overflowCount, overflowCount),
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
                                    painter = painterResource(R.drawable.ic_ui_close),
                                    contentDescription = textResources.getString(R.string.ui_close),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            if (visible.isEmpty()) {
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
                            painter = painterResource(R.drawable.ic_ui_download),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = textResources.getString(if (downloads.isEmpty()) R.string.ui_no_download_tasks else R.string.download_no_matches),
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
                    items(visible, key = { it.id }) { download ->
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
                                onDetails = { detailId = download.id },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    downloads.firstOrNull { it.id == detailId }?.let { download ->
        DownloadDetailsDialog(download) { detailId = null }
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

private enum class DownloadFilter(val label: Int) {
    ALL(R.string.download_filter_all), ACTIVE(R.string.download_filter_active),
    PAUSED(R.string.download_filter_paused), FAILED(R.string.download_filter_failed),
    COMPLETED(R.string.download_filter_completed);

    fun matches(status: DownloadStatus): Boolean = when (this) {
        ALL -> true
        ACTIVE -> status.active
        PAUSED -> status == DownloadStatus.PAUSED
        FAILED -> status == DownloadStatus.FAILED
        COMPLETED -> status == DownloadStatus.COMPLETED
    }
}

@Composable
private fun DownloadDetailsDialog(download: DownloadItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = localizedResources()
    val locale = resources.configuration.locales[0]
    val date = remember(download.timestamp, locale) {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale)
            .format(java.util.Date(download.timestamp))
    }
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_details)) },
        buttonsInColumn = true,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(download.filename, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (download.status) {
                    DownloadStatus.DOWNLOADING -> R.string.download_filter_active
                    DownloadStatus.COMPLETED -> R.string.download_filter_completed
                    DownloadStatus.PAUSED -> R.string.download_filter_paused
                    DownloadStatus.FAILED -> R.string.download_filter_failed
                    DownloadStatus.QUEUED -> R.string.download_queued
                    DownloadStatus.WAITING_NETWORK -> R.string.download_waiting_network
                    DownloadStatus.SAVING -> R.string.download_saving
                }))
                if (download.status == DownloadStatus.FAILED) Text(stringResource(when (download.failure) {
                    DownloadFailure.AUTHORIZATION -> R.string.download_error_authorization
                    DownloadFailure.NOT_FOUND -> R.string.download_error_not_found
                    DownloadFailure.SERVER -> R.string.download_error_server
                    DownloadFailure.STORAGE -> R.string.download_error_storage
                    DownloadFailure.SOURCE_UNAVAILABLE -> R.string.download_error_source
                    DownloadFailure.NETWORK, null -> R.string.download_error_network
                }), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.download_detail_progress, formatBytes(download.bytesDownloaded),
                    if (download.totalBytes > 0) formatBytes(download.totalBytes) else "—", download.threadCount))
                Text(stringResource(R.string.download_confirm_destination, localizeDownloadDirectory(resources, download.destinationLabel)))
                Text(stringResource(R.string.download_created, date))
                download.sourceUrl?.let { url ->
                    Text(stringResource(R.string.download_confirm_source), style = MaterialTheme.typography.labelMedium)
                    Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                download.sourceUrl?.let { url ->
                    BrowserActionPill(stringResource(R.string.download_copy_source), {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(resources.getString(R.string.download_confirm_source), url))
                    }, Modifier.fillMaxWidth(), iconRes = R.drawable.ic_ui_copy)
                }
                BrowserActionPill(stringResource(R.string.ui_close), onDismiss, Modifier.fillMaxWidth())
            }
        },
    )
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
    onDetails: () -> Unit,
) {
    val textResources = localizedResources()
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .libraryItemSelection(
                selecting = selecting,
                selected = selected,
                selectionLabel = stringResource(R.string.ui_select_downloads),
                onOpen = { if (download.status == DownloadStatus.COMPLETED) onOpen() else onDetails() },
                onToggleSelection = onToggleSelection,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(modifier = Modifier.width(8.dp))
        }
        // The status glyph: a download moving from running to done, or failing, changes what this
        // row means. Fading the glyph and its tint across says that happened, where swapping both on
        // one frame made the row look replaced.
        val statusIcon = when (download.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK, DownloadStatus.SAVING -> R.drawable.ic_ui_download
            DownloadStatus.COMPLETED -> R.drawable.ic_ui_file
            DownloadStatus.FAILED -> R.drawable.ic_ui_close
            DownloadStatus.PAUSED -> R.drawable.ic_ui_pause
        }
        AnimatedContent(
            targetState = statusIcon,
            transitionSpec = {
                fadeIn(BrowserMotion.contentCross()) togetherWith fadeOut(BrowserMotion.chromeFade())
            },
            label = "downloadStatusIcon",
        ) { icon ->
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = animateColorAsState(
                    targetValue = when (download.status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = BrowserMotion.contentCross(),
                    label = "downloadStatusTint",
                ).value,
            )
        }

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
            if (download.requiresNewRequest && (download.status == DownloadStatus.PAUSED || download.status == DownloadStatus.FAILED)) {
                Text(textResources.getString(R.string.download_response_retry), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!selecting) {
            if (download.status.active && download.canPause) {
                BrowserIconAction(R.drawable.ic_ui_pause, stringResource(R.string.download_pause), onClick = onPause)
            } else if (download.status == DownloadStatus.COMPLETED) {
                BrowserIconAction(R.drawable.ic_ui_open_in_new, stringResource(R.string.download_open), onClick = onOpen)
            } else if (download.canResume && (download.status == DownloadStatus.PAUSED || download.status == DownloadStatus.FAILED)) {
                BrowserIconAction(R.drawable.ic_ui_play,
                    stringResource(if (download.status == DownloadStatus.PAUSED && !download.requiresNewRequest) R.string.download_resume else R.string.ui_retry), onClick = onRetry)
            }
            Box {
                BrowserIconAction(R.drawable.ic_ui_more, stringResource(R.string.download_more)) { expanded = true }
                BrowserDropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.download_details)) }, onClick = { expanded = false; onDetails() })
                    if (download.status == DownloadStatus.COMPLETED && download.canResume) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.download_redownload)) }, onClick = { expanded = false; onRetry() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(if (download.status.active) R.string.ui_cancel_download else R.string.ui_delete_download_record)) },
                        onClick = { expanded = false; if (download.status.active) onCancel() else onDelete() })
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
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        buttonsInColumn = true,
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserActionPill(textResources.getString(R.string.ui_delete_records_only), { onConfirm(false) }, Modifier.fillMaxWidth())
                BrowserActionPill(textResources.getString(R.string.ui_delete_records_and_files), { onConfirm(true) }, Modifier.fillMaxWidth())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(textResources.getString(R.string.action_cancel)) }
        },
    )
}
