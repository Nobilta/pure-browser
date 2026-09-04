package com.mybrowser.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Material 3 navigation homepage rendered above a blank WebView document. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeDashboard(
    shortcuts: List<HomeShortcut>,
    onOpen: (HomeShortcut) -> Unit,
    onRemove: (HomeShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<HomeShortcut?>(null) }

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
                        text = "常用网站",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (shortcuts.isEmpty()) {
                            "添加书签时可同时放到首页"
                        } else {
                            "轻触打开，长按可从首页移除"
                        },
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
                        onLongClick = { pendingRemoval = shortcut },
                    )
                }
            }
        }
    }

    pendingRemoval?.let { shortcut ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            title = { Text("从首页移除？") },
            text = { Text("将“${shortcut.title}”从导航首页移除，书签仍会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onRemove(shortcut)
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShortcutTile(
    shortcut: HomeShortcut,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "从首页移除",
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(60.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
        ) {
            val icon = shortcut.icon
            if (icon != null && !icon.isRecycled) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = "${shortcut.title} 图标",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = shortcut.title.firstOrNull()?.uppercase() ?: "·",
                        style = MaterialTheme.typography.headlineMedium,
                        color = fallbackColor(shortcut.url),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
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
private fun EmptyHomeCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
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
                text = "首页还是空的",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "浏览网站后选择“添加书签”，勾选“同时添加到首页”即可创建快捷入口。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun fallbackColor(key: String): Color {
    val colors = listOf(
        Color(0xFF006A60),
        Color(0xFF315DA8),
        Color(0xFF7A4EAB),
        Color(0xFF9B4D5C),
        Color(0xFF765A00),
    )
    return colors[(key.hashCode() and Int.MAX_VALUE) % colors.size]
}
