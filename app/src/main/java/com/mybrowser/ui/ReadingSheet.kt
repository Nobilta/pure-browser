package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mybrowser.R
import com.mybrowser.reading.ReadingArticle
import kotlin.math.roundToInt
import com.mybrowser.reading.ReadingPosition
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun ReadingSheet(article: ReadingArticle, textZoom: Int, saved: Boolean, busy: Boolean,
    onTextZoom: (Int) -> Unit, onSave: () -> Unit, onCopy: () -> Unit, onOpenOriginal: () -> Unit, onDismiss: () -> Unit,
    initialPosition: ReadingPosition = ReadingPosition(), onPosition: (ReadingPosition) -> Unit = {}, onOpenLink: (String) -> Unit = {}) {
    var showTextSize by rememberSaveable { mutableStateOf(false) }
    val listState = remember(article.url) { LazyListState(initialPosition.index.coerceAtMost(article.blocks.size), initialPosition.offset) }
    val savePosition by rememberUpdatedState(onPosition)
    LaunchedEffect(article.url, listState) {
        snapshotFlow { ReadingPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().debounce(600).collect { savePosition(it) }
    }
    DisposableEffect(article.url, listState) {
        onDispose { savePosition(ReadingPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) }
    }
    // Android 10 can report zero system-bar insets inside a dialog. Both windows
    // fill the display, so observe the host window's insets before entering it.
    val contentInsets = WindowInsets.safeDrawing
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ApplySheetSystemBars(fullscreen = true)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cd_back), onClick = onDismiss)
                    Text(stringResource(R.string.reading_mode), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val textSizeLabel = stringResource(R.string.reading_text_size)
                    TextButton(onClick = { showTextSize = !showTextSize },
                        modifier = Modifier.semantics { contentDescription = textSizeLabel }) { Text("Aa") }
                    BrowserIconAction(R.drawable.ic_copy, stringResource(R.string.reading_copy), onClick = onCopy)
                    BrowserIconAction(R.drawable.ic_download, stringResource(if (saved) R.string.reading_saved else R.string.reading_save), !busy, onSave)
                }
                if (showTextSize) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sizeLabel = stringResource(R.string.reading_text_size)
                    Text(stringResource(R.string.site_text_zoom, textZoom))
                    Slider(value = textZoom.toFloat(), onValueChange = { onTextZoom(it.roundToInt()) }, valueRange = 75f..175f,
                        steps = 19, modifier = Modifier.weight(1f).padding(start = 12.dp).semantics { contentDescription = sizeLabel })
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        SelectionContainer { Text(article.title, style = MaterialTheme.typography.headlineMedium) }
                        TextButton(onClick = onOpenOriginal) { Text(stringResource(R.string.reading_open_original)) }
                        Text(article.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(article.blocks) { block ->
                        val size = (if (block.heading) 23f else 18f) * textZoom / 100
                        Column {
                            SelectionContainer { Text(block.text, fontSize = size.sp, lineHeight = (size * if (block.kind == "code") 1.35f else 1.6f).sp,
                                fontFamily = if (block.kind == "code") FontFamily.Monospace else FontFamily.Serif,
                                modifier = if (block.kind == "code" || block.kind == "quote") Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small).padding(12.dp) else Modifier,
                                color = MaterialTheme.colorScheme.onSurface) }
                            block.links.forEach { link ->
                                TextButton(onClick = { onOpenLink(link.url) }) { Text(link.text, maxLines = 2) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadingListSheet(articles: List<ReadingArticle>, busy: Boolean, onOpen: (ReadingArticle) -> Unit,
    onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<ReadingArticle?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.reading_list), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reading_offline_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            LibrarySearchField(query, stringResource(R.string.reading_search)) { query = it }
            val filtered = articles.filter { it.title.contains(query, true) || it.url.contains(query, true) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (filtered.isEmpty() && !busy) Text(stringResource(R.string.reading_empty), Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.url }) { article ->
                    ListItem(headlineContent = { Text(article.title) }, supportingContent = { Text(article.url, maxLines = 1) },
                        modifier = Modifier.clickable { onOpen(article) },
                        trailingContent = { BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.cd_delete), !busy) { delete = article } })
                    HorizontalDivider()
                }
            }
        }
    }
    delete?.let { article -> AlertDialog(onDismissRequest = { delete = null },
        title = { Text(stringResource(R.string.reading_delete)) }, text = { Text(article.title) },
        confirmButton = { TextButton(onClick = { delete = null; onDelete(article.url) }) { Text(stringResource(R.string.cd_delete)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}
