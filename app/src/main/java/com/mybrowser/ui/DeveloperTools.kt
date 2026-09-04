package com.mybrowser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mybrowser.core.ConsoleLogEntry
import com.mybrowser.core.ConsoleLogLevel
import com.mybrowser.core.NetworkRequestLog
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Lightweight, in-app diagnostics inspired by the useful part of X/Via's page tools.
 *
 * The Activity owns the stores because WebView callbacks are imperative and can arrive
 * while this sheet is closed. This composable only renders immutable snapshots and sends
 * commands back to the current WebView.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeveloperTools(
    webView: WebView?,
    onDismiss: () -> Unit,
    networkEntries: List<NetworkRequestLog> = emptyList(),
    consoleEntries: List<ConsoleLogEntry> = emptyList(),
    onClearNetwork: () -> Unit = {},
    onClearConsole: () -> Unit = {},
    pageUrl: String? = null,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pageSource by remember(webView) { mutableStateOf("加载中…") }
    // Developer tools contains a console input row as well as a potentially long output
    // list. Starting partially expanded hides that row behind the viewport on phones;
    // opening expanded makes the command surface immediately usable and the inner lists
    // retain their own scrolling behavior.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Source is deliberately capped. A large application can have a multi-megabyte DOM;
    // putting all of it in a Compose Text would otherwise stall the UI and retain a second
    // copy of the document while the sheet is open.
    LaunchedEffect(webView, pageUrl) {
        val view = webView ?: run {
            pageSource = "无法获取源码"
            return@LaunchedEffect
        }
        pageSource = "加载中…"
        runCatching {
            view.evaluateJavascript("document.documentElement?.outerHTML || ''") { raw ->
                pageSource = decodeJavascriptString(raw)
                    .ifBlank { "页面没有可显示的源码" }
                    .take(MAX_SOURCE_CHARS)
                    .let { source ->
                        if (source.length == MAX_SOURCE_CHARS) "$source\n\n…（源码已截断）" else source
                    }
            }
        }.onFailure { pageSource = "无法获取源码：${it.message.orEmpty()}" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = androidx.compose.ui.Modifier
            // Apply the IME inset to the sheet itself. Material3 consumes the inset
            // before passing constraints to some sheet content, so padding only the
            // inner column is not sufficient on edge-to-edge Android 15+ windows.
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // ModalBottomSheet does not resize its content on every WebView/IME
                // combination. Apply the inset explicitly so the JS command row and
                // execute button cannot end up underneath the software keyboard.
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "开发者工具",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("控制台")
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("网络")
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("源码")
                }
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                    Text("信息")
                }
            }

            when (selectedTab) {
                0 -> ConsoleTab(
                    entries = consoleEntries,
                    webView = webView,
                    onClear = onClearConsole,
                )
                1 -> NetworkTab(
                    entries = networkEntries,
                    onClear = onClearNetwork,
                )
                2 -> SourceCodeTab(pageSource)
                3 -> InfoTab(webView, pageUrl)
            }
        }
    }
}

