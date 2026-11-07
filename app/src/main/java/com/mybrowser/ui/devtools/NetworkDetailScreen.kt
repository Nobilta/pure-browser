package com.mybrowser.ui.devtools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.NetworkCategory
import com.mybrowser.core.NetworkRequestLog
import com.mybrowser.core.formatBytes
import com.mybrowser.devtools.BodyPreview
import com.mybrowser.devtools.BodyReason
import com.mybrowser.devtools.BodyState
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.HeaderEntry
import com.mybrowser.devtools.NetworkRequestDetail
import com.mybrowser.devtools.NetworkSource
import com.mybrowser.devtools.QueryParameter
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.localizedResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener

@Composable
internal fun NetworkDetailScreen(
    session: DevToolsSession,
    requestId: Long,
    onBack: () -> Unit,
    onExplain: (NetworkRequestLog) -> Unit,
) {
    val resources = localizedResources()
    BackHandler { onBack() }
    val revision by session.details.revision.collectAsState()
    val detail = remember(revision, requestId) { session.details.get(requestId) }
    val entry = remember(revision, requestId) {
        session.network.snapshot().firstOrNull { it.id == requestId }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowserIconAction(R.drawable.ic_ui_back, stringResource(R.string.cd_back), onClick = onBack)
            Text(
                stringResource(R.string.dev_network_detail_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    resources.getString(it.source.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (detail == null) {
            EmptyState(stringResource(R.string.dev_network_record_gone))
            return@Column
        }
        val status = statusText(detail, entry, resources)
        val duration = detail.durationMs ?: entry?.durationMs
        val size = detail.sizeBytes ?: entry?.sizeBytes
        val failure = failureText(detail, entry, resources)
        val query = detail.queryParameters
        val requestJson = isJson(detail.requestBody)
        val responseJson = isJson(detail.responseBody)
        var requestFormatted by rememberSaveable(detail.id) { mutableStateOf(true) }
        var responseFormatted by rememberSaveable(detail.id) { mutableStateOf(true) }
        val requestExpanded = rememberExpanded(detail.requestBody.text)
        val responseExpanded = rememberExpanded(detail.responseBody.text)
        val requestRows = rememberJsonRows(detail.requestBody.text, requestExpanded)
        val responseRows = rememberJsonRows(detail.responseBody.text, responseExpanded)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionCard(
                    title = stringResource(R.string.dev_network_overview),
                ) {
                    DetailRow(stringResource(R.string.dev_network_full_url), detail.url)
                    DetailRow(
                        stringResource(R.string.dev_network_method),
                        if (detail.methodKnown) detail.method else stringResource(R.string.dev_storage_unknown),
                    )
                    DetailRow(stringResource(R.string.dev_network_type),
                        resources.getString(NetworkCategory.of(entry ?: nativeEntry(detail)).label))
                    DetailRow(stringResource(R.string.dev_network_status), status)
                    detail.statusCode?.let {
                        DetailRow(stringResource(R.string.dev_network_status_source), it.toString())
                    }
                    DetailRow(stringResource(R.string.dev_network_duration), duration?.let { "$it ms" }
                        ?: stringResource(R.string.dev_duration_unknown))
                    detail.pageLoadMs?.let {
                        DetailRow(stringResource(R.string.dev_network_page_load), "$it ms")
                    }
                    DetailRow(stringResource(R.string.dev_network_size),
                        size?.let(::formatBytes) ?: stringResource(R.string.ui_unknown_size))
                    failure?.let { DetailRow(stringResource(R.string.dev_network_failure), it) }
                    if (!detail.completed && detail.source == NetworkSource.PAGE_SCRIPT) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }

                }
            }
            item {
                SectionCard(
                    title = stringResource(R.string.dev_network_request),
                ) {
                    Text(stringResource(R.string.dev_network_query), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.dev_network_no_query),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        query.forEach { parameter -> QueryRow(parameter) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.dev_network_request_headers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HeaderList(
                        detail.requestHeaders,
                        when {
                            !detail.requestHeadersCollected && detail.requestHeadersReason != BodyReason.NONE ->
                                resources.getString(detail.requestHeadersReason.labelOrNull ?: R.string.dev_body_not_collected)
                            detail.source == NetworkSource.PAGE_SCRIPT ->
                                stringResource(R.string.dev_network_headers_page_note)
                            else -> null
                        },
                    )
                }
            }
            item {
                BodyHeader(
                    title = stringResource(R.string.dev_network_request_body),
                    body = detail.requestBody,
                    showFormat = requestJson,
                    formatted = requestFormatted,
                    onToggleFormat = { requestFormatted = !requestFormatted },
                )
            }
            bodyRows(detail.requestBody, requestJson && requestFormatted, requestRows,
                requestExpanded, "request")
            item {
                SectionCard(
                    title = stringResource(R.string.dev_network_response),
                ) {
                    Text(stringResource(R.string.dev_network_response_headers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HeaderList(
                        detail.responseHeaders,
                        when {
                            !detail.responseHeadersCollected && detail.responseHeadersReason != BodyReason.NONE ->
                                resources.getString(detail.responseHeadersReason.labelOrNull ?: R.string.dev_body_not_collected)
                            detail.source == NetworkSource.PAGE_SCRIPT ->
                                stringResource(R.string.dev_network_headers_page_note)
                            else -> null
                        },
                    )
                }
            }
            item {
                BodyHeader(
                    title = stringResource(R.string.dev_network_response_body),
                    body = detail.responseBody,
                    showFormat = responseJson,
                    formatted = responseFormatted,
                    onToggleFormat = { responseFormatted = !responseFormatted },
                )
            }
            bodyRows(detail.responseBody, responseJson && responseFormatted, responseRows,
                responseExpanded, "response")
            item {
                SectionCard(title = stringResource(R.string.dev_network_filter)) {
                    SelectableText(detail.url, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    if (detail.blocked) Text(
                        stringResource(R.string.ui_blocked),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    entry?.let { request ->
                        if (!request.isForMainFrame) TextButton(onClick = { onExplain(request) }) {
                            Text(stringResource(R.string.filter_explain))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryRow(parameter: QueryParameter) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SelectableText(buildString {
            append(parameter.name).append(": ")
            append(when {
                parameter.value == null -> stringResource(R.string.dev_query_no_equals)
                parameter.value.isEmpty() -> stringResource(R.string.dev_query_empty_value)
                else -> parameter.value
            })
        }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        if (parameter.encodedDiffers) SelectableText(
            stringResource(R.string.dev_query_encoded, parameter.raw),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BodyHeader(
    title: String,
    body: BodyPreview,
    showFormat: Boolean,
    formatted: Boolean,
    onToggleFormat: () -> Unit,
) {
    SectionCard(
        title = title,
        actions = {
            if (showFormat) JsonToggle(formatted, onToggleFormat)
        },
    ) {
        if (body.text.isNotEmpty()) CopyButton(body.text, stringResource(R.string.dev_copy_captured))
        BodyStateLine(body)
        val context = LocalResources.current
        if (body.contentType.isNotBlank() || body.bytes > 0) {
            Text(
                buildString {
                    if (body.contentType.isNotBlank()) append(body.contentType)
                    if (body.bytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(formatBytes(body.bytes))
                    }
                    if (body.text.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(context.getString(R.string.dev_body_chars, body.text.length))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun highlightable(contentType: String): Boolean {
    val type = contentType.substringBefore(';').trim().lowercase()
    return type.contains("html") || type.contains("css") || type.contains("javascript") ||
        type.contains("ecmascript") || type.endsWith("xml") || type.contains("svg")
}

private fun isJson(body: BodyPreview): Boolean {
    val type = body.contentType.substringBefore(';').trim().lowercase()
    if (type.contains("json") || type.contains("graphql")) return true
    val text = body.text.trimStart()
    return text.startsWith("{") || text.startsWith("[")
}

@Composable
private fun rememberExpanded(text: String) = remember(text) {
    val map = mutableStateMapOf<String, Boolean>()
    jsonInitialExpanded(text).forEach { map[it] = true }
    map
}

@Composable
private fun rememberJsonRows(text: String, expanded: Map<String, Boolean>): List<JsonRow> {
    val expandedSet = expanded.filterValues { it }.keys.toSet()
    val state = produceState(emptyList<JsonRow>(), text, expandedSet) {
        value = withContext(Dispatchers.Default) { jsonRows(text, expandedSet).orEmpty() }
    }
    return state.value
}

private fun LazyListScope.bodyRows(
    body: BodyPreview,
    formattedJson: Boolean,
    jsonRows: List<JsonRow>,
    expanded: MutableMap<String, Boolean>,
    keyPrefix: String,
) {
    if (body.text.isEmpty()) return
    if (formattedJson && jsonRows.isNotEmpty()) {
        items(jsonRows, key = { "$keyPrefix-json-${it.path}" }) { row ->
            JsonRowView(row) { path -> expanded[path] = !(expanded[path] ?: false) }
        }
    } else item(key = "$keyPrefix-text") {
        PagedSelectableText(body.text,
            Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            highlight = highlightable(body.contentType))
    }
}

private fun statusText(detail: NetworkRequestDetail, entry: NetworkRequestLog?, resources: android.content.res.Resources): String =
    when {
        detail.blocked -> resources.getString(R.string.ui_blocked)
        detail.statusCode != null -> buildString {
            append(detail.statusCode)
            if (detail.statusText.isNotBlank()) append(' ').append(detail.statusText)
        }
        detail.cancelled -> resources.getString(R.string.dev_network_cancelled)
        detail.failed -> resources.getString(R.string.dev_network_failed)
        detail.errorCode != null -> resources.getString(R.string.ui_error, detail.errorCode)
        else -> resources.getString(R.string.dev_network_status_unknown)
    }

private fun failureText(detail: NetworkRequestDetail, entry: NetworkRequestLog?, resources: android.content.res.Resources): String? {
    if (detail.blocked) return resources.getString(R.string.ui_filter)
    detail.failure?.takeIf { it.isNotBlank() }?.let { return it }
    if (detail.failed || detail.cancelled) {
        detail.failureReason.labelOrNull?.let { return resources.getString(it) }
    }
    entry?.errorDescription?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

private fun nativeEntry(detail: NetworkRequestDetail): NetworkRequestLog =
    NetworkRequestLog(
        id = detail.id,
        method = detail.method,
        methodKnown = detail.methodKnown,
        url = detail.url,
        resourceType = detail.resourceType,
        statusCode = detail.statusCode,
        failed = detail.failed,
        cancelled = detail.cancelled,
        blocked = detail.blocked,
        isForMainFrame = detail.isForMainFrame,
    )
