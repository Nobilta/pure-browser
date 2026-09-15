package com.mybrowser.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mybrowser.R
import com.mybrowser.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Foreground, user-initiated updates. Leaving this dialog cancels the transfer. */
@Composable
internal fun UpdatePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { UpdateRepository(context) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<UpdateRepository.Release?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var bytes by remember { mutableLongStateOf(0L) }
    var message by remember { mutableIntStateOf(R.string.update_intro) }
    var allowed by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = context.packageManager.canRequestPackageInstalls()
        message = if (allowed) R.string.update_ready else R.string.update_permission_needed
    }
    fun failure(error: Exception) {
        if ((error as? UpdateRepository.Failure)?.problem in setOf(UpdateRepository.Problem.VERIFICATION, UpdateRepository.Problem.FILE)) {
            apk = null
        }
        message = when ((error as? UpdateRepository.Failure)?.problem) {
            UpdateRepository.Problem.NO_RELEASE -> R.string.update_no_release
            UpdateRepository.Problem.RATE_LIMIT -> R.string.update_rate_limit
            UpdateRepository.Problem.INCOMPATIBLE -> R.string.update_incompatible
            UpdateRepository.Problem.INVALID, UpdateRepository.Problem.VERIFICATION -> R.string.update_invalid
            UpdateRepository.Problem.FILE -> R.string.update_file_error
            else -> R.string.update_network_error
        }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        job = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_current, repository.currentVersion))
                Text(stringResource(message))
                if (busy) {
                    val total = release?.size
                    if (message == R.string.update_downloading && total != null)
                        LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                release?.let { item ->
                    Text(stringResource(R.string.update_available, item.versionName,
                        java.text.DecimalFormat("0.00").format(item.size / (1024.0 * 1024.0))), style = MaterialTheme.typography.titleMedium)
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRepository.RELEASES))) }
                    catch (_: Exception) { message = R.string.update_network_error }
                }) { Text(stringResource(R.string.update_release_page)) }
            }
        },
        confirmButton = {
            if (!busy) {
                val item = release
                val file = apk
                TextButton(onClick = {
                    when {
                        item == null -> run {
                            message = R.string.update_checking
                            release = repository.check()
                            message = if (release == null) R.string.update_latest else R.string.update_confirm_download
                        }
                        file == null -> run {
                            bytes = 0
                            message = R.string.update_downloading
                            apk = repository.download(item) { downloaded -> withContext(Dispatchers.Main) { bytes = downloaded } }
                            message = R.string.update_ready
                        }
                        !context.packageManager.canRequestPackageInstalls() -> {
                            allowed = false
                            message = R.string.update_permission_needed
                            try { authorization.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"))) }
                            catch (_: Exception) { message = R.string.update_permission_needed }
                        }
                        else -> run {
                            message = R.string.update_verifying
                            repository.verify(file, item)
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                message = R.string.update_install_handed_off
                            } catch (_: Exception) { message = R.string.update_install_error }
                        }
                    }
                }) { Text(stringResource(when {
                    item == null -> R.string.update_check
                    file == null -> R.string.update_download
                    !allowed -> R.string.update_allow_source
                    else -> R.string.update_install
                })) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) {
            Text(stringResource(if (busy) R.string.action_cancel else R.string.ui_close))
        } },
    )
}
