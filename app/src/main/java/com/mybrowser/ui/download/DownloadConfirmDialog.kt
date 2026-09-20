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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.download.DownloadRequestCoordinator
import com.mybrowser.ui.shell.BrowserAlertDialog
import com.mybrowser.ui.shell.localizedResources

/** Android package MIME type gets an explicit label in the confirmation. */
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * The single download confirmation. Nothing starts until the user picks 下载;
 * the request context behind it lives in memory only.
 */
@Composable
fun DownloadConfirmDialog(
    request: DownloadRequestCoordinator.Request,
    destinationLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val textResources = localizedResources()
    BrowserAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(textResources.getString(R.string.download_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = request.filename,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (request.mimeType == APK_MIME) {
                    Text(
                        text = textResources.getString(R.string.download_confirm_apk),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, it))
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
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.download_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
