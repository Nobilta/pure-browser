package com.mybrowser.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.mybrowser.data.BookmarkFolder
import com.mybrowser.data.BookmarkFolders
import com.mybrowser.ui.shell.BrowserFullscreenSheet
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.LibraryFooter
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.browserSheetInsets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>, query: String, loading: Boolean, hasMore: Boolean, error: Boolean,
    onQueryChange: (String) -> Unit, onLoadMore: () -> Unit,
    onSelectBookmark: (String) -> Unit, onOpenNewTab: (String) -> Unit,
    onEditBookmark: (Bookmark) -> Unit, onCopy: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit,
    onImport: () -> Unit, onExport: () -> Unit, transferBusy: Boolean,
    library: BookmarkLibrary,
) {
    var actions by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<BookmarkFolder?>(null) }
    var folderEditor by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var moving by remember { mutableStateOf(false) }
    var movingFolder by remember { mutableStateOf<BookmarkFolder?>(null) }
    var deleteSelected by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<BookmarkFolder?>(null) }
    val folders = library.folders
    val parentId = library.path.lastOrNull()?.parentId ?: 0L
    LaunchedEffect(library.folderId, query) { selected = emptySet(); selecting = false }
    val back = {
        if (selecting) { selecting = false; selected = emptySet() }
        else if (library.folderId != 0L) library.openFolder(parentId)
        else onDismiss()
    }
    BrowserFullscreenSheet(onDismissRequest = back) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets()).padding(horizontal = 16.dp)) {
            BrowserSheetHeader(stringResource(R.string.bookmarks_title), onBack = back) {
                BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.bookmarks_clear), bookmarks.isNotEmpty(), onClearAll)
                Box {
                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.bookmarks_transfer), !transferBusy) { actions = true }
                    DropdownMenu(actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_new_folder)) }, onClick = {
                            actions = false; editingFolder = null; folderName = ""; folderEditor = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_select)) }, onClick = { actions = false; selecting = !selecting; selected = emptySet() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_import)) }, onClick = { actions = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_export)) }, onClick = { actions = false; onExport() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { library.openFolder(0) }) { Text(stringResource(R.string.bookmarks_root)) }
                library.path.forEach { folder ->
                    Text("›")
                    TextButton(onClick = { library.openFolder(folder.id) }) { Text(folder.title) }
                }
            }
            LibrarySearchField(query, stringResource(R.string.bookmarks_search), onQueryChange)
            if (selecting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bookmarks.isNotEmpty() && bookmarks.all { it.id in selected }, onCheckedChange = {
                    selected = if (it) bookmarks.map { bookmark -> bookmark.id }.toSet() else emptySet()
                })
                Text(stringResource(R.string.bookmarks_selected, selected.size), Modifier.weight(1f))
                TextButton(onClick = { movingFolder = null; moving = true }, enabled = selected.isNotEmpty() && !library.busy) { Text(stringResource(R.string.bookmarks_move)) }
                BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.cd_delete), selected.isNotEmpty() && !library.busy) { deleteSelected = true }
            }
            if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (query.isBlank()) items(folders.filter { it.parentId == library.folderId }, key = { "folder-${it.id}" }) { folder ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(headlineContent = { Text(folder.title) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), null) },
                        modifier = Modifier.clickable { library.openFolder(folder.id) },
                        trailingContent = { Box {
                            BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions), !library.busy) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_rename_folder)) }, onClick = {
                                    menu = false; editingFolder = folder; folderName = folder.title; folderEditor = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move)) }, onClick = { menu = false; movingFolder = folder; moving = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move_up)) }, onClick = { menu = false; library.mutate { reorder(folder.id, true, -1) } })
                                DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move_down)) }, onClick = { menu = false; library.mutate { reorder(folder.id, true, 1) } })
                                DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) }, onClick = { menu = false; deleteFolder = folder })
                            }
                        } })
                }
                if (bookmarks.isEmpty() && !loading && (query.isNotBlank() || folders.none { it.parentId == library.folderId })) item {
                    Text(stringResource(if (error) R.string.library_load_failed else if (query.isBlank()) R.string.bookmarks_empty else R.string.library_no_results),
                        modifier = Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    var itemMenu by remember { mutableStateOf(false) }
                    fun toggleSelection() { selected = if (bookmark.id in selected) selected - bookmark.id else selected + bookmark.id }
                    Row(Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (selecting) toggleSelection() else onSelectBookmark(bookmark.url) },
                        onLongClick = { selecting = true; toggleSelection() },
                        onLongClickLabel = stringResource(R.string.bookmarks_select)).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(bookmark.id in selected, { toggleSelection() })
                        else Icon(painterResource(R.drawable.ic_bookmark), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bookmark.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(bookmark.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (query.isNotBlank() && bookmark.folderId != 0L) Text(
                                runCatching { BookmarkFolders.path(bookmark.folderId, folders).joinToString(" / ") { it.title } }.getOrDefault(""),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions), !library.busy) { itemMenu = true }
                            DropdownMenu(itemMenu, { itemMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.context_open_new_tab)) }, onClick = { itemMenu = false; onOpenNewTab(bookmark.url) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.library_edit_bookmark)) }, onClick = { itemMenu = false; onEditBookmark(bookmark) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move)) }, onClick = { itemMenu = false; selected = setOf(bookmark.id); movingFolder = null; moving = true })
                                if (query.isBlank()) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move_up)) }, onClick = { itemMenu = false; library.mutate { reorder(bookmark.id, false, -1) } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move_down)) }, onClick = { itemMenu = false; library.mutate { reorder(bookmark.id, false, 1) } })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.context_copy_link)) }, onClick = { itemMenu = false; onCopy(bookmark.url) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) }, onClick = { itemMenu = false; onDeleteBookmark(bookmark.url) })
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { LibraryFooter(loading, hasMore, onLoadMore, error) }
            }
        }
    }
    }
    if (folderEditor) AlertDialog(onDismissRequest = { folderEditor = false },
        title = { Text(stringResource(if (editingFolder == null) R.string.bookmarks_new_folder else R.string.bookmarks_rename_folder)) },
        text = { OutlinedTextField(folderName, { folderName = it.take(BookmarkFolders.MAX_NAME) }, singleLine = true,
            label = { Text(stringResource(R.string.bookmarks_folder_name)) }) },
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = {
            val folder = editingFolder; val name = folderName; val target = library.folderId
            library.mutate { if (folder == null) addFolder(name, target) else updateFolder(folder.id, name, folder.parentId) }
            folderEditor = false
        }) { Text(stringResource(R.string.bookmark_save)) } },
        dismissButton = { TextButton(onClick = { folderEditor = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (moving) BookmarkFolderPicker(folders.filter { folder ->
        movingFolder?.let { parent -> BookmarkFolders.path(folder.id, folders).none { it.id == parent.id } } ?: true
    }, folders, onSelect = { target ->
        val folder = movingFolder; val ids = selected
        library.mutate { if (folder == null) moveBookmarks(ids, target) else updateFolder(folder.id, folder.title, target) }
        moving = false; selected = emptySet(); selecting = false
    }, onDismiss = { moving = false })
    if (deleteSelected) AlertDialog(onDismissRequest = { deleteSelected = false },
        title = { Text(stringResource(R.string.bookmarks_delete_selected)) },
        text = { Text(stringResource(R.string.bookmarks_selected, selected.size)) },
        confirmButton = { TextButton(onClick = { val ids = selected; library.mutate { deleteBookmarks(ids) }; selected = emptySet(); selecting = false; deleteSelected = false }) { Text(stringResource(R.string.cd_delete)) } },
        dismissButton = { TextButton(onClick = { deleteSelected = false }) { Text(stringResource(R.string.action_cancel)) } })
    deleteFolder?.let { folder -> AlertDialog(onDismissRequest = { deleteFolder = null },
        title = { Text(folder.title) }, text = { Text(stringResource(R.string.bookmarks_remove_folder_note)) },
        confirmButton = { TextButton(onClick = { library.mutate { removeFolder(folder.id) }; deleteFolder = null }) { Text(stringResource(R.string.cd_delete)) } },
        dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

@Composable
private fun BookmarkFolderPicker(choices: List<BookmarkFolder>, all: List<BookmarkFolder>, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bookmarks_move_to)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { TextButton(onClick = { onSelect(0) }) { Text(stringResource(R.string.bookmarks_root)) } }
            items(choices.sortedBy { BookmarkFolders.path(it.id, all).joinToString("/") { item -> item.title } }, key = { it.id }) { folder ->
                TextButton(onClick = { onSelect(folder.id) }) { Text(BookmarkFolders.path(folder.id, all).joinToString(" / ") { it.title }) }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun BookmarkImportDialog(data: com.mybrowser.data.BookmarkImport, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bookmarks_import)) },
        text = { Column {
            Text(stringResource(R.string.bookmarks_import_preview, data.entries.size, data.skipped))
            Text(stringResource(R.string.bookmarks_folder_count, data.folders.size))
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.bookmarks_import)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}
