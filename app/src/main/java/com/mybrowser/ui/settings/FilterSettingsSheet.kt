package com.mybrowser.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mybrowser.R
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.shell.userItemMotion
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

@Composable
fun FilterSettingsSheet(controller: FilterSubscriptions, filterController: FilterController, onDismiss: () -> Unit) {
    val res = localizedResources()
    val lists by controller.subscriptions.collectAsState()
    val rules by controller.userRules.collectAsState()
    val ruleCount by filterController.ruleCount.collectAsState()
    val cosmeticCount by filterController.cosmeticCount.collectAsState()
    val unsupportedCount by filterController.unsupportedCount.collectAsState()
    val refusedCount by filterController.refusedCount.collectAsState()
    val enabled by filterController.enabled.collectAsState()
    val busy by controller.busy.collectAsState()
    val autoUpdate by controller.autoUpdate.collectAsState()
    val error by controller.lastError.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FilterSubscriptions.Subscription?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dateFormat = remember(res.configuration.locales.toLanguageTags()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, res.configuration.locales[0])
    }
    BrowserBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            BrowserSheetHeader(res.getString(R.string.ui_ad_filter_settings), onBack = onDismiss) {
                Switch(enabled, onCheckedChange = filterController::setEnabled,
                    modifier = Modifier.semantics { contentDescription = res.getString(R.string.ui_ad_filter_settings) })
            }
            Text(res.getString(R.string.filter_loaded_counts, ruleCount, cosmeticCount),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            Text(res.getString(R.string.filter_reload_hint), Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(res.getString(R.string.filter_unsupported_count, unsupportedCount), Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Only when it happened: unlike unsupported syntax, a refused rule is a subscription
            // the user can trim, so the line is a prompt rather than a permanent statistic.
            if (refusedCount > 0) Text(res.getString(R.string.filter_refused_count, refusedCount),
                Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launch { controller.update() } }, enabled = !busy && lists.any { it.enabled }) {
                    Text(res.getString(if (busy) R.string.ui_working else R.string.filter_update_all))
                }
                TextButton(onClick = { adding = true }, enabled = !busy) { Text(res.getString(R.string.ui_add_filter_list)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item {
                    ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        headlineContent = { Text(res.getString(R.string.filter_auto_update)) },
                        supportingContent = { Text(res.getString(R.string.filter_auto_update_summary)) },
                        trailingContent = { Switch(autoUpdate, onCheckedChange = controller::setAutoUpdate,
                            modifier = Modifier.semantics { contentDescription = res.getString(R.string.filter_auto_update) }) },
                    )
                }
                // The rules the user wrote by hand, above the lists they came from: this is where
                // one of them is undone, and the rules are the part of this screen nobody else can
                // replace — a wrong one is what the two blocking gestures leave behind.
                item {
                    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                        Text(res.getString(R.string.filter_user_rules), style = MaterialTheme.typography.titleSmall)
                        Text(res.getString(R.string.filter_user_rules_summary),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (rules.isEmpty()) item {
                    Text(res.getString(R.string.filter_user_rules_empty),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                // The rows the user removes here are the ones they added by hand, so they are the
                // list this app can be most sure the user edits; the row fades and its neighbours
                // make room, like every other user-managed list.
                items(rules, key = { it }) { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = userItemMotion().fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(rule, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        // A refused write must say so: the row disappearing without the preference
                        // change landing looks identical to the rule never having existed, and the
                        // add path already reports every outcome.
                        IconButton(onClick = {
                            scope.launch {
                                if (!controller.removeUserRule(rule)) {
                                    Toast.makeText(context, res.getString(R.string.filter_user_rule_failed),
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(painterResource(R.drawable.ic_delete), res.getString(R.string.filter_user_rule_remove),
                                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
                items(lists, key = { it.id }) { list ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                                    Text(res.getString(if (list.builtIn) R.string.filter_built_in else R.string.ui_custom_filter_lists),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(list.enabled, modifier = Modifier.semantics { contentDescription = list.name }, onCheckedChange = { value ->
                                    scope.launch { controller.setEnabled(list.id, value) }
                                }, enabled = !busy)
                            }
                            Text(list.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(res.getString(R.string.filter_source_rule_count, list.ruleCount), style = MaterialTheme.typography.bodySmall)
                            Text(if (!list.builtIn && list.file == null) res.getString(R.string.filter_pending_download)
                                else if (list.updatedAt == 0L) res.getString(R.string.filter_packaged_snapshot)
                                else res.getString(R.string.filter_updated_at, dateFormat.format(Date(list.updatedAt))),
                                style = MaterialTheme.typography.bodySmall)
                            if (list.checkedAt != 0L) Text(res.getString(R.string.filter_checked_at, dateFormat.format(Date(list.checkedAt))),
                                style = MaterialTheme.typography.bodySmall)
                            list.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (!list.builtIn) TextButton(onClick = { deleting = list }, enabled = !busy) {
                                    Text(res.getString(R.string.cd_delete))
                                }
                                TextButton(onClick = { scope.launch { controller.update(list.id) } }, enabled = !busy) {
                                    Text(res.getString(R.string.filter_update))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) adding = false },
            title = { Text(res.getString(R.string.ui_add_filter_list)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(res.getString(R.string.search_engine_name)) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && name.isNotBlank() && url.isNotBlank(), onClick = {
                    scope.launch { if (controller.add(name, url)) adding = false }
                }) { Text(res.getString(if (busy) R.string.ui_working else R.string.ui_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text(res.getString(R.string.action_cancel)) } })
    }
    deleting?.let { list ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(res.getString(R.string.cd_delete)) }, text = { Text(list.name) },
            confirmButton = { TextButton(onClick = {
                scope.launch { controller.remove(list.id) }
                deleting = null
            }) { Text(res.getString(R.string.cd_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(res.getString(R.string.action_cancel)) } })
    }
}
