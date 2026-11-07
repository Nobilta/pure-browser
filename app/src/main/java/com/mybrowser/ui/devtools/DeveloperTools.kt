package com.mybrowser.ui.devtools

import android.content.res.Resources
import android.webkit.WebView
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.ConsoleLogEntry
import com.mybrowser.core.ConsoleLogLevel
import com.mybrowser.core.NetworkRequestLog
import com.mybrowser.devtools.CaptureNotice
import com.mybrowser.devtools.DevToolsController
import com.mybrowser.devtools.EnhancedCaptureState
import com.mybrowser.ui.shell.BrowserFullscreenSheet
import com.mybrowser.ui.shell.browserSheetInsets
import com.mybrowser.ui.shell.BrowserMotion
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

private val EMPTY_CAPTURE: StateFlow<EnhancedCaptureState> = MutableStateFlow(EnhancedCaptureState())
private val EMPTY_NETWORK: StateFlow<List<NetworkRequestLog>> = MutableStateFlow(emptyList())

/** Console, network, elements and storage for the current tab. */
@Composable
internal fun DeveloperTools(
    webView: WebView?,
    devTools: DevToolsController,
    consoleEntries: List<ConsoleLogEntry>,
    onClearConsole: () -> Unit,
    onReloadPage: () -> Unit,
    pageUrl: String?,
    onConsoleVisibility: (Boolean) -> Unit,
    onExplainFilter: (NetworkRequestLog) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = localizedResources()
    val session = remember(webView, devTools) { devTools.sessionFor(webView) }
    val capture by (session?.state ?: EMPTY_CAPTURE).collectAsState()
    val networkEntries by (session?.network?.entries ?: EMPTY_NETWORK).collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var command by rememberSaveable { mutableStateOf("") }
    val identity = remember(webView, pageUrl, capture.documentId) { Any() }
    val currentIdentity by rememberUpdatedState(identity)
    var evaluations by remember(identity) { mutableStateOf<List<ConsoleLine>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val tabStates = rememberSaveableStateHolder()
    val domState = remember(session) { session?.let { DomTreeState(it) } }
    val sourceState = remember(session) { session?.let { SourceViewerState(it) } }
    DisposableEffect(selectedTab, session) {
        session?.setNetworkVisible(selectedTab == 1)
        onConsoleVisibility(selectedTab == 0)
        onDispose {
            session?.setNetworkVisible(false)
            onConsoleVisibility(false)
        }
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
                if (currentIdentity === owner) {
                    evaluations = (evaluations + ConsoleLine((evaluations.lastOrNull()?.id ?: 0L) + 1, result)).takeLast(100)
                }
            }
        }
    }
    val enableCapture: () -> Unit = {
        session?.let {
            if (it.enable()) onReloadPage()
        }
    }
    BrowserFullscreenSheet(onDismissRequest = onDismiss) {
        // Compose handles IME insets. Platform auto-pan would additionally move the whole
        // window when the landscape console hides its toolbar as the keyboard opens.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = generateSequence(dialogView as? ViewParent ?: dialogView.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            val previousMode = window?.attributes?.softInputMode
            val resize = Runnable { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            resize.run()
            dialogView.post(resize)
            onDispose {
                dialogView.removeCallbacks(resize)
                if (previousMode != null) window.setSoftInputMode(previousMode)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets()).imePadding()) {
        // Measure inside the dialog after its insets have been consumed. Activity IME
        // visibility can lag behind this window during rotation or keyboard animation.
        val compactInput = selectedTab == 0 && maxHeight < 260.dp
        Column(Modifier.fillMaxSize()) {
            if (!compactInput) {
                BrowserSheetHeader(resources.getString(R.string.menu_developer_tools), onBack = onDismiss)
                EnhancedCaptureBar(
                    capture = capture,
                    onEnable = enableCapture,
                    onStop = { session?.disable() },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf(R.string.ui_console, R.string.ui_network, R.string.dev_tab_elements, R.string.dev_tab_storage)
                        .forEachIndexed { index, label ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                                modifier = Modifier.heightIn(min = 48.dp),
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text(resources.getString(label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                }
            }
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(BrowserMotion.contentCross()) togetherWith fadeOut(BrowserMotion.chromeFade())
                },
                label = "developerTabContent",
            ) { tab ->
                tabStates.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> ConsoleTab(consoleEntries, evaluations, command, { command = it.take(MAX_COMMAND_CHARS) }, execute,
                            webView != null, { evaluations = emptyList(); onClearConsole() }, !compactInput)
                        1 -> NetworkLogTab(
                            entries = networkEntries,
                            onClear = { session?.clearNetwork() },
                            onExplain = onExplainFilter,
                            session = session,
                        )
                        2 -> ElementsTab(
                            session = session,
                            capture = capture,
                            domState = domState,
                            sourceState = sourceState,
                        )
                        3 -> StorageTab(
                            session = session,
                            pageUrl = pageUrl,
                        )
                    }
                }
            }
        }
        }
    }

}

