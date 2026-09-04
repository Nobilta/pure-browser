package com.mybrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mybrowser.filter.CustomFilterController
import com.mybrowser.filter.FilterController
import kotlinx.coroutines.launch

/**
 * Settings sheet for custom filter lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSettingsSheet(
    controller: CustomFilterController,
    filterController: FilterController,
    onDismiss: () -> Unit
) {
    val customLists by controller.customLists.collectAsState()
    // FilterController owns the live engine and therefore includes both the bundled
    // EasyList rules and every custom list.  CustomFilterController.ruleCount only counts
    // user-added lists, which made a healthy built-in engine look like "0 rules" here.
    val ruleCount by filterController.ruleCount.collectAsState()
    val lastError by controller.lastError.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Bound the list explicitly. Without a height cap a LazyColumn nested
                // below the header can claim the whole sheet and leave the add/remove
                // controls unreachable on small displays.
                .heightIn(max = 720.dp)
                .padding(16.dp)
        ) {
            Text(
                text = "广告过滤设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "已加载 $ruleCount 条规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            lastError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Built-in list
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                ListItem(
                    headlineContent = { Text("EasyList (内置)") },
                    supportingContent = { Text("默认广告过滤规则") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }

            // Custom lists header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "自定义规则列表",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(
                    onClick = { showAddDialog = true },
                    enabled = !adding,
                ) {
                    Text(if (adding) "处理中…" else "添加")
                }
            }

            // Custom lists
            if (customLists.isEmpty()) {
                Text(
                    text = "暂无自定义规则列表",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(customLists) { list ->
                        CustomListItem(
                            list = list,
                            onRemove = { controller.removeCustomList(list.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFilterListDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                adding = true
                scope.launch {
                    try {
                        val success = controller.addCustomList(name, url)
                        if (success) showAddDialog = false
                    } finally {
                        // Keep the sheet usable even if a provider throws outside the
                        // controller's normal Result path.
                        adding = false
                    }
                }
            }
        )
    }
}

@Composable
private fun CustomListItem(
    list: CustomFilterController.CustomList,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(list.name) },
            supportingContent = {
                Column {
                    Text(list.url, maxLines = 1)
                    Text("${list.ruleCount} 条规则", style = MaterialTheme.typography.labelSmall)
                }
            },
            trailingContent = {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除"
                    )
                }
            }
        )
    }
}

@Composable
private fun AddFilterListDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加过滤列表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/filters.txt") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
