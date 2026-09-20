package com.mybrowser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.backup.ImportPreview
import com.mybrowser.backup.ImportPreviewText
import com.mybrowser.backup.SettingsBackupCodec
import com.mybrowser.backup.SettingsBackup

/**
 * Import confirmation. Shows what the file would change before anything is written;
 * "应用设置" is the product's own confirmation, so no further prompt follows.
 */
@Composable
fun ImportPreviewDialog(
    backup: SettingsBackup,
    summary: ImportPreview,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_import)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (backup.appVersion.isNotEmpty()) {
                    Note(stringResource(R.string.settings_import_source_version,
                        ImportPreviewText.limit(backup.appVersion, SettingsBackupCodec.MAX_APP_VERSION_CHARS)))
                }
                if (backup.exportedAt.isNotEmpty()) {
                    Note(stringResource(R.string.settings_import_exported_at,
                        ImportPreviewText.limit(backup.exportedAt, SettingsBackupCodec.MAX_EXPORTED_AT_CHARS)))
                }
                Note(stringResource(R.string.settings_import_groups, groupLabels(summary)))
                summary.incognitoSwitch?.let { target ->
                    Note(
                        stringResource(
                            if (target) R.string.settings_import_incognito_on_note
                            else R.string.settings_import_incognito_off_note,
                        ),
                    )
                }
                summary.engineReplaces?.let { (new, old) ->
                    Note(stringResource(R.string.settings_import_engines_note, new, old))
                }
                summary.subscriptionReplaces?.let { (new, old) ->
                    Note(stringResource(R.string.settings_import_subscriptions_note, new, old))
                }
                summary.siteReplaces?.let { (new, old) ->
                    Note(stringResource(R.string.settings_import_sites_note, new, old))
                }
                if (summary.unknownBuiltInIds.isNotEmpty()) {
                    Note(stringResource(R.string.settings_import_unknown_builtins_note,
                        ImportPreviewText.unknownIds(summary.unknownBuiltInIds)))
                }
                if (summary.directoryHintCustom) {
                    Note(stringResource(R.string.settings_import_directory_note))
                }
                if (summary.siteStoreUnreadable) {
                    Note(stringResource(R.string.settings_import_site_store_unreadable_note))
                }
                Note(stringResource(R.string.settings_import_privacy_note))
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) { Text(stringResource(R.string.settings_import_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = ImportPreviewText.limit(text),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun groupLabels(summary: ImportPreview): String {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    return summary.groups.joinToString(", ") { id ->
        when (id) {
            "browser" -> resources.getString(R.string.settings_group_browser)
            "home" -> resources.getString(R.string.settings_group_home)
            "search" -> resources.getString(R.string.settings_group_search)
            "downloads" -> resources.getString(R.string.settings_group_downloads)
            "filtering" -> resources.getString(R.string.settings_group_filtering)
            "sites" -> resources.getString(R.string.settings_group_sites)
            else -> id
        }
    }
}
