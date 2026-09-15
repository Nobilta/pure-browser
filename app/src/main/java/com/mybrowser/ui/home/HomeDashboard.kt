package com.mybrowser.ui.home

import com.mybrowser.R
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.home.HomeShortcut
import com.mybrowser.home.ShortcutIconChange
import com.mybrowser.home.ShortcutSaveResult
import com.mybrowser.ui.shell.localizedResources

/** Material 3 navigation homepage rendered above a blank WebView document. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeDashboard(
    shortcuts: List<HomeShortcut>,
    onOpen: (HomeShortcut) -> Unit,
    onSave: suspend (String, String, String, ShortcutIconChange) -> ShortcutSaveResult,
    onRemove: suspend (HomeShortcut) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val textResources = localizedResources()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = textResources.getString(R.string.ui_favorite_sites),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (shortcuts.isEmpty()) Text(
                        text = textResources.getString(R.string.ui_add_sites_to_your_homepage_when_bookmarking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (shortcuts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyHomeCard()
                }
            } else {
                items(shortcuts, key = HomeShortcut::id) { shortcut ->
                    HomeShortcutTile(
                        shortcut = shortcut,
                        onClick = { onOpen(shortcut) },
                        onLongClick = { editingId = shortcut.id },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    shortcut: HomeShortcut,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val textResources = localizedResources()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = textResources.getString(R.string.home_shortcut_edit),
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeShortcutIcon(shortcut.title, shortcut.url, shortcut.icon, Modifier.size(60.dp))
        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun HomeShortcutIcon(title: String, url: String, icon: Bitmap?, modifier: Modifier = Modifier) {
    val textResources = localizedResources()
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        if (icon != null && !icon.isRecycled) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = textResources.getString(R.string.ui_icon, title),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.trim().takeIf { it.isNotEmpty() }
                        ?.let { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() } ?: "·",
                    style = MaterialTheme.typography.headlineMedium,
                    color = fallbackColor(url),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EmptyHomeCard() {
    val textResources = localizedResources()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = textResources.getString(R.string.ui_your_homepage_is_empty),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = textResources.getString(R.string.ui_visit_a_site_choose_add_bookmark_then_select),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
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
