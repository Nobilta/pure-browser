package com.mybrowser.ui.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.DomDetails
import com.mybrowser.devtools.PageResult
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.BrowserIconAction

private data class DomEdit(val details: DomDetails, val kind: String, val name: String?, val value: String, val existing: Boolean)

@Composable
internal fun DomNodeDetails(session: DevToolsSession, nodeId: String, nodeName: String, onBack: () -> Unit, onCopy: () -> Unit, onChanged: () -> Unit) {
    val listState = rememberLazyListState()
    var details by remember(nodeId) { mutableStateOf<DomDetails?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var edit by remember { mutableStateOf<DomEdit?>(null) }
    var query by rememberSaveable(nodeId) { mutableStateOf("") }
    LaunchedEffect(nodeId, refresh, session.documentId) {
        loading = true
        try {
            details = (session.dom.details(nodeId) as? PageResult.Ok)?.value
        } finally {
            loading = false
        }
    }
    val invalidStyle = stringResource(R.string.dev_dom_invalid_style)
    edit?.let { draft ->
        DevToolsEditDialog(
            title = stringResource(R.string.dev_edit), initialName = draft.name, initialValue = draft.value,
            nameEditable = !draft.existing,
            hint = stringResource(if (draft.kind == "style") R.string.dev_dom_style_hint else R.string.dev_dom_temporary),
            onApply = { name, value ->
                val result = session.dom.edit(draft.details, draft.kind, name, value)
                if (result is PageResult.Failed && result.message == "invalid_css") PageResult.Failed(invalidStyle) else result
            },
            onRemove = if (draft.existing && draft.kind != "text") {
                { session.dom.edit(draft.details, draft.kind, draft.name.orEmpty(), null) }
            } else null,
            onApplied = { edit = null; refresh++; onChanged() }, onDismiss = { edit = null },
        )
    }
    val computed = remember(details, query) {
        details?.computed.orEmpty().filter { it.name.contains(query, true) || it.value.contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        BrowserSheetHeader(stringResource(R.string.dev_dom_details), onBack = onBack, actions = {
            BrowserIconAction(R.drawable.ic_ui_copy, stringResource(R.string.dev_dom_copy_html), onClick = onCopy)
            BrowserIconAction(R.drawable.ic_ui_refresh, stringResource(R.string.dev_dom_refresh_node), !loading) { refresh++ }
        })
        when {
            loading -> CircularProgressIndicator(Modifier.padding(16.dp))
            details == null -> Text(stringResource(R.string.dev_dom_stale), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> {
                val current = details!!
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        SelectableText(nodeName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.dev_dom_temporary), Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    current.text?.let { text ->
                        item {
                            BrowserActionPill(stringResource(R.string.dev_dom_edit_text), {
                                edit = DomEdit(current, "text", null, text, true)
                            })
                            SelectableText(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (current.kind == "element") {
                        item { DetailRow(stringResource(R.string.dev_dom_box), current.box) }
                        item {
                            DetailSectionHeader(stringResource(R.string.dev_dom_attributes), stringResource(R.string.dev_dom_add_attribute)) {
                                edit = DomEdit(current, "attribute", "", "", false)
                            }
                        }
                        items(current.attributes, key = { "attr-${it.name}" }) { attribute ->
                            EditableDetailRow(attribute.name, attribute.value) {
                                edit = DomEdit(current, "attribute", attribute.name, attribute.value, true)
                            }
                        }
                        if (current.supportsInlineStyle) {
                            item {
                                DetailSectionHeader(stringResource(R.string.dev_dom_inline_styles), stringResource(R.string.dev_dom_add_style)) {
                                    edit = DomEdit(current, "style", "", "", false)
                                }
                            }
                            items(current.styles, key = { "style-${it.name}" }) { style ->
                                EditableDetailRow(style.name, style.value) {
                                    edit = DomEdit(current, "style", style.name, style.value, true)
                                }
                            }
                        }
                        item {
                            Text(stringResource(R.string.dev_dom_computed), style = MaterialTheme.typography.titleSmall)
                            DevToolsSearchField(query, stringResource(R.string.dev_dom_computed_search)) { query = it }
                        }
                        if (computed.isEmpty() && query.isNotBlank()) item { EmptyState(stringResource(R.string.library_no_results)) }
                        items(computed, key = { "computed-${it.name}" }) { style -> DetailRow(style.name, style.value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableDetailRow(name: String, value: String, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dev_edit), onClick = onEdit),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { DetailRow(name, value) }
        Icon(painterResource(R.drawable.ic_ui_edit), null,
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailSectionHeader(title: String, action: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        BrowserIconAction(R.drawable.ic_ui_add, action, onClick = onAdd)
    }
}
