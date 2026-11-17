package com.mybrowser.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.mybrowser.ui.components.BrowserTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import android.content.Intent
import com.mybrowser.download.DownloadDestination
import com.mybrowser.download.DownloadSettings
import com.mybrowser.download.DownloadSettingsRepository
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.shell.BrowserDropdownMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    settings: DownloadSettings,
    onConfirm: (retryResponse: Boolean, filename: String, destination: DownloadDestination) -> Unit,
    onCancel: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val textResources = localizedResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filename by rememberSaveable(request.identity) { mutableStateOf(request.filename) }
    var mode by rememberSaveable(request.identity) { mutableStateOf(settings.destinationMode) }
    var treeUri by rememberSaveable(request.identity) { mutableStateOf(settings.customTreeUri) }
    var treeLabel by rememberSaveable(request.identity) { mutableStateOf(settings.customDirectoryLabel) }
    var choosingDirectory by remember { mutableStateOf(false) }
    val destination = DownloadDestination(mode, treeUri, DownloadDestination.labelFor(mode, treeLabel))
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val label = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    DownloadSettingsRepository.describeDirectory(context, uri)
                }.getOrNull()
            }
            if (label != null) {
                treeUri = uri.toString()
                treeLabel = label
                mode = DownloadDestinationMode.CUSTOM_DIRECTORY
            } else onMessage(textResources.getString(R.string.ui_unable_to_use_the_selected_download_folder))
        }
    }
    val responseUnavailable = request.response?.unavailable?.collectAsState()?.value
    BrowserAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(textResources.getString(if (isNewCopy) R.string.download_copy_title else R.string.download_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserTextField(
                    value = filename,
                    onValueChange = { filename = it.take(127) },
                    label = stringResource(R.string.download_filename),
                    modifier = Modifier.fillMaxWidth(),
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
                    text = textResources.getString(R.string.download_confirm_destination, destination.displayDestinationLabel(textResources)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Box {
                    BrowserActionPill(stringResource(R.string.download_choose_destination), { choosingDirectory = true })
                    BrowserDropdownMenu(choosingDirectory, { choosingDirectory = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_system_downloads_folder)) }, onClick = {
                            mode = DownloadDestinationMode.SYSTEM_DOWNLOADS
                            choosingDirectory = false
                        })
                        if (settings.destinationMode == DownloadDestinationMode.CUSTOM_DIRECTORY) DropdownMenuItem(
                            text = { Text(settings.displayDestinationLabel(textResources)) },
                            onClick = {
                                mode = settings.destinationMode; treeUri = settings.customTreeUri; treeLabel = settings.customDirectoryLabel
                                choosingDirectory = false
                            },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_custom_folder)) }, onClick = {
                            choosingDirectory = false
                            directoryPicker.launch(treeUri?.toUri())
                        })
                    }
                }
            }
        },
        confirmButton = {
            // Capture the displayed action; expiry between rendering Download and the tap
            // must not silently authorize a new request.
            TextButton(enabled = filename.isNotBlank(), onClick = { onConfirm(responseUnavailable != null, filename, destination) }) {
                Text(stringResource(if (responseUnavailable != null) R.string.ui_retry else if (isNewCopy) R.string.download_copy_action else R.string.download_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
