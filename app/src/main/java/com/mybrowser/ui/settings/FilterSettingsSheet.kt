package com.mybrowser.ui.settings

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mybrowser.ui.components.BrowserTextField
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.components.BrowserGroupCard
import com.mybrowser.ui.components.BrowserGroupDivider
import com.mybrowser.R
import com.mybrowser.ui.shell.LocalSnackbarHostState
import com.mybrowser.filter.FilterController
import com.mybrowser.filter.FilterSubscriptions
import com.mybrowser.filter.UserRuleGroupKind
import com.mybrowser.filter.UserRuleGrouping
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.browserSheetDismiss
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.shell.userItemMotion

@Composable
fun FilterSettingsSheet(controller: FilterSubscriptions, filterController: FilterController, onDismiss: () -> Unit) {
    val res = localizedResources()
    val lists by controller.subscriptions.collectAsState()
    val rules by controller.userRules.collectAsState()
    val groupedRules = remember(rules) { UserRuleGrouping.group(rules) }
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
    val snackbarHostState = LocalSnackbarHostState.current
    val dateFormat = remember(res.configuration.locales.toLanguageTags()) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, res.configuration.locales[0])
    }
    BrowserBottomSheet(onDismissRequest = onDismiss, dragHandleOnly = true) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            BrowserSheetHeader(res.getString(R.string.ui_ad_filter_settings), onBack = browserSheetDismiss()) {
                Switch(enabled, onCheckedChange = filterController::setEnabled,
                    modifier = Modifier.semantics { contentDescription = res.getString(R.string.ui_ad_filter_settings) })
            }
            Text(res.getString(R.string.filter_loaded_counts, ruleCount, cosmeticCount),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            Text(res.getString(R.string.filter_reload_hint), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(res.getString(R.string.filter_unsupported_count, unsupportedCount), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Only when it happened: unlike unsupported syntax, a refused rule is a subscription
            // the user can trim, so the line is a prompt rather than a permanent statistic.
            if (refusedCount > 0) Text(res.getString(R.string.filter_refused_count, refusedCount),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrowserActionPill(res.getString(if (busy) R.string.ui_working else R.string.filter_update_all),
                    { scope.launch { controller.update() } }, Modifier.weight(1f).fillMaxHeight(), enabled = !busy && lists.any { it.enabled })
                BrowserActionPill(res.getString(R.string.ui_add_filter_list), { adding = true }, Modifier.weight(1f).fillMaxHeight(), enabled = !busy)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item {
                    BrowserGroupCard {
                        SettingsToggle(
                            title = res.getString(R.string.filter_auto_update),
                            summary = res.getString(R.string.filter_auto_update_summary),
                            checked = autoUpdate,
                            onChange = controller::setAutoUpdate,
                        )
                    }
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
                if (groupedRules.isEmpty()) item {
                    Text(res.getString(R.string.filter_user_rules_empty),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                // The rows the user removes here are the ones they added by hand, so they are the
                // list this app can be most sure the user edits; the row fades and its neighbours
                // make room, like every other user-managed list. Rules are grouped by the site they
                // affect, with the generic rules first, so a long hand-written list stays legible.
                groupedRules.forEach { group ->
                    item(key = "rule-group:${group.key}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (group.kind) {
                                    UserRuleGroupKind.ALL_SITES -> stringResource(R.string.filter_rule_group_all_sites)
                                    UserRuleGroupKind.UNKNOWN -> stringResource(R.string.filter_rule_group_unknown)
                                    UserRuleGroupKind.SITE -> group.site.orEmpty()
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.filter_rule_group_count, group.rules.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    items(group.rules, key = { it }) { rule ->
                        UserRuleRow(rule, userItemMotion()) {
                            scope.launch {
                                if (!controller.removeUserRule(rule)) {
                                    val message = res.getString(R.string.filter_user_rule_failed)
                                    snackbarHostState?.showSnackbar(message)
                                }
                            }
                        }
                    }
                }
                item { BrowserGroupDivider(inset = 0.dp) }
                items(lists, key = { it.id }) { list ->
                    BrowserGroupCard(modifier = Modifier.padding(vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                                    value = list.enabled,
                                    enabled = !busy,
                                    role = Role.Switch,
                                    onValueChange = { value -> scope.launch { controller.setEnabled(list.id, value) } },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).alpha(if (busy) 0.38f else 1f)) {
                                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                                    Text(res.getString(if (list.builtIn) R.string.filter_built_in else R.string.ui_custom_filter_lists),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    list.enabled,
                                    modifier = Modifier.clearAndSetSemantics {},
                                    onCheckedChange = null,
                                    enabled = !busy,
                                )
                            }
                            FilterSubscriptions.BUILT_INS.firstOrNull { it.id == list.id }
                                ?.summaryRes?.takeIf { it != 0 }?.let { summary ->
                                    Text(res.getString(summary), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (!list.builtIn) BrowserActionPill(
                                    text = res.getString(R.string.cd_delete),
                                    onClick = { deleting = list },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    enabled = !busy,
                                )
                                BrowserActionPill(
                                    text = res.getString(R.string.filter_update),
                                    onClick = { scope.launch { controller.update(list.id) } },
                                    enabled = !busy,
                                    modifier = (if (list.builtIn) Modifier else Modifier.weight(1f)).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var attempted by rememberSaveable { mutableStateOf(false) }
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        com.mybrowser.ui.shell.BrowserAlertDialog(onDismissRequest = { if (!busy) adding = false },
            title = { Text(res.getString(R.string.ui_add_filter_list)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BrowserTextField(name, res.getString(R.string.search_engine_name), { name = it },
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    BrowserTextField(url, res.getString(R.string.bookmark_edit_url), { url = it },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (attempted) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && name.isNotBlank() && url.isNotBlank(), onClick = {
                    scope.launch { attempted = true; if (controller.add(name, url)) adding = false }
                }) { Text(res.getString(if (busy) R.string.ui_working else R.string.ui_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text(res.getString(R.string.action_cancel)) } })
    }
    deleting?.let { list ->
        com.mybrowser.ui.shell.BrowserConfirmDialog(
            title = res.getString(R.string.cd_delete),
            message = list.name,
            confirmText = res.getString(R.string.cd_delete),
            dismissText = res.getString(R.string.action_cancel),
            onConfirm = {
                scope.launch { controller.remove(list.id) }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun UserRuleRow(rule: String, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            rule,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A refused write must say so: the row disappearing without the preference change
        // landing looks identical to the rule never having existed.
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(R.drawable.ic_ui_delete),
                stringResource(R.string.filter_user_rule_remove),
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
