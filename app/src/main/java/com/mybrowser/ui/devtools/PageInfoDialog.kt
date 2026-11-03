package com.mybrowser.ui.devtools

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mybrowser.R
import com.mybrowser.ui.shell.localizedResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import org.json.JSONTokener

/** Title, URL and user agent moved from the old Info tab into a toolbar entry. */
@Composable
internal fun PageInfoDialog(
    webView: WebView?,
    pageUrl: String?,
    documentId: Long,
    onDismiss: () -> Unit,
) {
    val resources = localizedResources()
    var rows by remember(webView, pageUrl, documentId) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(webView, pageUrl, documentId) {
        val view = webView ?: return@LaunchedEffect
        val fallbackTitle = view.title.orEmpty()
        val fallbackAgent = view.settings.userAgentString.orEmpty()
        try {
            val raw = view.awaitJavascript(
                """
                JSON.stringify({title: document.title || '', url: location.href || '',
                    userAgent: navigator.userAgent || '', cookiesEnabled: navigator.cookieEnabled,
                    screenWidth: screen.width, screenHeight: screen.height, language: navigator.language || ''})
                """.trimIndent(),
            )
            val info = withContext(Dispatchers.Default) { decode(raw) }
            val url = info?.optString("url").orEmpty().ifBlank { pageUrl.orEmpty() }
            val uri = runCatching { url.toUri() }.getOrNull()
            rows = listOf(
                resources.getString(R.string.dev_page_title) to info?.optString("title").orEmpty().ifBlank { fallbackTitle },
                resources.getString(R.string.dev_page_url) to url,
                resources.getString(R.string.dev_page_protocol) to uri?.scheme.orEmpty().uppercase(),
                resources.getString(R.string.dev_page_host) to uri?.host.orEmpty(),
                resources.getString(R.string.dev_page_user_agent) to info?.optString("userAgent").orEmpty().ifBlank { fallbackAgent },
                resources.getString(R.string.dev_page_language) to info?.optString("language").orEmpty(),
                resources.getString(R.string.dev_page_cookies) to resources.getString(
                    if (info?.optBoolean("cookiesEnabled", true) != false) R.string.dev_page_enabled
                    else R.string.dev_page_disabled
                ),
                resources.getString(R.string.dev_page_screen) to
                    "${info?.optInt("screenWidth", 0)} × ${info?.optInt("screenHeight", 0)}",
            )
        } catch (_: Exception) {
            rows = listOf(
                resources.getString(R.string.dev_page_title) to fallbackTitle,
                resources.getString(R.string.dev_page_url) to pageUrl.orEmpty(),
                resources.getString(R.string.dev_page_user_agent) to fallbackAgent,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dev_page_info)) },
        text = {
            val current = rows
            if (current == null) Text(stringResource(R.string.dev_page_reading))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(current, key = { it.first }) { (label, value) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value.ifBlank { "—" }, Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) } },
    )
}

private suspend fun WebView.awaitJavascript(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { raw -> if (continuation.isActive) continuation.resume(raw) }
}

private fun decode(raw: String?): JSONObject? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "null") return null
    val text = runCatching { JSONTokener(value).nextValue() as? String ?: value }.getOrNull() ?: return null
    return runCatching { JSONObject(text) }.getOrNull()
}
