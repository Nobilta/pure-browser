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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import com.mybrowser.ui.components.BrowserActionPill
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
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserDropdownMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
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
    onInspectNode: () -> Unit,
    pageUrl: String?,
    onConsoleVisibility: (Boolean) -> Unit,
    onExplainFilter: (NetworkRequestLog) -> Unit,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
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
    val storageState = remember(session, capture.documentId) { StorageTabState() }
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
            session?.rememberCommand(expression)
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
    CompositionLocalProvider(LocalDevToolsFeedback provides onFeedback) {
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
        val header: @Composable () -> Unit = {
            if (!compactInput) {
                BrowserSheetHeader(resources.getString(R.string.menu_developer_tools), onBack = onDismiss,
                    actions = { CaptureControl(capture, enableCapture, { session?.disable() }) })
                PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(R.string.ui_console, R.string.ui_network, R.string.dev_tab_elements, R.string.dev_tab_storage)
                        .forEachIndexed { index, label ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                                modifier = Modifier.heightIn(min = 48.dp),
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text(resources.getString(label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
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
                            webView != null, { evaluations = emptyList(); onClearConsole() }, !compactInput,
                            session?.commandHistory.orEmpty(), header)
                        1 -> NetworkLogTab(
                            entries = networkEntries,
                            onClear = { session?.clearNetwork() },
                            onExplain = onExplainFilter,
                            session = session,
                            header = header,
                        )
                        2 -> ElementsTab(
                            session = session,
                            capture = capture,
                            domState = domState,
                            sourceState = sourceState,
                            onInspectNode = onInspectNode,
                            onEnableCapture = enableCapture,
                            header = header,
                        )
                        3 -> key(session, capture.documentId) {
                            StorageTab(session = session, pageUrl = pageUrl, state = storageState, header = header)
                        }
                    }
                }
            }
        }
        }
    }

    }
}

