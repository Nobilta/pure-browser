package com.mybrowser.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.mybrowser.tabs.TabState
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.userItemMotion
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.BrowserAlertDialog

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
    onDismiss: () -> Unit,
    onMoveTab: (String, Int) -> Unit = { _, _ -> },
    onGroupTab: (String, String) -> Unit = { _, _ -> },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var groupTarget by remember { mutableStateOf<TabState?>(null) }
    var groupName by remember { mutableStateOf("") }
    val groups = tabs.map { it.group }.filter { it.isNotEmpty() }.distinct()
    val filtered = tabs.filter { (groupFilter == null || it.group == groupFilter) &&
        (it.title.contains(query, true) || it.url.contains(query, true)) }
    val currentId = tabs.getOrNull(currentIndex)?.id
    BrowserBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            BrowserSheetHeader(stringResource(R.string.tabs_count, tabs.size), onBack = onDismiss) {
                BrowserIconAction(R.drawable.ic_add, stringResource(R.string.tabs_new), canCreateTab, onNewTab)
                Box {
                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.tabs_actions)) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_others)) },
                            enabled = tabs.size > 1, onClick = { menuOpen = false; confirm = "others" })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_all)) },
                            onClick = { menuOpen = false; confirm = "all" })
                    }
                }
            }
            if (isIncognito) Text(stringResource(R.string.incognito_badge), Modifier.padding(start = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LibrarySearchField(query, stringResource(R.string.tabs_search)) { query = it }
            if (groups.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(groupFilter == null, { groupFilter = null }, label = { Text(stringResource(R.string.tabs_all_groups)) })
                groups.forEach { group -> FilterChip(groupFilter == group, { groupFilter = group }, label = { Text(group) }) }
            }
            if (filtered.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { tab ->
                    var itemMenu by remember { mutableStateOf(false) }
                    Surface(shape = MaterialTheme.shapes.medium, color = if (tab.id == currentId)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = userItemMotion().fillMaxWidth().clickable { onSelectTab(tab.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(64.dp, 80.dp).clip(MaterialTheme.shapes.small)
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
                                if (tab.group.isNotEmpty()) Text(tab.group, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            Column {
                                BrowserIconAction(R.drawable.ic_close, stringResource(R.string.cd_close_tab)) { onCloseTab(tab.id) }
                                Box {
                                    BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions)) { itemMenu = true }
                                    DropdownMenu(itemMenu, { itemMenu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_move_previous)) },
                                            enabled = tabs.firstOrNull()?.id != tab.id, onClick = { itemMenu = false; onMoveTab(tab.id, -1) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_move_next)) },
                                            enabled = tabs.lastOrNull()?.id != tab.id, onClick = { itemMenu = false; onMoveTab(tab.id, 1) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tabs_group)) },
                                            onClick = { itemMenu = false; groupTarget = tab; groupName = tab.group })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    groupTarget?.let { target -> BrowserAlertDialog(onDismissRequest = { groupTarget = null },
        title = { Text(stringResource(R.string.tabs_group)) },
        text = { Column {
            OutlinedTextField(groupName, { groupName = it.take(40) }, singleLine = true,
                label = { Text(stringResource(R.string.tabs_group_name)) })
            Text(stringResource(R.string.tabs_group_hint), Modifier.padding(vertical = 8.dp))
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                groups.forEach { name -> TextButton(onClick = { groupName = name }) { Text(name) } }
            }
        } },
        confirmButton = { TextButton(onClick = { onGroupTab(target.id, groupName); groupTarget = null }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = { groupTarget = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    if (confirm != null) BrowserAlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(if (confirm == "all") R.string.tabs_close_all else R.string.tabs_close_others)) },
        text = {
            val count = if (confirm == "all") tabs.size else (tabs.size - 1).coerceAtLeast(0)
            Text(pluralStringResource(R.plurals.tabs_close_confirm, count, count))
        },
        confirmButton = { TextButton(onClick = {
            val action = confirm
            confirm = null
            if (action == "all") onCloseAll() else onCloseOthers()
        }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } })
}
