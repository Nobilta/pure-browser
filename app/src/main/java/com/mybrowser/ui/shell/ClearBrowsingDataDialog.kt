package com.mybrowser.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import com.mybrowser.privacy.*

@Composable
fun ClearBrowsingDataDialog(privateMode: Boolean, isolated: Boolean, modernDeletion: Boolean, busy: Boolean,
    onClear: (ClearDataRequest) -> Unit, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(ClearScope.CURRENT) }
    var types by remember { mutableStateOf(if (privateMode) setOf(ClearDataType.WEBSITE_DATA)
        else setOf(ClearDataType.WEBSITE_DATA, ClearDataType.HISTORY)) }
    var period by remember { mutableStateOf(HistoryPeriod.ALL) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.clear_data_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.clear_scope), style = MaterialTheme.typography.titleSmall)
            ClearScope.entries.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(scope == value, !busy, Role.RadioButton) { scope = value },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(scope == value, null, enabled = !busy)
                    Text(stringResource(when(value) { ClearScope.CURRENT -> R.string.clear_current; ClearScope.NORMAL -> R.string.clear_normal; ClearScope.ALL -> R.string.clear_all_profiles }))
                }
            }
            if (privateMode && !isolated) Text(stringResource(R.string.clear_shared_warning), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ClearDataType.entries.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value in types, !busy, Role.Checkbox) {
                    types = if (it) types + value else types - value
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(value in types, null, enabled = !busy)
                    Text(stringResource(when(value) {
                        ClearDataType.WEBSITE_DATA -> R.string.clear_website_data
                        ClearDataType.COOKIES -> R.string.clear_cookies_only
                        ClearDataType.HISTORY -> R.string.clear_normal_history
                        ClearDataType.PERMISSIONS -> R.string.clear_site_permissions
                    }))
                }
            }
            if (ClearDataType.HISTORY in types) {
                HistoryPeriod.entries.forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(period == value, !busy, Role.RadioButton) { period = value },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(period == value, null, enabled = !busy)
                        Text(stringResource(when(value) { HistoryPeriod.HOUR -> R.string.clear_last_hour; HistoryPeriod.DAY -> R.string.clear_last_day; HistoryPeriod.ALL -> R.string.clear_all_time }))
                    }
                }
            }
            if (!modernDeletion) Text(stringResource(R.string.clear_legacy_warning), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.clear_keeps_saved), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        } },
        confirmButton = { TextButton(onClick = { onClear(ClearDataRequest(scope, types, period)) }, enabled = !busy && types.isNotEmpty()) { Text(stringResource(R.string.action_clear)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}
