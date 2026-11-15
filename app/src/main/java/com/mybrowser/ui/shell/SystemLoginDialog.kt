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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mybrowser.R
import com.mybrowser.core.SystemLoginSupport
import com.mybrowser.ui.components.BrowserActionPill

@Composable
fun SystemLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var autofillEnabled by remember { mutableStateOf(SystemLoginSupport.autofillEnabled(context)) }
    var provider by remember { mutableStateOf(SystemLoginSupport.autofillProvider(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autofillEnabled = SystemLoginSupport.autofillEnabled(context)
                provider = SystemLoginSupport.autofillProvider(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BrowserAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.system_login)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.system_login_summary))
            Text(stringResource(if (autofillEnabled) R.string.autofill_enabled else R.string.autofill_disabled), Modifier.padding(top = 12.dp))
            provider?.let { Text(stringResource(R.string.autofill_provider, it), Modifier.padding(top = 12.dp)) }
            Text(stringResource(when {
                !SystemLoginSupport.supportsWebAuthn -> R.string.webauthn_unsupported
                !SystemLoginSupport.hasOriginPermission(context) -> R.string.webauthn_origin_unavailable
                else -> R.string.webauthn_supported
            }), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.webauthn_provider_approval), Modifier.padding(top = 12.dp))
            Text("WebView " + WebView.getCurrentWebViewPackage()?.versionName.orEmpty(), Modifier.padding(top = 12.dp))
        } }, confirmButton = {
            BrowserActionPill(stringResource(R.string.login_provider_settings), onClick = { SystemLoginSupport.openSettings(context) })
        }, dismissButton = { BrowserActionPill(stringResource(R.string.ui_close), onClick = onDismiss) })
}
