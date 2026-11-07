package com.mybrowser.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.home.HomeShortcut
import com.mybrowser.home.ShortcutIconChange
import com.mybrowser.home.ShortcutSaveResult
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.theme.BrowserColors

/**
 * The navigation homepage: a hero, an optional search entry and the user's grouped shortcuts.
 *
 * With the address bar docked at the bottom, the real field is already on screen, so the page
 * draws no second search box. The whole band is centred in the lower half of the viewport and
 * capped at 520dp, which keeps it thumb-reachable on a phone and readable on a tablet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeDashboard(
    shortcuts: List<HomeShortcut>,
    onOpen: (HomeShortcut) -> Unit,
    onSave: suspend (String, String, String, ShortcutIconChange) -> ShortcutSaveResult,
    onRemove: suspend (HomeShortcut) -> Boolean,
    modifier: Modifier = Modifier,
    isIncognito: Boolean = false,
    hasPrivateIsolation: Boolean = true,
    searchEngineName: String = "",
    onSearchClick: () -> Unit = {},
    onAddShortcut: () -> Unit = {},
    bottomAddressBar: Boolean = true,
) {
    val textResources = localizedResources()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    if (isIncognito) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 12.dp)
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
                                text = stringResource(R.string.incognito_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(if (isIncognito) R.string.home_private_title else R.string.home_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!isIncognito) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 8.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    if (isIncognito) {
                        Column(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (hasPrivateIsolation) R.string.private_note_local_isolated
                                    else R.string.private_note_local_shared,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.private_note_remote),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!bottomAddressBar) {
                        SearchEntryPill(
                            engineName = searchEngineName,
                            onClick = onSearchClick,
                            modifier = Modifier.padding(top = 28.dp),
                        )
                    }
                }
                item {
                    ShortcutGrid(
                        shortcuts = shortcuts,
                        onOpen = onOpen,
                        onLongClick = { editingId = it.id },
                        onAdd = onAddShortcut,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }
        }
    }

    shortcuts.firstOrNull { it.id == editingId }?.let { shortcut ->
        key(shortcut.id) {
            HomeShortcutEditor(shortcut, onSave, onRemove, onDismiss = { editingId = null })
        }
    }
}

@Composable
private fun SearchEntryPill(
    engineName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_search),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.omnibar_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (engineName.isNotBlank()) Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, BrowserColors.cardBorder),
            ) {
                Text(
                    text = engineName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutGrid(
    shortcuts: List<HomeShortcut>,
    onOpen: (HomeShortcut) -> Unit,
    onLongClick: (HomeShortcut) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textResources = localizedResources()
    // Grouping runs on every recomposition of the homepage otherwise; the shortcut list is the
    // only input, so it is the only key.
    val rows = remember(shortcuts) {
        val grouped = shortcuts.chunked(4).toMutableList()
        if (grouped.isEmpty() || grouped.last().size == 4) grouped.add(emptyList())
        grouped.toList()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = textResources.getString(R.string.home_quick_links) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (shortcuts.isEmpty()) {
            Text(
                text = textResources.getString(R.string.ui_visit_a_site_choose_add_bookmark_then_select),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            )
            AddItem(
                onClick = onAdd,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.25f),
            )
        } else {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { shortcut ->
                        ShortcutItem(
                            shortcut = shortcut,
                            onClick = { onOpen(shortcut) },
                            onLongClick = { onLongClick(shortcut) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(4 - row.size) { slot ->
                        if (index == rows.lastIndex && slot == 0) {
                            AddItem(onClick = onAdd, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (index != rows.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutItem(
    shortcut: HomeShortcut,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textResources = localizedResources()
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = textResources.getString(R.string.home_shortcut_edit),
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            HomeShortcutIcon(
                title = shortcut.title,
                url = shortcut.url,
                icon = shortcut.icon,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun AddItem(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val textResources = localizedResources()
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ui_add),
                contentDescription = textResources.getString(R.string.home_shortcut_add),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = textResources.getString(R.string.home_shortcut_add),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
internal fun HomeShortcutIcon(title: String, url: String, icon: Bitmap?, modifier: Modifier = Modifier) {
    val textResources = localizedResources()
    if (icon != null && !icon.isRecycled) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = textResources.getString(R.string.ui_icon, title),
            contentScale = ContentScale.Fit,
            modifier = modifier.padding(7.dp),
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = title.trim().takeIf { it.isNotEmpty() }
                    ?.let { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() } ?: "·",
                style = MaterialTheme.typography.titleMedium,
                color = fallbackColor(url),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun fallbackColor(key: String): Color {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )
    return colors[(key.hashCode() and Int.MAX_VALUE) % colors.size]
}
