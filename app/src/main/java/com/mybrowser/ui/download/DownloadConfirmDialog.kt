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
import com.mybrowser.download.DownloadDestination
import com.mybrowser.download.DownloadValidation
import com.mybrowser.download.DownloadSettings
import com.mybrowser.download.DownloadSettingsRepository
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.shell.BrowserDropdownMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
    directory: com.mybrowser.download.DownloadHandler.DirectorySelection,
    onConfirm: (retryResponse: Boolean, filename: String, destination: DownloadDestination) -> Unit,
    onCancel: () -> Unit,
) {
    val textResources = localizedResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filename by rememberSaveable(request.identity) { mutableStateOf(request.filename) }
    var mode by rememberSaveable(request.identity) { mutableStateOf(directory.destination?.mode ?: settings.destinationMode) }
    var treeUri by rememberSaveable(request.identity) { mutableStateOf(directory.destination?.treeUri ?: settings.customTreeUri) }
    var treeLabel by rememberSaveable(request.identity) { mutableStateOf(directory.destination?.label ?: settings.customDirectoryLabel) }
    var choosingDirectory by remember { mutableStateOf(false) }
    var directoryError by remember(request.identity) { mutableStateOf(false) }
    var customDirectoryAvailable by remember(request.identity) { mutableStateOf(false) }
    var checkingDirectory by remember(request.identity) { mutableStateOf(true) }
    var confirming by remember(request.identity) { mutableStateOf(false) }
    var directoryChoice by remember { mutableIntStateOf(0) }
    LaunchedEffect(request.identity, treeUri, directoryChoice) {
        checkingDirectory = true
        try {
            val chosen = treeUri?.let { directory.select(it.toUri()) }
            customDirectoryAvailable = chosen != null
            if (chosen != null) {
                treeLabel = chosen.label
                directoryError = false
            }
        } catch (_: SecurityException) {
            customDirectoryAvailable = false
            directoryError = mode == DownloadDestinationMode.CUSTOM_DIRECTORY
        } finally {
            if (isActive) checkingDirectory = false
        }
    }
    val destination = DownloadDestination(
        mode,
        treeUri.takeIf { mode == DownloadDestinationMode.CUSTOM_DIRECTORY },
        DownloadDestination.labelFor(mode, treeLabel),
    )
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            checkingDirectory = true
            treeUri = uri.toString()
            mode = DownloadDestinationMode.CUSTOM_DIRECTORY
            directoryChoice++ // Reauthorizing the same URI must also check its new grant.
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
                androidx.compose.material3.Surface(color = com.mybrowser.ui.theme.BrowserColors.recessedWell,
                    shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = textResources.getString(R.string.download_confirm_source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = DownloadValidation.displaySource(request.sourceOrigin, request.referer, request.url),
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
                    }
                }
                androidx.compose.foundation.layout.Box {
                    BrowserActionPill(stringResource(R.string.download_choose_destination), { choosingDirectory = true },
                        enabled = !checkingDirectory && !confirming)
                    BrowserDropdownMenu(choosingDirectory, { choosingDirectory = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_system_downloads_folder)) }, onClick = {
                            directoryError = false
                            mode = DownloadDestinationMode.SYSTEM_DOWNLOADS
                            choosingDirectory = false
                        })
                        if (customDirectoryAvailable) DropdownMenuItem(
                            text = { Text(com.mybrowser.download.localizeDownloadDirectory(textResources, DownloadDestination.labelFor(DownloadDestinationMode.CUSTOM_DIRECTORY, treeLabel))) },
                            onClick = {
                                directoryError = false
                                mode = DownloadDestinationMode.CUSTOM_DIRECTORY
                                choosingDirectory = false
                            },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_choose_a_folder_with_write_access)) }, onClick = {
                            choosingDirectory = false
                            directoryPicker.launch(treeUri?.takeIf { customDirectoryAvailable }?.toUri())
                        })
                    }
                }
                if (directoryError) Text(
                    stringResource(R.string.ui_unable_to_keep_write_access_to_this_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            // Capture the displayed action; expiry between rendering Download and the tap
            // must not silently authorize a new request.
            TextButton(enabled = filename.isNotBlank() && !confirming && !checkingDirectory &&
                (mode == DownloadDestinationMode.SYSTEM_DOWNLOADS || customDirectoryAvailable), onClick = {
                confirming = true
                scope.launch {
                    try {
                        val writable = destination.mode != DownloadDestinationMode.CUSTOM_DIRECTORY ||
                            withContext(Dispatchers.IO) {
                                DownloadSettingsRepository.hasWritableGrant(context, destination.treeUri)
                            }
                        if (writable) onConfirm(responseUnavailable != null, filename, destination)
                        else {
                            customDirectoryAvailable = false
                            directoryError = true
                        }
                    } finally { confirming = false }
                }
            }) {
                Text(stringResource(if (responseUnavailable != null) R.string.ui_retry else if (isNewCopy) R.string.download_copy_action else R.string.download_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
