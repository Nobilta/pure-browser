package com.mybrowser.ui.devtools

import android.content.res.Configuration
import android.content.res.Resources
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mybrowser.R
import com.mybrowser.core.ConsoleLogEntry
import com.mybrowser.core.ConsoleLogLevel
import com.mybrowser.core.NetworkRequestLog
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources

/** Logs and source are bounded; only the selected tab does work or publishes snapshots. */
@Composable
fun DeveloperTools(
    webView: WebView?,
    onDismiss: () -> Unit,
    networkEntries: List<NetworkRequestLog> = emptyList(),
    consoleEntries: List<ConsoleLogEntry> = emptyList(),
    onClearNetwork: () -> Unit = {},
    onClearConsole: () -> Unit = {},
    pageUrl: String? = null,
    pageGeneration: Long = 0L,
    isCurrentDocument: (Long) -> Boolean = { true },
    onLogVisibility: (network: Boolean, console: Boolean) -> Unit = { _, _ -> },
    onExplainFilter: (NetworkRequestLog) -> Unit = {},
) {
    val resources = localizedResources()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var command by rememberSaveable { mutableStateOf("") }
    val identity = remember(webView, pageUrl, pageGeneration) { Any() }
    val currentIdentity by rememberUpdatedState(identity)
    val currentDocument by rememberUpdatedState(isCurrentDocument)
    var evaluations by remember(identity) { mutableStateOf<List<ConsoleLine>>(emptyList()) }
    var document by remember(identity) { mutableStateOf<SourceDocument?>(null) }
    var sourceError by remember(identity) { mutableStateOf<String?>(null) }
    var sourceLoading by remember(identity) { mutableStateOf(false) }
    var sourceRevision by remember(identity) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val tabStates = rememberSaveableStateHolder()
    val compactInput = selectedTab == 0 && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val visibility by rememberUpdatedState(onLogVisibility)
    DisposableEffect(selectedTab) {
        visibility(selectedTab == 1, selectedTab == 0)
        onDispose { visibility(false, false) }
    }
    LaunchedEffect(identity, selectedTab, sourceRevision) {
        if (selectedTab != 2 || document != null) return@LaunchedEffect
        sourceLoading = true
        sourceError = null
        try {
            // The parameter is nullable on purpose: the panel stays up while the renderer is being
            // replaced. Report that the same way a failed read is reported rather than throwing.
            val view = webView
            if (view == null) {
                sourceError = resources.getString(R.string.ui_unable_to_read_page_source)
                return@LaunchedEffect
            }
            // Let the selected tab and loading state paint before asking the renderer.
            withFrameNanos { }
            val raw = view.awaitJavascript("(document.documentElement ? document.documentElement.outerHTML : '').slice(0, ${SourceHighlighter.MAX_SOURCE_CHARS + 1})")
            val parsed = withContext(Dispatchers.Default) {
                SourceHighlighter.parse(decodeJavascriptString(raw)) { ensureActive() }
            }
            if (currentDocument(pageGeneration)) document = parsed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            sourceError = resources.getString(R.string.ui_unable_to_read_page_source_e0f027, error.message.orEmpty())
        } finally { sourceLoading = false }
    }
    val execute: () -> Unit = {
        val expression = command.trim()
        if (expression.isNotEmpty() && webView != null) {
            command = ""
            val owner = identity
            scope.launch {
                val result = try {
                    val raw = webView.awaitJavascript(expression)
                    withContext(Dispatchers.Default) {
                        "> $expression\n${decodeJavascriptString(raw).take(MAX_RESULT_CHARS)}"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { resources.getString(R.string.ui_execution_failed, expression, error.message.orEmpty()) }
                if (currentIdentity === owner && currentDocument(pageGeneration)) {
                    evaluations = (evaluations + ConsoleLine((evaluations.lastOrNull()?.id ?: 0L) + 1, result)).takeLast(100)
                }
            }
        }
    }
    BrowserBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            if (!compactInput) {
                BrowserSheetHeader(resources.getString(R.string.menu_developer_tools), onDismiss = onDismiss)
                PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf(R.string.ui_console, R.string.ui_network, R.string.ui_source, R.string.ui_info).forEachIndexed { index, label ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index }, modifier = Modifier.heightIn(min = 48.dp),
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(resources.getString(label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            tabStates.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> ConsoleTab(consoleEntries, evaluations, command, { command = it.take(MAX_COMMAND_CHARS) }, execute,
                        webView != null, { evaluations = emptyList(); onClearConsole() }, !compactInput)
                    1 -> NetworkLogTab(networkEntries, onClearNetwork, onExplainFilter)
                    2 -> SourceCodeTab(document, sourceLoading, sourceError) { document = null; sourceRevision++ }
                    3 -> InfoTab(webView, pageUrl, pageGeneration, currentDocument)
                }
            }
        }
    }
}

@Composable
private fun ConsoleTab(entries: List<ConsoleLogEntry>, evaluations: List<ConsoleLine>, command: String,
    onCommandChange: (String) -> Unit, onExecute: () -> Unit, canExecute: Boolean, onClear: () -> Unit, showHeader: Boolean) {
    val resources = localizedResources()
    val scroll = rememberLazyListState()
    LaunchedEffect(evaluations.lastOrNull()?.id) {
        if (evaluations.isNotEmpty()) scroll.scrollToItem(entries.size + evaluations.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (showHeader) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(resources.getString(R.string.ui_console_5633ea, entries.size + evaluations.size), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear) { Text(resources.getString(R.string.ui_clear)) }
        }
        Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
            LazyColumn(state = scroll, contentPadding = PaddingValues(12.dp)) {
                if (entries.isEmpty() && evaluations.isEmpty()) item {
                    Text(resources.getString(R.string.ui_no_console_output), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries, key = { "log-${it.id}" }, contentType = { "log" }) { entry ->
                    Text(remember(entry, resources) { formatConsoleEntry(entry, resources) },
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = consoleColor(entry.level), modifier = Modifier.padding(vertical = 2.dp))
                }
                items(evaluations, key = { "eval-${it.id}" }, contentType = { "evaluation" }) { line ->
                    Text(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(command, onCommandChange, Modifier.weight(1f),
                placeholder = { Text(resources.getString(R.string.ui_enter_a_javascript_command)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onExecute() }))
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onExecute, enabled = canExecute && command.isNotBlank()) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text(resources.getString(R.string.ui_run))
            }
        }
    }
}

@Composable
internal fun NetworkRequestItem(request: NetworkRequestLog, onExplain: (NetworkRequestLog) -> Unit) {
    val textResources = localizedResources()
    val statusColor = when {
        request.blocked -> BrowserColors.warning
        request.statusCode != null && request.statusCode in 200..399 -> BrowserColors.secure
        request.statusCode != null || request.errorCode != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = request.method + " · " + textResources.getString(com.mybrowser.core.NetworkCategory.of(request).label),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = request.statusText(textResources),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            Text(
                text = request.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        append(request.sizeText(textResources))
                        if (request.isForMainFrame) append(textResources.getString(R.string.ui_main_document))
                        if (request.blocked) append(textResources.getString(R.string.ui_filter))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = request.durationText(textResources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!request.isForMainFrame) TextButton(onClick = { onExplain(request) }) {
                Text(textResources.getString(R.string.filter_explain))
            }
            request.errorDescription?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoTab(webView: WebView?, pageUrl: String?, generation: Long, isCurrent: (Long) -> Boolean) {
    val resources = localizedResources()
    var pageInfo by remember(webView, pageUrl, generation) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(webView, pageUrl, generation) {
        val view = webView ?: return@LaunchedEffect
        // WebView properties are read on Main; JSON decoding and URI work use Default.
        val fallbackTitle = view.title.orEmpty()
        val fallbackAgent = view.settings.userAgentString.orEmpty()
        try {
            val raw = view.awaitJavascript("""
                JSON.stringify({title: document.title || '', url: location.href || '',
                    userAgent: navigator.userAgent || '', cookiesEnabled: navigator.cookieEnabled,
                    screenWidth: screen.width, screenHeight: screen.height, language: navigator.language || ''})
            """.trimIndent())
            val info = withContext(Dispatchers.Default) {
                val json = runCatching { JSONObject(decodeJavascriptString(raw)) }.getOrNull()
                val url = json?.optString("url").orEmpty().ifBlank { pageUrl.orEmpty() }
                val uri = runCatching { url.toUri() }.getOrNull()
                listOf(
                    resources.getString(R.string.ui_page_title) to json?.optString("title").orEmpty().ifBlank { fallbackTitle },
                    "URL" to url,
                    resources.getString(R.string.ui_protocol) to uri?.scheme.orEmpty().uppercase(),
                    resources.getString(R.string.ui_host) to uri?.host.orEmpty(),
                    "User Agent" to json?.optString("userAgent").orEmpty().ifBlank { fallbackAgent },
                    resources.getString(R.string.ui_language) to json?.optString("language").orEmpty(),
                    "Cookie" to resources.getString(if (json?.optBoolean("cookiesEnabled", true) != false) R.string.ui_enabled else R.string.ui_disabled),
                    resources.getString(R.string.ui_screen) to "${json?.optInt("screenWidth", 0)} × ${json?.optInt("screenHeight", 0)}",
                )
            }
            if (isCurrent(generation)) pageInfo = info
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (isCurrent(generation)) pageInfo = listOf(resources.getString(R.string.ui_page_title) to fallbackTitle, "URL" to pageUrl.orEmpty())
        }
    }
    if (pageInfo == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(resources.getString(R.string.ui_reading_page_information))
    } else LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(pageInfo.orEmpty(), key = { it.first }) { (label, value) ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value.ifBlank { "—" }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}

private data class ConsoleLine(val id: Long, val text: String)

private fun formatConsoleEntry(entry: ConsoleLogEntry, textResources: Resources): String {
    val prefix = when (entry.level) {
        ConsoleLogLevel.ERROR -> "[ERROR]"
        ConsoleLogLevel.WARNING -> "[WARN]"
        ConsoleLogLevel.DEBUG -> "[DEBUG]"
        ConsoleLogLevel.TIP -> "[TIP]"
        ConsoleLogLevel.INFO -> "[INFO]"
        ConsoleLogLevel.LOG -> "[LOG]"
    }
    val location = entry.sourceId.takeIf { it.isNotBlank() }?.let {
        " ($it:${entry.lineNumber.coerceAtLeast(0)})"
    }.orEmpty()
    return "$prefix$location ${entry.message.ifBlank { textResources.getString(R.string.ui_empty_message) }}"
}

@Composable
private fun consoleColor(level: ConsoleLogLevel): Color = when (level) {
    ConsoleLogLevel.ERROR -> MaterialTheme.colorScheme.error
    ConsoleLogLevel.WARNING -> BrowserColors.warning
    ConsoleLogLevel.DEBUG -> MaterialTheme.colorScheme.tertiary
    ConsoleLogLevel.TIP, ConsoleLogLevel.INFO -> BrowserColors.secure
    ConsoleLogLevel.LOG -> MaterialTheme.colorScheme.onSurface
}

/** Cancellation discards callbacks from a closed tab or an obsolete document. */
private suspend fun WebView.awaitJavascript(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { raw -> if (continuation.isActive) continuation.resume(raw) }
}

private fun decodeJavascriptString(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "null") return ""
    return runCatching { JSONTokener(value).nextValue() as? String ?: value }.getOrElse { value.removeSurrounding("\"") }
}

private const val MAX_COMMAND_CHARS = 8_192
private const val MAX_RESULT_CHARS = 8_192
