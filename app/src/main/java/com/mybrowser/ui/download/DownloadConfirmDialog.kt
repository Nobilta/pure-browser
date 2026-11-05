package com.mybrowser.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.ui.shell.BrowserAlertDialog
import com.mybrowser.ui.shell.localizedResources

/**
 * The single download confirmation. Nothing starts until the user picks 下载;
 * the request context behind it lives in memory only.
 */
@Composable
fun DownloadConfirmDialog(
    request: DownloadRequestCoordinator.Request,
    isNewCopy: Boolean,
    destinationLabel: String,
    onConfirm: (retryResponse: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val textResources = localizedResources()
    val responseUnavailable = request.response?.unavailable?.collectAsState()?.value
    BrowserAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(textResources.getString(if (isNewCopy) R.string.download_copy_title else R.string.download_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = request.filename,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isNewCopy) {
                    Text(textResources.getString(R.string.download_copy_note), style = MaterialTheme.typography.bodySmall)
                }
                if (request.response != null) {
                    Text(textResources.getString(when (responseUnavailable) {
                        com.mybrowser.download.DownloadResponse.Unavailable.NETWORK -> R.string.download_response_network
                        com.mybrowser.download.DownloadResponse.Unavailable.EXPIRED -> R.string.download_response_expired
                        null -> R.string.download_response_note
                    }), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = textResources.getString(R.string.download_confirm_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = request.sourceOrigin ?: request.referer ?: request.url.substringBefore('#'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = request.contentLength?.let {
                        textResources.getString(R.string.download_confirm_size,
                            com.mybrowser.core.formatBytes(it))
                    } ?: textResources.getString(R.string.download_confirm_size_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = textResources.getString(R.string.download_confirm_destination, destinationLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Capture the displayed action; expiry between rendering Download and the tap
            // must not silently authorize a new request.
            TextButton(onClick = { onConfirm(responseUnavailable != null) }) {
                Text(stringResource(if (responseUnavailable != null) R.string.ui_retry else if (isNewCopy) R.string.download_copy_action else R.string.download_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
