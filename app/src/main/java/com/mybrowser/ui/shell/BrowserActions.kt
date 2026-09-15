package com.mybrowser.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.mybrowser.R

/** One title, spacing and action layout for every browser-owned sheet/page. */
@Composable
internal fun BrowserSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .padding(start = if (onBack == null) 24.dp else 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cd_back), onClick = it) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        actions()
        onDismiss?.let { BrowserIconAction(R.drawable.ic_close, stringResource(R.string.ui_close), onClick = it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserIconAction(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(icon), label, Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun BrowserActionRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun LibrarySearchField(query: String, label: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(value = query, onValueChange = { onQueryChange(it.take(256)) }, singleLine = true,
        placeholder = { Text(label) }, label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = {
            if (query.isNotEmpty()) BrowserIconAction(R.drawable.ic_close, stringResource(R.string.cd_clear)) {
                onQueryChange("")
            }
        }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
}

@Composable
internal fun LibraryFooter(loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, error: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else if (hasMore || error) TextButton(onClick = onLoadMore) {
            Text(stringResource(if (error) R.string.library_retry else R.string.library_load_more))
        }
    }
}

@Composable
internal fun LibraryItemMenu(onOpenNewTab: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BrowserIconAction(R.drawable.ic_more, stringResource(R.string.library_item_actions)) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onEdit != null) DropdownMenuItem(text = { Text(stringResource(R.string.library_edit_bookmark)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null) },
                onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.context_open_new_tab)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) },
                onClick = { expanded = false; onOpenNewTab() })
            DropdownMenuItem(text = { Text(stringResource(R.string.context_copy_link)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_copy), null) },
                onClick = { expanded = false; onCopy() })
            DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                onClick = { expanded = false; onDelete() })
        }
    }
}
