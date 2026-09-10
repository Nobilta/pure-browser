package com.mybrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.tabs.ClosedTab
import com.mybrowser.tabs.TabState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(
    tabs: List<TabState>,
    currentIndex: Int,
    isIncognito: Boolean,
    canCreateTab: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onCloseOthers: () -> Unit,
    recentlyClosed: List<ClosedTab>,
    onReopen: (String) -> Unit,
    onClearRecent: () -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showRecent by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val filtered = tabs.filter { it.title.contains(query, true) || it.url.contains(query, true) }
    val currentId = tabs.getOrNull(currentIndex)?.id
    val filteredRecent = recentlyClosed.filter { it.title.contains(query, true) || it.url.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tabs_count, tabs.size), style = MaterialTheme.typography.titleLarge)
                    if (isIncognito) Text(stringResource(R.string.incognito_badge),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BrowserIconAction(R.drawable.ic_add, stringResource(R.string.tabs_new), canCreateTab, onNewTab)
                Box {
                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.tabs_actions)) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!isIncognito) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tabs_reopen)) },
                                enabled = recentlyClosed.isNotEmpty() && canCreateTab,
                                onClick = { menuOpen = false; recentlyClosed.firstOrNull()?.let { onReopen(it.id) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.tabs_clear_recent)) },
                                enabled = recentlyClosed.isNotEmpty(), onClick = { menuOpen = false; confirm = "recent" })
                        }

                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_others)) },
                            enabled = tabs.size > 1, onClick = { menuOpen = false; confirm = "others" })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_all)) },
                            onClick = { menuOpen = false; confirm = "all" })
                    }
                }
            }
            if (!isIncognito) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showRecent, onClick = { showRecent = false }, label = { Text(stringResource(R.string.tabs_open)) })
                FilterChip(selected = showRecent, onClick = { showRecent = true }, label = { Text(stringResource(R.string.tabs_recent, recentlyClosed.size)) })
            }
            LibrarySearchField(query, stringResource(R.string.tabs_search)) { query = it }
            if (showRecent && !isIncognito) {
                if (filteredRecent.isEmpty()) Text(stringResource(R.string.tabs_recent_empty), Modifier.padding(vertical = 24.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filteredRecent, key = { it.id }) { entry ->
                        ListItem(headlineContent = { Text(entry.title.ifBlank { entry.url }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(entry.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { TextButton(onClick = { onReopen(entry.id) }, enabled = canCreateTab) { Text(stringResource(R.string.tabs_restore)) } })
                        HorizontalDivider()
                    }
                }
            } else
            if (filtered.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { tab ->
                    Surface(shape = MaterialTheme.shapes.medium, color = if (tab.id == currentId)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().clickable { onSelectTab(tab.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(64.dp, 80.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                val thumbnail = tab.thumbnail
                                if (thumbnail != null && !thumbnail.isRecycled) Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxSize())
                                else Icon(painterResource(R.drawable.ic_tabs), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tab.title.ifBlank { stringResource(R.string.tab_untitled) },
                                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tab.url, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (tab.id == currentId) Text(stringResource(R.string.tabs_current),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            BrowserIconAction(R.drawable.ic_close, stringResource(R.string.cd_close_tab)) { onCloseTab(tab.id) }
                        }
                    }
                }
            }
            snackbarHostState?.let { SnackbarHost(it, modifier = Modifier.padding(bottom = 12.dp)) }
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(when (confirm) { "all" -> R.string.tabs_close_all; "recent" -> R.string.tabs_clear_recent; else -> R.string.tabs_close_others })) },
        text = {
            val count = if (confirm == "all") tabs.size else (tabs.size - 1).coerceAtLeast(0)
            if (confirm == "recent") Text(stringResource(R.string.tabs_clear_recent_confirm))
            else Text(pluralStringResource(R.plurals.tabs_close_confirm, count, count))
        },
        confirmButton = { TextButton(onClick = {
            val action = confirm
            confirm = null
            when (action) { "all" -> onCloseAll(); "recent" -> onClearRecent(); else -> onCloseOthers() }
        }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } })
}
