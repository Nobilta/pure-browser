package com.mybrowser.ui.devtools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.devtools.PageResult
import com.mybrowser.ui.components.BrowserTextField
import com.mybrowser.ui.shell.BrowserAlertDialog
import kotlinx.coroutines.launch

/** Explicit, page-scoped edits shared by DOM attributes/styles and Web Storage values. */
@Composable
internal fun DevToolsEditDialog(
    title: String,
    initialName: String? = null,
    initialValue: String,
    nameEditable: Boolean = true,
    hint: String? = null,
    onApply: suspend (String, String) -> PageResult<Unit>,
    onRemove: (suspend () -> PageResult<Unit>)? = null,
    onApplied: () -> Unit,
    onRemoved: () -> Unit = onApplied,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var value by remember { mutableStateOf(initialValue) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.dev_edit_failed)
    fun submit(onSuccess: () -> Unit = onApplied, action: suspend () -> PageResult<Unit>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                when (val result = action()) {
                    is PageResult.Ok -> onSuccess()
                    is PageResult.Failed -> error = result.message?.takeIf { it.isNotBlank() } ?: failed
                    else -> error = failed
                }
            } finally { busy = false }
        }
    }
    BrowserAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        scrollableContent = true,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
                if (initialName != null) BrowserTextField(name, stringResource(R.string.dev_storage_name),
                    { name = it }, enabled = nameEditable && !busy)
                BrowserTextField(value, stringResource(R.string.dev_storage_value), { value = it },
                    singleLine = false, enabled = !busy, modifier = Modifier.heightIn(max = 240.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit { onApply(name, value) } }, enabled = !busy) {
                Text(stringResource(R.string.dev_edit_apply))
            }
        },
        dismissButton = {
            if (onRemove != null) TextButton(onClick = { submit(onRemoved, onRemove) }, enabled = !busy) {
                Text(stringResource(R.string.ui_remove), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
