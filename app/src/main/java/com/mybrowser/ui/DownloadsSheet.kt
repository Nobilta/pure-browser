package com.mybrowser.ui

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
    val destinationLabel: String = "系统下载目录",
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
                    text = "下载管理",
                    style = MaterialTheme.typography.titleLarge
                )

                if (downloads.isEmpty()) {
                    Text(
                        text = "暂无下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${downloads.size} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val hasTerminal = downloads.any {
                            it.status == DownloadStatus.COMPLETED ||
                                it.status == DownloadStatus.FAILED
                        }
                        if (hasTerminal) {
                            TextButton(onClick = { confirmClearCompleted = true }) {
                                Text("清除")
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
                            text = "暂无下载任务",
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
            title = "删除下载记录？",
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
            title = "清除已完成记录？",
            message = "将清除已完成和失败的下载记录。",
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
                        text = "${download.progress}% · ${download.threadCount} 线程 · ${formatBytes(download.bytesDownloaded)} / ${formatBytes(download.totalBytes)}",
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
                        text = "已完成 · ${formatBytes(download.totalBytes)} · ${download.destinationLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = "下载失败 · ${download.destinationLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                DownloadStatus.PAUSED -> {
                    Text(
                        text = "下载已中断 · ${download.progress}% · 可重试",
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
                        contentDescription = "取消下载"
                    )
                }
            }
            DownloadStatus.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "删除下载",
                        )
                    }
                }
            }
            DownloadStatus.PAUSED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "删除下载",
                        )
                    }
                }
            }
            DownloadStatus.COMPLETED -> {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "删除下载",
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
                        Text("同时删除本地文件")
                        Text(
                            text = "关闭后只从 Pure 浏览器中移除记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFiles) }) {
                Text(if (deleteFiles) "删除记录和文件" else "仅删除记录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
