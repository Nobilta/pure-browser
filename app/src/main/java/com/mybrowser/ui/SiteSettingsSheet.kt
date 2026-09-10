package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.site.*
import kotlin.math.roundToInt

@Composable
fun SiteSettingsSheet(origin: String, settings: SiteSettings, privateSession: Boolean, busy: Boolean,
    onSave: (SiteSettings) -> Unit, onReset: () -> Unit, onConnectionInfo: (() -> Unit)?, onDismiss: () -> Unit) {
    var draft by remember(origin, settings) { mutableStateOf(settings) }
    var confirmReset by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.site_settings), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // Long origins and large fonts must not consume the fixed-height viewport.
                Text(origin, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (privateSession) R.string.site_private_scope else R.string.site_scope),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
                if (onConnectionInfo != null) TextButton(onClick = onConnectionInfo) {
                    Text(stringResource(R.string.site_connection_info))
                }
                SiteToggle(stringResource(R.string.site_filtering), draft.filtering, !busy) { draft = draft.copy(filtering = it) }
                SiteToggle(stringResource(R.string.site_javascript), draft.javaScript, !busy) { draft = draft.copy(javaScript = it) }
                SiteToggle(stringResource(R.string.site_images), draft.images, !busy) { draft = draft.copy(images = it) }
                SiteToggle(stringResource(R.string.site_third_party_cookies), draft.thirdPartyCookies, !busy) { draft = draft.copy(thirdPartyCookies = it) }
                SiteToggle(stringResource(R.string.menu_desktop_site), draft.desktop, !busy) { draft = draft.copy(desktop = it) }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val textZoomLabel = stringResource(R.string.site_text_zoom, draft.textZoom)
                Text(textZoomLabel, style = MaterialTheme.typography.titleSmall)
                Slider(value = draft.textZoom.toFloat(), onValueChange = { draft = draft.copy(textZoom = it.roundToInt()) },
                    valueRange = 50f..200f, steps = 29, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = textZoomLabel })
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.site_permissions), style = MaterialTheme.typography.titleSmall)
                SiteCapability.entries.forEach { capability ->
                    var expanded by remember(capability) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(capability.label()), Modifier.weight(1f))
                        Box {
                            val permissionLabel = stringResource(capability.label()) + ": " + stringResource(draft.permission(capability).label())
                            TextButton(onClick = { expanded = true }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = permissionLabel }) { Text(stringResource(draft.permission(capability).label())) }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                SitePermission.entries.forEach { permission ->
                                    DropdownMenuItem(text = { Text(stringResource(permission.label())) }, onClick = {
                                        draft = draft.withPermission(capability, permission); expanded = false
                                    })
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.site_os_permission_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmReset = true }, enabled = !busy) { Text(stringResource(R.string.site_reset)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { onSave(draft) }, enabled = !busy, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(stringResource(R.string.site_save_reload))
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.site_reset)) }, text = { Text(stringResource(R.string.site_reset_confirm, origin)) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text(stringResource(R.string.site_reset)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun ManagedSitesSheet(sites: Map<String, SiteSettings>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.site_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            LibrarySearchField(query, stringResource(R.string.site_search)) { query = it }
            val origins = sites.keys.filter { it.contains(query, true) }.sorted()
            if (origins.isEmpty()) Text(stringResource(R.string.site_none), modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(origins, key = { it }) { origin ->
                    ListItem(headlineContent = { Text(origin) }, modifier = Modifier.clickable { onSelect(origin) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun WebsitePermissionDialog(prompt: WebsitePermissions.Prompt, onRespond: (Boolean, Boolean) -> Unit) {
    var remember by remember(prompt) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { onRespond(false, false) },
        title = { Text(stringResource(R.string.site_permission_request)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(prompt.origin, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.site_permission_request_summary), Modifier.padding(top = 12.dp))
                prompt.capabilities.forEach { Text("• " + stringResource(it.label()), Modifier.padding(vertical = 4.dp)) }
                Row(Modifier.fillMaxWidth().toggleable(remember, role = Role.Checkbox) { remember = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Text(stringResource(if (prompt.privateSession) R.string.site_remember_private else R.string.site_remember),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRespond(true, remember) }) { Text(stringResource(R.string.site_allow)) } },
        dismissButton = { TextButton(onClick = { onRespond(false, remember) }) { Text(stringResource(R.string.site_block)) } })
}

@Composable
private fun SiteToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled, Role.Switch, onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun SiteCapability.label() = when (this) {
    SiteCapability.CAMERA -> R.string.site_camera
    SiteCapability.MICROPHONE -> R.string.site_microphone
    SiteCapability.LOCATION -> R.string.site_location
    SiteCapability.PROTECTED_MEDIA -> R.string.site_protected_media
}
private fun SitePermission.label() = when (this) {
    SitePermission.ASK -> R.string.site_ask
    SitePermission.ALLOW -> R.string.site_allow
    SitePermission.BLOCK -> R.string.site_block
}
