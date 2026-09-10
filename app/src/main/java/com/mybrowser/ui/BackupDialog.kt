package com.mybrowser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.backup.BackupDocuments
import com.mybrowser.backup.BackupSection
import com.mybrowser.backup.RestoreChoice

@Composable
fun BackupDialog(documents: BackupDocuments) {
    val preview = documents.preview
    val available = remember(preview) { BackupSection.entries.filter {
        preview == null || preview.getJSONObject("sections").has(it.name)
    }.toSet() }
    var selected by remember(preview) { mutableStateOf(available) }
    var replace by remember(preview) { mutableStateOf(false) }
    var permissions by remember(preview) { mutableStateOf(false) }
    val busy = documents.busy
    AlertDialog(onDismissRequest = documents::dismiss,
        title = { Text(stringResource(if (preview == null) R.string.backup_title else R.string.backup_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.backup_scope))
                if (preview != null) Text(stringResource(R.string.backup_restart_notice), Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(12.dp))
                available.forEach { section ->
                    Row(Modifier.fillMaxWidth().toggleable(section in selected, enabled = !busy, role = Role.Checkbox,
                        onValueChange = { selected = if (it) selected + section else selected - section }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(section in selected, onCheckedChange = null, enabled = !busy)
                        Text(stringResource(section.label), Modifier.weight(1f))
                    }
                }
                if (preview != null) {
                    Text(stringResource(R.string.backup_conflicts), Modifier.padding(top = 12.dp))
                    for (value in listOf(false, true)) Row(Modifier.fillMaxWidth()
                        .selectable(replace == value, enabled = !busy, role = Role.RadioButton, onClick = { replace = value }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(replace == value, onClick = null, enabled = !busy)
                        Text(stringResource(if (value) R.string.backup_replace else R.string.backup_merge), Modifier.weight(1f))
                    }
                    if (BackupSection.SITES in selected) Row(Modifier.fillMaxWidth()
                        .toggleable(permissions, enabled = !busy, role = Role.Checkbox, onValueChange = { permissions = it }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(permissions, onCheckedChange = null, enabled = !busy)
                        Text(stringResource(R.string.backup_permissions), Modifier.weight(1f))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                if (preview == null) documents.exportFile(selected) else documents.restore(RestoreChoice(selected, replace, permissions))
            }) { Text(stringResource(if (preview == null) R.string.backup_export else R.string.backup_restore)) }
        },
        dismissButton = {
            if (preview == null) TextButton(enabled = !busy, onClick = documents::importFile) { Text(stringResource(R.string.backup_import)) }
            else TextButton(enabled = !busy, onClick = documents::clearPreview) { Text(stringResource(R.string.action_cancel)) }
        })
}
