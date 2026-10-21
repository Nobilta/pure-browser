package com.mybrowser.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R

/** One-time welcome, kept short: the product's three promises and a default-browser shortcut. */
@Composable
fun WelcomeDialog(
    isDefaultBrowser: Boolean,
    onSetDefaultBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.welcome_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.welcome_body))
                Text(stringResource(R.string.welcome_filter))
                Text(stringResource(R.string.welcome_translate))
                Text(stringResource(R.string.welcome_privacy))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.welcome_start)) }
        },
        dismissButton = if (isDefaultBrowser) null else {
            { TextButton(onClick = onSetDefaultBrowser) { Text(stringResource(R.string.welcome_set_default)) } }
        },
    )
}
