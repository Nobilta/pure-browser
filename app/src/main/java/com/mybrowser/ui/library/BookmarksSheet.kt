package com.mybrowser.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
import com.mybrowser.ui.shell.LibrarySelectionBar
import com.mybrowser.ui.shell.libraryItemSelection
import com.mybrowser.ui.shell.browserSheetInsets
import com.mybrowser.ui.shell.BrowserAlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>, query: String, loading: Boolean, hasMore: Boolean, error: Boolean,
    onQueryChange: (String) -> Unit, onLoadMore: () -> Unit,
    onSelectBookmark: (String) -> Unit, onOpenNewTab: (String) -> Unit,
    onEditBookmark: (Bookmark) -> Unit, onCopy: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteBookmarks: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
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
    // Search results span folders. Resolve every path once per folder list rather than walking
    // the parent chain for each visible row; with a 50-row page that was quadratic.
    val folderPaths = remember(folders) { BookmarkFolders.paths(folders) }
    val parentId = library.path.lastOrNull()?.parentId ?: 0L
    val listState = rememberLazyListState()
    val drag = remember(listState) { BookmarkDragState(listState) }
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val folderRows = if (query.isBlank()) folders.filter { it.parentId == library.folderId } else emptyList()
    val keys = folderRows.map { -it.id } + bookmarks.map { it.id }
    val keySet = keys.toSet()
    val bookmarksById = bookmarks.associateBy { it.id }
    val foldersById = folderRows.associateBy { -it.id }
    LaunchedEffect(keys, library.busy) { drag.sync(keys, library.busy) }
    LaunchedEffect(library.folderId, query) {
        drag.cancel(keys); selected = emptySet(); selecting = false
        listState.scrollToItem(0)
    }
    val canLoadMore by rememberUpdatedState(hasMore && !loading && !error)
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(drag.active) {
        if (drag.active != null) drag.scrollAtEdge(edge) {
            if (canLoadMore) loadMore()
        }
    }
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    fun finishDrag() {
        drag.finish()?.let { library.moveRelative(kotlin.math.abs(it.key), it.key < 0, kotlin.math.abs(it.anchor), it.after) }
    }
    fun accessibleMove(key: Long, direction: Int): Boolean {
        if (library.busy || drag.active != null) return false
        val peers = keys.filter { (it < 0) == (key < 0) }
        val anchor = peers.getOrNull(peers.indexOf(key) + direction) ?: return false
        library.moveRelative(kotlin.math.abs(key), key < 0, kotlin.math.abs(anchor), direction > 0)
        return true
    }
    val back = {
        if (drag.active != null) drag.cancel(keys)
        else if (selecting) { selecting = false; selected = emptySet() }
        else if (library.folderId != 0L) library.openFolder(parentId)
        else onDismiss()
    }
    BrowserFullscreenSheet(onDismissRequest = back) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets()).padding(horizontal = 16.dp)) {
            BrowserSheetHeader(stringResource(R.string.bookmarks_title), onBack = back) {
                Box {
                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.bookmarks_transfer), !transferBusy && !library.busy && drag.active == null) { actions = true }
                    DropdownMenu(actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_new_folder)) }, onClick = {
                            actions = false; editingFolder = null; folderName = ""; folderEditor = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_import)) }, onClick = { actions = false; onImport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_export)) }, onClick = { actions = false; onExport() })
                    }
                }
            }
            if (library.path.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                library.path.forEachIndexed { index, folder ->
                    if (index > 0) Text("›")
                    TextButton(onClick = { drag.cancel(keys); library.openFolder(folder.id) }, enabled = !library.busy) { Text(folder.title) }
                }
            }
            LibrarySearchField(query, stringResource(R.string.bookmarks_search), {
                if (!library.busy) { drag.cancel(keys); onQueryChange(it) }
            })
            LibrarySelectionBar(
                visible = selecting,
                allSelected = bookmarks.isNotEmpty() && bookmarks.all { it.id in selected },
                onToggleSelectAll = {
                    if (drag.active == null && !library.busy) selected = if (it) bookmarks.map { bookmark -> bookmark.id }.toSet() else emptySet()
                },
                countText = stringResource(R.string.bookmarks_selected, selected.size),
            ) {
                TextButton(onClick = { movingFolder = null; moving = true }, enabled = selected.isNotEmpty() && !library.busy && drag.active == null) { Text(stringResource(R.string.bookmarks_move)) }
                BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.cd_delete), selected.isNotEmpty() && !library.busy && drag.active == null) { deleteSelected = true }
            }
            if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), state = listState, userScrollEnabled = drag.active == null,
                contentPadding = PaddingValues(bottom = 16.dp)) {
                items(drag.order.filter { it in keySet }, key = { it }) { key ->
                    val folder = foldersById[key]
                    val bookmark = bookmarksById[key]
                    val dragging = drag.active == key
                    var menu by remember { mutableStateOf(false) }
                    val rowMotion = Modifier.animateItem(placementSpec = if (dragging) null else androidx.compose.animation.core.spring())
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = drag.translation(key); shadowElevation = if (dragging) 8.dp.toPx() else 0f }
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                    if (folder != null) {
                        Row(rowMotion.fillMaxWidth().combinedClickable(
                            onClick = { if (!selecting && !library.busy) library.openFolder(folder.id) },
                            onLongClick = { if (!library.busy) { selecting = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) } },
                            onLongClickLabel = stringResource(R.string.bookmarks_organize),
                        ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) BookmarkDragHandle(key, drag, !library.busy,
                                onStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onEnd = ::finishDrag, onMove = { accessibleMove(key, it) })
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(folder.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Box {
                                BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions), !library.busy && drag.active == null) { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_rename_folder)) }, onClick = {
                                        menu = false; editingFolder = folder; folderName = folder.title; folderEditor = true
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move)) }, onClick = { menu = false; movingFolder = folder; moving = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) }, onClick = { menu = false; deleteFolder = folder })
                                }
                            }
                        }
                    } else if (bookmark != null) {
                        Row(rowMotion.fillMaxWidth().libraryItemSelection(
                            selecting = selecting, selected = bookmark.id in selected,
                            selectionLabel = stringResource(R.string.bookmarks_select),
                            onOpen = { if (!library.busy) onSelectBookmark(bookmark.url) },
                            onToggleSelection = {
                                if (!library.busy && drag.active == null) {
                                    if (!selecting) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selecting = true
                                    selected = if (bookmark.id in selected) selected - bookmark.id else selected + bookmark.id
                                }
                            }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting && query.isBlank()) BookmarkDragHandle(key, drag, !library.busy,
                                onStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onEnd = ::finishDrag, onMove = { accessibleMove(key, it) })
                            BookmarkIcon(bookmark.url)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bookmark.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(bookmark.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (query.isNotBlank() && bookmark.folderId != 0L) Text(
                                    folderPaths[bookmark.folderId].orEmpty().joinToString(" / ") { it.title },
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selecting) Checkbox(bookmark.id in selected, onCheckedChange = null)
                            else Box {
                                BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions), !library.busy) { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.context_open_new_tab)) }, onClick = { menu = false; onOpenNewTab(bookmark.url) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.library_edit_bookmark)) }, onClick = { menu = false; onEditBookmark(bookmark) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_move)) }, onClick = { menu = false; selected = setOf(bookmark.id); movingFolder = null; moving = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.context_copy_link)) }, onClick = { menu = false; onCopy(bookmark.url) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) }, onClick = { menu = false; onDeleteBookmark(bookmark.url) })
                                }
                            }
                        }
                    }
                }
                if (keys.isEmpty() && !loading) item {
                    Text(stringResource(if (error) R.string.library_load_failed else if (query.isBlank()) R.string.bookmarks_empty else R.string.library_no_results),
                        modifier = Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { LibraryFooter(loading, hasMore, onLoadMore, error) }
            }
        }
    }
    }
    if (folderEditor) BrowserAlertDialog(onDismissRequest = { folderEditor = false },
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
        movingFolder?.let { parent -> folderPaths[folder.id].orEmpty().none { it.id == parent.id } } ?: true
    }, folders, onSelect = { target ->
        val folder = movingFolder; val ids = selected
        library.mutate { if (folder == null) moveBookmarks(ids, target) else updateFolder(folder.id, folder.title, target) }
        moving = false; selected = emptySet(); selecting = false
    }, onDismiss = { moving = false })
    if (deleteSelected) com.mybrowser.ui.shell.BrowserConfirmDialog(
        title = stringResource(R.string.bookmarks_delete_selected),
        message = stringResource(R.string.bookmarks_selected, selected.size),
        confirmText = stringResource(R.string.cd_delete),
        onConfirm = {
            val ids = selected
            selected = emptySet(); selecting = false; deleteSelected = false
            onDeleteBookmarks(ids)
        },
        onDismiss = { deleteSelected = false },
    )
    deleteFolder?.let { folder -> com.mybrowser.ui.shell.BrowserConfirmDialog(
        title = folder.title,
        message = stringResource(R.string.bookmarks_remove_folder_note),
        confirmText = stringResource(R.string.cd_delete),
        onConfirm = { library.mutate { removeFolder(folder.id) }; deleteFolder = null },
        onDismiss = { deleteFolder = null },
    ) }
}

