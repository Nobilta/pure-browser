package com.mybrowser.ui.devtools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.shell.BrowserIconAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No floating label: focus and IME changes must not change the search field's height. */
@Composable
internal fun DevToolsSearchField(query: String, label: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (query.isNotEmpty()) ({
            BrowserIconAction(R.drawable.ic_ui_close, stringResource(R.string.ui_clear)) { onQueryChange("") }
        }) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = label },
    )
}

@Composable
internal fun SelectableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
) {
    SelectionContainer(modifier) { Text(text, style = style, color = color, fontFamily = fontFamily) }
}

/** Bounded, lossless pages: selection spans the entire displayed page, including offscreen lines. */
internal fun selectableTextPages(text: String, pageSize: Int = 16 * 1024): List<String> {
    require(pageSize >= 2)
    if (text.isEmpty()) return listOf("")
    val result = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + pageSize, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start + pageSize / 2) end = newline + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}

@Composable
internal fun PagedSelectableText(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val pages = remember(text) { selectableTextPages(text) }
    var index by remember(text) { mutableIntStateOf(0) }
    val page = pages[index]
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    val blocks by produceState<List<SourceBlock>>(emptyList(), page, highlight) {
        value = if (highlight) withContext(Dispatchers.Default) { SourceHighlighter.parseChunk(page).blocks }
            else emptyList()
    }
    val palette = rememberSourcePalette()
    val annotated = remember(page, blocks, palette) {
        buildAnnotatedString {
            append(page)
            var offset = 0
            // Reject a stale asynchronous parse while switching pages.
            if (blocks.joinToString("") { it.text } == page) blocks.forEach { block ->
                block.spans.forEach { span ->
                    addStyle(androidx.compose.ui.text.SpanStyle(color = highlightColor(span.token, palette)),
                        offset + span.start, offset + span.end)
                }
                offset += block.text.length
            }
        }
    }
    Column(modifier) {
        if (pages.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index-- }, enabled = index > 0) { Text(stringResource(R.string.dev_source_prev)) }
            Text(stringResource(R.string.dev_text_page, index + 1, pages.size), Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { index++ }, enabled = index < pages.lastIndex) { Text(stringResource(R.string.dev_source_next)) }
        }
        key(page) {
            SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)) {
                Text(annotated, Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
