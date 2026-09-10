package com.mybrowser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mybrowser.R
import com.mybrowser.core.NetworkRequestLog
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.NativeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilterExplanationDialog(request: NetworkRequestLog, filter: FilterController, onDismiss: () -> Unit) {
    var result by remember(request.id) { mutableStateOf<FilterController.Explanation?>(null) }
    var busy by remember(request.id) { mutableStateOf(true) }
    LaunchedEffect(request.id) {
        result = withContext(Dispatchers.Default) { runCatching {
            filter.explain(request.url, request.documentUrl,
                NativeFilter.ResourceType.entries.getOrElse(request.resourceType) { NativeFilter.ResourceType.OTHER })
        }.getOrNull() }
        busy = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.filter_explain)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.filter_explain_current))
            Text(request.url)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val explanation = result
                val hit = explanation?.exception ?: explanation?.blocking
                Text(stringResource(if (explanation == null) R.string.filter_explain_failed
                    else if (explanation.exception != null) R.string.filter_exception_match
                    else if (hit != null) R.string.filter_block_match else R.string.filter_no_match))
                hit?.let { (source, rule) -> Text(source); Text(rule) }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) } })
}
