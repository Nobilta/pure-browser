package com.mybrowser.ui.shell

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.SystemLoginSupport

@Composable
fun SystemLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.system_login)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.system_login_summary))
            Text(stringResource(if (SystemLoginSupport.autofillEnabled(context)) R.string.autofill_enabled else R.string.autofill_disabled), Modifier.padding(top = 12.dp))
            Text(stringResource(when {
                !SystemLoginSupport.supportsWebAuthn -> R.string.webauthn_unsupported
                !SystemLoginSupport.hasOriginPermission(context) -> R.string.webauthn_origin_unavailable
                else -> R.string.webauthn_supported
            }), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.webauthn_provider_approval), Modifier.padding(top = 12.dp))
            Text("WebView " + WebView.getCurrentWebViewPackage()?.versionName.orEmpty(), Modifier.padding(top = 12.dp))
        } }, confirmButton = { TextButton(onClick = {
            runCatching { SystemLoginSupport.openSettings(context) }; onDismiss()
        }) { Text(stringResource(R.string.system_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } })
}
