package com.mybrowser.ui.devtools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.launch
import com.mybrowser.ui.shell.BrowserIconAction

@Composable
internal fun SourceCodeTab(document: SourceDocument?, loading: Boolean, error: String?, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.tertiary, BrowserColors.secure, colors.onSurfaceVariant,
        colors.primary, BrowserColors.warning, colors.onSurfaceVariant)
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(document?.let { stringResource(R.string.dev_source_summary, it.characters) } ?: stringResource(R.string.ui_source),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            BrowserIconAction(R.drawable.ic_reload, stringResource(R.string.dev_source_refresh), !loading) {
                scope.launch { scroll.scrollToItem(0) }
                onRefresh()
            }
        }
        if (document?.truncated == true) Text(stringResource(R.string.dev_source_truncated_notice, document.characters),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        Surface(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            color = colors.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
                error != null || document == null || document.blocks.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Text(error ?: stringResource(R.string.ui_this_page_has_no_source_to_display), color = colors.onSurfaceVariant)
                }
                else -> SelectionContainer {
                    LazyColumn(state = scroll, contentPadding = PaddingValues(8.dp)) {
                        itemsIndexed(document.blocks, key = { index, _ -> index }, contentType = { _, _ -> "source" }) { _, block ->
                            val annotated = remember(block, palette) {
                                val text = block.text.removeSuffix("\n").removeSuffix("\r")
                                AnnotatedString.Builder(text).apply {
                                    block.spans.forEach { span ->
                                        val end = minOf(span.end, text.length)
                                        if (span.start < end) addStyle(SpanStyle(
                                            color = palette[span.token.ordinal],
                                            fontWeight = if (span.token == SourceToken.KEYWORD) FontWeight.Medium else null,
                                        ), span.start, end)
                                    }
                                }.toAnnotatedString()
                            }
                            Row(Modifier.fillMaxWidth()) {
                                Text(if (block.continuation) "↳" else block.line.toString(),
                                    Modifier.width(44.dp).padding(end = 8.dp), color = colors.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(annotated, Modifier.weight(1f), color = colors.onSurface,
                                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
