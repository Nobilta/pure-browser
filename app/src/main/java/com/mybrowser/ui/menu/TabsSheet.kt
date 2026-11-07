package com.mybrowser.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mybrowser.R
import com.mybrowser.core.UrlUtils
import com.mybrowser.tabs.TabState
import com.mybrowser.ui.components.ThumbZoneLibrarySheet
import com.mybrowser.ui.shell.BrowserAlertDialog
import com.mybrowser.ui.shell.BrowserDropdownMenu
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.theme.BrowserColors

/**
 * Open tabs as a visual grid, above a thumb-zone "new tab" button.
 *
 * A list row can only show a title; a browser tab is a page, and its thumbnail is the fastest
 * way to recognise it. Search is pinned under the header so the keyboard cannot squeeze the grid,
 * the grid keeps two cards per row on every phone width, and the primary action sits at the bottom
 * edge where the thumb already is. The active card carries a 2dp accent ring and a selected
 * semantic, and a swipe in either direction closes a tab.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    onCloseOthers: (String) -> Unit,
    onDismiss: () -> Unit,
    onMoveTab: (String, Int) -> Unit = { _, _ -> },
    onGroupTab: (String, String) -> Unit = { _, _ -> },
    // Observed so in-place TabState mutations (group, title, thumbnail) recompose this sheet.
    revision: Int = 0,
) {
    @Suppress("UNUSED_VARIABLE")
    val observedRevision = revision
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var closeOthersKeepId by remember { mutableStateOf<String?>(null) }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var groupTarget by remember { mutableStateOf<TabState?>(null) }
    var groupName by remember { mutableStateOf("") }
    val groups = remember(tabs, revision) {
        tabs.map { it.group }.filter { it.isNotEmpty() }.distinct()
    }
    val activeGroupFilter = groupFilter?.takeIf { it in groups }
    LaunchedEffect(activeGroupFilter) {
        if (groupFilter != activeGroupFilter) groupFilter = activeGroupFilter
    }
    val filtered = remember(tabs, revision, query, activeGroupFilter) {
        tabs.filter { (activeGroupFilter == null || it.group == activeGroupFilter) &&
            (it.title.contains(query, true) || it.url.contains(query, true)) }
    }
    val currentId = tabs.getOrNull(currentIndex)?.id

    ThumbZoneLibrarySheet(
        title = stringResource(R.string.tabs_count, tabs.size),
        query = query,
        searchLabel = stringResource(R.string.tabs_search),
        primaryActionText = stringResource(R.string.tabs_new),
        primaryActionIconRes = R.drawable.ic_ui_add,
        canExecutePrimaryAction = canCreateTab,
        isEmpty = filtered.isEmpty(),
        emptyText = stringResource(R.string.library_no_results),
        onQueryChange = { query = it },
        onPrimaryAction = onNewTab,
        onDismiss = onDismiss,
        filterBar = {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (isIncognito) Row(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_incognito),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.incognito_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (groups.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(groupFilter == null, { groupFilter = null },
                        label = { Text(stringResource(R.string.tabs_all_groups)) })
                    groups.forEach { group ->
                        FilterChip(groupFilter == group, { groupFilter = group }, label = { Text(group) })
                    }
                }
            }
        },
        headerActions = {
            Box {
                BrowserIconAction(R.drawable.ic_ui_more, stringResource(R.string.tabs_actions)) { menuOpen = true }
                BrowserDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_others)) },
                        enabled = tabs.size > 1, onClick = {
                            menuOpen = false
                            closeOthersKeepId = currentId
                            confirm = "others"
                        })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_all)) },
                        onClick = { menuOpen = false; confirm = "all" })
                }
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Fixed counts, never Adaptive: a 320dp phone must still show two cards rather than
            // collapsing to one. Wider windows only gain columns.
            val columns = when {
                maxWidth < 600.dp -> 2
                maxWidth < 840.dp -> 3
                else -> 4
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(filtered, key = { _, tab -> tab.id }) { _, tab ->
                    TabGridCard(
                        tab = tab,
                        isSelected = tab.id == currentId,
                        canMovePrevious = tabs.firstOrNull()?.id != tab.id,
                        canMoveNext = tabs.lastOrNull()?.id != tab.id,
                        showCloseOthers = tabs.size > 1,
                        onSelect = { onSelectTab(tab.id) },
                        onClose = { onCloseTab(tab.id) },
                        onMove = { delta -> onMoveTab(tab.id, delta) },
                        onGroup = { groupTarget = tab; groupName = tab.group },
                        onCloseOthers = onCloseOthers,
                    )
                }
            }
        }
    }
    groupTarget?.let { target -> BrowserAlertDialog(onDismissRequest = { groupTarget = null },
        title = { Text(stringResource(R.string.tabs_group)) },
        text = { Column {
            androidx.compose.material3.OutlinedTextField(groupName, { groupName = it.take(40) }, singleLine = true,
                label = { Text(stringResource(R.string.tabs_group_name)) })
            Text(stringResource(R.string.tabs_group_hint), Modifier.padding(vertical = 8.dp))
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                groups.forEach { name -> TextButton(onClick = { groupName = name }) { Text(name) } }
            }
        } },
        confirmButton = { TextButton(onClick = { onGroupTab(target.id, groupName); groupTarget = null }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = { groupTarget = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    if (confirm != null) {
        val count = if (confirm == "all") tabs.size else (tabs.size - 1).coerceAtLeast(0)
        com.mybrowser.ui.shell.BrowserConfirmDialog(
            title = stringResource(if (confirm == "all") R.string.tabs_close_all else R.string.tabs_close_others),
            message = pluralStringResource(R.plurals.tabs_close_confirm, count, count),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                val action = confirm
                confirm = null
                if (action == "all") onCloseAll() else onCloseOthers(closeOthersKeepId ?: currentId.orEmpty())
            },
            onDismiss = { confirm = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabGridCard(
    tab: TabState,
    isSelected: Boolean,
    canMovePrevious: Boolean,
    canMoveNext: Boolean,
    showCloseOthers: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onMove: (Int) -> Unit,
    onGroup: () -> Unit,
    onCloseOthers: (String) -> Unit,
) {
    val context = LocalContext.current
    val textResources = com.mybrowser.ui.shell.localizedResources()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onClose()
                true
            } else false
        },
    )
    var itemMenu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val thumbnailShape = MaterialTheme.shapes.small
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_close),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Box {
            val cardBorder = if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, BrowserColors.cardBorder)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                    .border(cardBorder, shape)
                    .combinedClickable(
                        role = Role.Tab,
                        onClick = onSelect,
                        onLongClick = { itemMenu = true },
                        onLongClickLabel = textResources.getString(R.string.library_item_actions),
                    )
                    .semantics { selected = isSelected },
            ) {
                Column(Modifier.fillMaxWidth().padding(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(tab)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tab.title.ifBlank { stringResource(R.string.tab_untitled) },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 44dp hit target around a 28dp visual disc: the close action is common
                        // enough to sit inside the card, but must not be a thumb-width accident.
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onClose)
                                .semantics {
                                    contentDescription = textResources.getString(R.string.cd_close_tab)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ui_close),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(top = 2.dp)
                            .clip(thumbnailShape)
                            .background(BrowserColors.recessedWell)
                            .border(
                                0.5.dp,
                                BrowserColors.cardBorder,
                                thumbnailShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Thumbnail(tab)
                    }
                }
            }
            BrowserDropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.context_copy_link)) },
                    onClick = {
                        itemMenu = false
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(tab.title, tab.url))
                    },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.tabs_move_previous)) },
                    enabled = canMovePrevious, onClick = { itemMenu = false; onMove(-1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.tabs_move_next)) },
                    enabled = canMoveNext, onClick = { itemMenu = false; onMove(1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.tabs_group)) },
                    onClick = { itemMenu = false; onGroup() })
                DropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_others)) },
                    enabled = showCloseOthers, onClick = { itemMenu = false; onCloseOthers(tab.id) })
            }
        }
    }
}

@Composable
private fun Favicon(tab: TabState) {
    val icon = tab.favicon
    if (icon != null && !icon.isRecycled) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(16.dp).clip(MaterialTheme.shapes.extraSmall),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val host = UrlUtils.hostOf(tab.url)?.trim().orEmpty()
            Text(
                text = if (host.isEmpty()) "·" else host.substring(0, host.offsetByCodePoints(0, 1)).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Thumbnail(tab: TabState) {
    val thumbnail = tab.thumbnail
    if (thumbnail != null && !thumbnail.isRecycled) {
        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_globe),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
            Text(
                text = UrlUtils.hostOf(tab.url) ?: tab.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