@Composable
private fun EnhancedCaptureBar(
    capture: EnhancedCaptureState,
    onEnable: () -> Unit,
    onStop: () -> Unit,
) {
    val resources = localizedResources()
    Surface(color = BrowserColors.recessedWell, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val enabled = capture.enabled
                    Text(
                        when {
                            capture.installing -> resources.getString(R.string.dev_enhanced_installing)
                            enabled && capture.ready -> resources.getString(R.string.dev_enhanced_ready)
                            enabled -> resources.getString(R.string.dev_enhanced_document_pending)
                            capture.stopped -> resources.getString(R.string.dev_enhanced_stopped)
                            else -> resources.getString(R.string.dev_enhanced_off)
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) BrowserColors.secure else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (enabled) {
                        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(resources.getString(R.string.dev_enhanced_stop))
                        }
                    } else {
                        Button(
                            onClick = onEnable,
                            enabled = capture.supported && !capture.installing,
                            shape = CircleShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(resources.getString(R.string.dev_enhanced_enable), maxLines = 1) }
                    }
                }
                val notice = when {
                    !capture.supported -> resources.getString(R.string.dev_enhanced_limited)
                    capture.notice == CaptureNotice.HTTP_ONLY -> resources.getString(R.string.dev_enhanced_http_only)
                    capture.notice == CaptureNotice.SCRIPT_MISSING -> resources.getString(R.string.dev_enhanced_script_missing)
                    capture.notice == CaptureNotice.INSTALL_FAILED -> resources.getString(
                        R.string.dev_enhanced_install_failed, capture.errorDetail.orEmpty())
                    else -> null
                }
                if (notice != null) Text(
                    notice,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!capture.supported || capture.notice == CaptureNotice.INSTALL_FAILED)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
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
            color = BrowserColors.recessedWell, shape = MaterialTheme.shapes.medium) {
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
            FilledTonalButton(
                onClick = onExecute,
                enabled = canExecute && command.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_ui_play), null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(resources.getString(R.string.ui_run))
            }
        }
    }
}

@Composable
internal fun NetworkRequestItem(request: NetworkRequestLog, onExplain: (NetworkRequestLog) -> Unit, onOpen: () -> Unit) {
    val textResources = localizedResources()
    val statusColor = when {
        request.blocked -> BrowserColors.warning
        request.statusCode != null && request.statusCode in 200..399 -> BrowserColors.secure
        request.statusCode != null || request.errorCode != null || request.failed -> MaterialTheme.colorScheme.error
        request.cancelled -> BrowserColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
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
                    text = (if (request.methodKnown && request.method.isNotBlank()) request.method
                        else textResources.getString(R.string.dev_storage_unknown)) +
                        " · " + textResources.getString(com.mybrowser.core.NetworkCategory.of(request).label) +
                        " · " + textResources.getString(
                            if (request.pageScript) R.string.dev_enhanced_source_page_script
                            else R.string.dev_enhanced_source_webview
                        ),
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
                Text(
                    text = request.durationText(textResources) +
                        (request.pageLoadMs?.let { " · ${textResources.getString(R.string.dev_network_page_load)} $it ms" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
