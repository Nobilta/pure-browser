package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.data.Bookmark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>, query: String, loading: Boolean, hasMore: Boolean, error: Boolean,
    onQueryChange: (String) -> Unit, onLoadMore: () -> Unit,
    onSelectBookmark: (String) -> Unit, onOpenNewTab: (String) -> Unit,
    onEditBookmark: (Bookmark) -> Unit, onCopy: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit,
    onImport: () -> Unit, onExport: () -> Unit, transferBusy: Boolean,
) {
    var actions by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bookmarks_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.bookmarks_clear), bookmarks.isNotEmpty(), onClearAll)
                Box {
                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.bookmarks_transfer), !transferBusy) { actions = true }
                    DropdownMenu(actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_import)) }, onClick = { actions = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_export)) }, onClick = { actions = false; onExport() })
                    }
                }
            }
            LibrarySearchField(query, stringResource(R.string.bookmarks_search), onQueryChange)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (bookmarks.isEmpty() && !loading) item {
                    Text(stringResource(if (error) R.string.library_load_failed else if (query.isBlank()) R.string.bookmarks_empty else R.string.library_no_results),
                        modifier = Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(Modifier.fillMaxWidth().clickable { onSelectBookmark(bookmark.url) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_bookmark), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bookmark.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(bookmark.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LibraryItemMenu(onOpenNewTab = { onOpenNewTab(bookmark.url) }, onCopy = { onCopy(bookmark.url) },
                            onEdit = { onEditBookmark(bookmark) }, onDelete = { onDeleteBookmark(bookmark.url) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { LibraryFooter(loading, hasMore, onLoadMore, error) }
            }
        }
    }
}

@Composable
fun BookmarkImportDialog(data: com.mybrowser.data.BookmarkImport, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bookmarks_import)) },
        text = { Text(stringResource(R.string.bookmarks_import_preview, data.entries.size, data.skipped)) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.bookmarks_import)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}