@Composable
private fun BookmarkDragHandle(key: Long, drag: BookmarkDragState, enabled: Boolean,
    onStart: () -> Unit, onEnd: () -> Unit, onMove: (Int) -> Boolean) {
    val label = stringResource(R.string.bookmarks_drag)
    val up = stringResource(R.string.bookmarks_move_up)
    val down = stringResource(R.string.bookmarks_move_down)
    val end by rememberUpdatedState(onEnd)
    val start by rememberUpdatedState(onStart)
    Box(Modifier.size(48.dp).semantics {
        contentDescription = label
        customActions = if (enabled) listOf(CustomAccessibilityAction(up) { onMove(-1) }, CustomAccessibilityAction(down) { onMove(1) }) else emptyList()
    }.pointerInput(key, enabled) {
        if (enabled) detectDragGestures(
            onDragStart = { if (drag.start(key)) start() },
            onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
            onDragEnd = { end() },
            onDragCancel = { if (drag.active == key) drag.cancel() },
        )
    }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_drag_handle), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookmarkFolderPicker(choices: List<BookmarkFolder>, all: List<BookmarkFolder>, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val rows = remember(choices, all) {
        val paths = BookmarkFolders.paths(all)
        choices.map { it to paths.getValue(it.id).joinToString(" / ") { folder -> folder.title } }
            .sortedBy { it.second }
    }
    BrowserAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bookmarks_move_to)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { TextButton(onClick = { onSelect(0) }) { Text(stringResource(R.string.bookmarks_root)) } }
            items(rows, key = { it.first.id }) { (folder, path) ->
                TextButton(onClick = { onSelect(folder.id) }) { Text(path) }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun BookmarkImportDialog(data: com.mybrowser.data.BookmarkImport, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BrowserAlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.bookmarks_import)) },
        text = { Column {
            Text(stringResource(R.string.bookmarks_import_preview, data.entries.size, data.skipped))
            Text(stringResource(R.string.bookmarks_folder_count, data.folders.size))
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.bookmarks_import)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun BookmarkExportDialog(result: com.mybrowser.data.BookmarkExportResult, onDismiss: () -> Unit) {
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_exported, result.count)) },
        text = if (result.unsupported > 0 || result.omitted > 0) {
            {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (result.unsupported > 0) {
                        Text(stringResource(R.string.bookmarks_export_unsupported, result.unsupported))
                    }
                    if (result.omitted > 0) {
                        Text(stringResource(R.string.bookmarks_exported_partial, result.count, result.omitted))
                    }
                }
            }
        } else null,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_ok)) }
        },
    )
}