@Composable
private fun ConsoleTab(
    entries: List<ConsoleLogEntry>,
    webView: WebView?,
    onClear: () -> Unit,
) {
    var command by remember { mutableStateOf("") }
    var evaluations by remember { mutableStateOf<List<ConsoleLine>>(emptyList()) }

    // The same action is exposed through both the visible button and the keyboard's
    // Done key. A modal sheet can sit behind the IME on edge-to-edge devices, so the
    // keyboard action must remain a complete execution path.
    val executeCommand: () -> Unit = {
        val expression = command.trim()
        if (expression.isNotEmpty()) {
            command = ""
            val view = webView
            if (view == null) {
                evaluations = appendEvaluation(evaluations, "> $expression\n< WebView 不可用")
            } else {
                runCatching {
                    view.evaluateJavascript(expression) { raw ->
                        appendEvaluation(
                            evaluations,
                            "> $expression\n${decodeJavascriptString(raw)}",
                        ).also { evaluations = it }
                    }
                }.onFailure {
                    evaluations = appendEvaluation(
                        evaluations,
                        "> $expression\n< 执行失败：${it.message.orEmpty()}",
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DiagnosticsHeader(
            title = "控制台 (${entries.size + evaluations.size})",
            onClear = {
                evaluations = emptyList()
                onClear()
            },
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            color = Color(0xFF1E1E1E),
            tonalElevation = 2.dp,
        ) {
            if (entries.isEmpty() && evaluations.isEmpty()) {
                Text(
                    text = "暂无控制台输出",
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                )
            } else {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { entry ->
                        Text(
                            text = formatConsoleEntry(entry),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = consoleColor(entry.level),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    evaluations.forEach { line ->
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFD4D4D4),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入 JavaScript 命令") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { executeCommand() }),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = command.isNotBlank() && webView != null,
                onClick = executeCommand,
            ) {
                Text("执行")
            }
        }
    }
}

@Composable
private fun NetworkTab(entries: List<NetworkRequestLog>, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        DiagnosticsHeader(
            title = "网络请求 (${entries.size})",
            onClear = onClear,
        )
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无请求。打开或刷新页面后会在这里显示真实 WebView 请求。")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            ) {
                items(entries.asReversed(), key = { it.id }) { request ->
                    NetworkRequestItem(request)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsHeader(title: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClear) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "清空",
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("清空")
        }
    }
}

@Composable
private fun NetworkRequestItem(request: NetworkRequestLog) {
    val statusColor = when {
        request.blocked -> Color(0xFFFF9800)
        request.statusCode != null && request.statusCode in 200..399 -> Color(0xFF4CAF50)
        request.statusCode != null || request.errorCode != null -> Color(0xFFF44336)
        else -> Color(0xFF90CAF9)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = request.method,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    text = request.statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
            Text(
                text = request.url,
                fontSize = 12.sp,
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
                        append(request.sizeText)
                        if (request.isForMainFrame) append(" · 主文档")
                        if (request.blocked) append(" · 过滤器")
                    },
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
                Text(text = request.durationText, fontSize = 11.sp, color = Color.Gray)
            }
            request.errorDescription?.let { description ->
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = Color(0xFFE57373),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceCodeTab(source: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        color = Color(0xFF1E1E1E),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = source,
            modifier = Modifier
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFD4D4D4),
        )
    }
}

@Composable
private fun InfoTab(webView: WebView?, pageUrl: String?) {
    var pageInfo by remember(webView, pageUrl) { mutableStateOf<PageInfo?>(null) }

    LaunchedEffect(webView, pageUrl) {
        val view = webView ?: return@LaunchedEffect
        pageInfo = null
        runCatching {
            view.evaluateJavascript(
                """
                JSON.stringify({
                    title: document.title || '',
                    url: window.location.href || '',
                    userAgent: navigator.userAgent || '',
                    cookiesEnabled: navigator.cookieEnabled,
                    screenWidth: screen.width,
                    screenHeight: screen.height,
                    language: navigator.language || ''
                })
                """.trimIndent(),
            ) { raw ->
                val json = decodeJavascriptString(raw)
                val parsed = runCatching { JSONObject(json) }.getOrNull()
                val url = parsed?.optString("url").orEmpty().ifBlank { pageUrl.orEmpty() }
                val uri = runCatching { url.toUri() }.getOrNull()
                pageInfo = PageInfo(
                    title = parsed?.optString("title").orEmpty().ifBlank { view.title.orEmpty() },
                    url = url,
                    protocol = uri?.scheme?.uppercase().orEmpty().ifBlank { "—" },
                    host = uri?.host ?: uri?.authority.orEmpty(),
                    userAgent = parsed?.optString("userAgent").orEmpty()
                        .ifBlank { view.settings.userAgentString.orEmpty() },
                    language = parsed?.optString("language").orEmpty().ifBlank { "—" },
                    cookiesEnabled = parsed?.optBoolean("cookiesEnabled", true) ?: true,
                    screen = "${parsed?.optInt("screenWidth", 0)} × ${parsed?.optInt("screenHeight", 0)}",
                )
            }
        }.onFailure {
            pageInfo = PageInfo(
                title = view.title.orEmpty(),
                url = pageUrl.orEmpty(),
                protocol = pageUrl.orEmpty().toUri().scheme?.uppercase().orEmpty().ifBlank { "—" },
                host = pageUrl.orEmpty().toUri().host.orEmpty(),
                userAgent = view.settings.userAgentString.orEmpty(),
                language = "—",
                cookiesEnabled = true,
                screen = "—",
            )
        }
    }

    if (pageInfo == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("读取页面信息中…")
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            val info = pageInfo ?: return@LazyColumn
            item {
                InfoItem("页面标题", info.title)
                InfoItem("URL", info.url)
                InfoItem("协议", info.protocol)
                InfoItem("主机", info.host)
                InfoItem("User Agent", info.userAgent)
                InfoItem("语言", info.language)
                InfoItem("Cookie", if (info.cookiesEnabled) "已启用" else "已禁用")
                InfoItem("屏幕", info.screen)
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
        )
        Text(
            text = value.ifBlank { "—" },
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

private data class ConsoleLine(val text: String)

private data class PageInfo(
    val title: String,
    val url: String,
    val protocol: String,
    val host: String,
    val userAgent: String,
    val language: String,
    val cookiesEnabled: Boolean,
    val screen: String,
)

private fun appendEvaluation(current: List<ConsoleLine>, text: String): List<ConsoleLine> =
    (current + ConsoleLine(text)).takeLast(MAX_EVALUATIONS)

private fun formatConsoleEntry(entry: ConsoleLogEntry): String {
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
    return "$prefix$location ${entry.message}"
}

private fun consoleColor(level: ConsoleLogLevel): Color = when (level) {
    ConsoleLogLevel.ERROR -> Color(0xFFFF8A80)
    ConsoleLogLevel.WARNING -> Color(0xFFFFD180)
    ConsoleLogLevel.DEBUG -> Color(0xFF80CBC4)
    ConsoleLogLevel.TIP, ConsoleLogLevel.INFO -> Color(0xFF9CCC65)
    ConsoleLogLevel.LOG -> Color(0xFFD4D4D4)
}

/** evaluateJavascript returns a JSON string literal, including escaped newlines/quotes. */
private fun decodeJavascriptString(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "null") return ""
    return runCatching { JSONTokener(value).nextValue() as? String ?: value }
        .getOrElse { value.removeSurrounding("\"") }
}

private const val MAX_SOURCE_CHARS = 1_000_000
private const val MAX_EVALUATIONS = 100
