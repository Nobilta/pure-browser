package com.mybrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.PageFailure
import com.mybrowser.core.PageFailureKind

@Composable
fun PageRecovery(failure: PageFailure, onRetry: () -> Unit, onHome: () -> Unit, onSite: () -> Unit) {
    var details by remember(failure) { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text(stringResource(if (failure.kind == PageFailureKind.RENDERER) R.string.recovery_crash else R.string.recovery_network),
                style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.recovery_help), Modifier.padding(vertical = 16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.recovery_retry)) }
            OutlinedButton(onClick = onHome) { Text(stringResource(R.string.recovery_home)) }
            TextButton(onClick = onSite) { Text(stringResource(R.string.site_settings)) }
            TextButton(onClick = { details = !details }) { Text(stringResource(R.string.recovery_details)) }
            if (details) Text(failure.url + "\n" + failure.details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExternalAppDialog(origin: String, scheme: String, privateSession: Boolean, onAnswer: (Boolean, Boolean) -> Unit) {
    var rememberChoice by remember(origin, scheme) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { onAnswer(false, false) },
        title = { Text(stringResource(R.string.external_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.external_message, origin, scheme))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(rememberChoice, role = Role.Checkbox) { rememberChoice = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberChoice, null)
                Text(stringResource(if (privateSession) R.string.site_remember_private else R.string.site_remember))
            }
        } },
        confirmButton = { TextButton(onClick = { onAnswer(true, rememberChoice) }) { Text(stringResource(R.string.site_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false, rememberChoice) }) { Text(stringResource(R.string.site_block)) } })
}

data class BackHistoryEntry(val offset: Int, val title: String, val url: String)
@Composable
fun BackHistoryDialog(entries: List<BackHistoryEntry>, onSelect: (Int) -> Unit, onExitSite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.back_history)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            entries.forEach { entry ->
                TextButton(onClick = { onSelect(entry.offset) }, modifier = Modifier.fillMaxWidth()) {
                    Text(entry.title.ifBlank { entry.url }, maxLines = 2)
                }
            }
            if (entries.isEmpty()) Text(stringResource(R.string.back_history_empty))
        } },
        confirmButton = { TextButton(onClick = onExitSite) { Text(stringResource(R.string.exit_current_site)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
