package com.mybrowser.ui.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.shell.BrowserAlertDialog

@Composable
fun TranslationResultDialog(
    original: String,
    translated: String?,
    busy: Boolean,
    error: String?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BrowserAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.translate_result_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.translate_original), style = MaterialTheme.typography.labelMedium)
                Text(original, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (error != null) {
                    Text(stringResource(R.string.translate_error, error), color = MaterialTheme.colorScheme.error)
                } else if (translated != null) {
                    Text(translated, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            if (!busy && translated != null) {
                TextButton(onClick = { onCopy(translated) }) { Text(stringResource(R.string.translate_copy)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_close)) }
        },
    )
}