@Composable
private fun CaptureControl(capture: EnhancedCaptureState, onEnable: () -> Unit, onStop: () -> Unit) {
    val resources = localizedResources()
    var expanded by remember { mutableStateOf(false) }
    val status = resources.getString(when {
        capture.installing -> R.string.dev_enhanced_installing
        capture.enabled && capture.ready -> R.string.dev_enhanced_ready
        capture.enabled -> R.string.dev_enhanced_document_pending
        capture.stopped -> R.string.dev_enhanced_stopped
        else -> R.string.dev_enhanced_off
    })
    Box {
        BrowserIconAction(if (capture.enabled) R.drawable.ic_ui_stop else R.drawable.ic_ui_play,
            status) { expanded = true }
        BrowserDropdownMenu(expanded, { expanded = false }) {
            Text(status, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (capture.enabled) BrowserColors.secure else MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenuItem(
                text = { Text(resources.getString(if (capture.enabled) R.string.dev_enhanced_stop else R.string.dev_enhanced_enable)) },
                enabled = capture.enabled || (capture.supported && !capture.installing),
                onClick = { expanded = false; if (capture.enabled) onStop() else onEnable() },
            )
            val notice = when {
                !capture.supported -> resources.getString(R.string.dev_enhanced_limited)
                capture.notice == CaptureNotice.HTTP_ONLY -> resources.getString(R.string.dev_enhanced_http_only)
                capture.notice == CaptureNotice.SCRIPT_MISSING -> resources.getString(R.string.dev_enhanced_script_missing)
                capture.notice == CaptureNotice.INSTALL_FAILED -> resources.getString(
                    R.string.dev_enhanced_install_failed, capture.errorDetail.orEmpty())
                else -> null
            }
            notice?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ConsoleTab(entries: List<ConsoleLogEntry>, evaluations: List<ConsoleLine>, command: String,
    onCommandChange: (String) -> Unit, onExecute: () -> Unit, canExecute: Boolean, onClear: () -> Unit, showHeader: Boolean, history: List<String>, header: @Composable () -> Unit) {
    val resources = localizedResources()
    val scroll = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf<ConsoleLogLevel?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val filtered = remember(entries, query, level, resources) {
        entries.filter { (level == null || it.level == level) &&
            formatConsoleEntry(it, resources).contains(query, ignoreCase = true) }
    }
    val filteredEvaluations = remember(evaluations, query) {
        evaluations.filter { it.text.contains(query, ignoreCase = true) }
    }
    var lastEvaluation by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(evaluations.lastOrNull()?.id) {
        val newest = evaluations.lastOrNull()?.id
        if (newest != lastEvaluation) {
            lastEvaluation = newest
            val index = filteredEvaluations.indexOfLast { it.id == newest }
            if (index >= 0) scroll.scrollToItem(filtered.size + index)
        }
    }
    if (showHistory) com.mybrowser.ui.shell.BrowserAlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text(resources.getString(R.string.dev_console_history)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(history) { expression ->
                    BrowserActionPill(expression, { onCommandChange(expression); showHistory = false },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text(resources.getString(R.string.ui_close)) } },
    )
    DevToolsPage(controls = {
            header()
            if (showHeader) Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        DevToolsSearchField(query, resources.getString(R.string.dev_console_search)) { query = it }
                    }
                    BrowserIconAction(R.drawable.ic_ui_delete, resources.getString(R.string.ui_clear),
                        enabled = entries.isNotEmpty() || evaluations.isNotEmpty(), onClick = onClear)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
                    .padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrowserActionPill(resources.getString(R.string.dev_console_all), { level = null }, selected = level == null)
                    ConsoleLogLevel.entries.forEach { item ->
                        val label = when (item) {
                            ConsoleLogLevel.LOG -> R.string.dev_console_log
                            ConsoleLogLevel.INFO -> R.string.dev_console_info
                            ConsoleLogLevel.WARNING -> R.string.dev_console_warning
                            ConsoleLogLevel.ERROR -> R.string.dev_console_error
                            ConsoleLogLevel.DEBUG -> R.string.dev_console_debug
                            ConsoleLogLevel.TIP -> R.string.dev_console_tip
                        }
                        BrowserActionPill(resources.getString(label), { level = item }, selected = level == item)
                    }
                }
            }
    }) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    if (filtered.isEmpty() && filteredEvaluations.isEmpty()) item {
                        Text(resources.getString(R.string.ui_no_console_output), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(filtered, key = { "log-${it.id}" }, contentType = { "log" }) { entry ->
                        ConsoleEntry(entry)
                        HorizontalDivider(color = BrowserColors.cardBorder)

                    }
                    items(filteredEvaluations, key = { "eval-${it.id}" }, contentType = { "evaluation" }) { line ->
                        SelectableText(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrowserIconAction(R.drawable.ic_ui_history, resources.getString(R.string.dev_console_history),
                    enabled = history.isNotEmpty()) { showHistory = true }
                com.mybrowser.ui.components.BrowserTextField(
                    value = command, onValueChange = onCommandChange, label = null,
                    modifier = Modifier.weight(1f),
                    accessibilityLabel = resources.getString(R.string.ui_enter_a_javascript_command),
                    placeholder = resources.getString(R.string.ui_enter_a_javascript_command),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onExecute() }),
                )
                Spacer(Modifier.width(8.dp))
                com.mybrowser.ui.components.BrowserActionPill(
                    text = resources.getString(R.string.ui_run),
                    onClick = onExecute,
                    enabled = canExecute && command.isNotBlank(),
                    iconRes = R.drawable.ic_ui_play,
                )
            }
        }
}

@Composable
private fun ConsoleEntry(entry: ConsoleLogEntry) {
    val resources = localizedResources()
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(entry.message.ifBlank { resources.getString(R.string.ui_empty_message) },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = consoleColor(entry.level))
            val source = remember(entry.sourceId, entry.lineNumber, expanded) {
                val location = if (expanded) entry.sourceId else entry.sourceId.substringAfterLast('/').ifBlank { entry.sourceId }
                "$location:${entry.lineNumber.coerceAtLeast(0)}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.level.name, style = MaterialTheme.typography.labelSmall, color = consoleColor(entry.level))
                if (entry.sourceId.isNotBlank()) {
                    Text(source, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BrowserIconAction(R.drawable.ic_ui_info, resources.getString(R.string.dev_console_source)) { expanded = !expanded }
                }
            }
        }
    }
}

@Composable
internal fun NetworkRequestItem(request: NetworkRequestLog, onOpen: () -> Unit) {
    val resources = localizedResources()
    val name = remember(request.url) { Uri.parse(request.url).lastPathSegment?.takeIf { it.isNotBlank() }
        ?: Uri.parse(request.url).host ?: request.url }
    val statusColor = when {
        request.blocked -> BrowserColors.warning
        request.statusCode != null && request.statusCode in 200..399 -> BrowserColors.secure
        request.statusCode != null || request.errorCode != null || request.failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text(request.statusText(resources), color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
            Text(listOf(
                if (request.methodKnown) request.method else resources.getString(R.string.dev_storage_unknown),
                resources.getString(com.mybrowser.core.NetworkCategory.of(request).label),
                request.durationText(resources), request.sizeText(resources),
                resources.getString(if (request.pageScript) R.string.dev_enhanced_source_page_script else R.string.dev_enhanced_source_webview),
            ).joinToString(" · "), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            request.errorDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
    HorizontalDivider(color = BrowserColors.cardBorder, modifier = Modifier.padding(horizontal = 16.dp))
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
