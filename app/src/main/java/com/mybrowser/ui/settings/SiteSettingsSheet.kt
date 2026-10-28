package com.mybrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.VideoFit
import com.mybrowser.site.*
import kotlin.math.roundToInt
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.LibrarySearchField

private val SiteSettingsSaver = listSaver<SiteSettings, String>(
    save = { value -> listOf(
        value.filtering.toString(), value.javaScript.toString(), value.images.toString(),
        value.thirdPartyCookies.toString(), value.desktop.toString(), value.textZoom.toString(),
        value.camera.name, value.microphone.name, value.location.name, value.protectedMedia.name,
        value.externalApps.name, value.webDarkening.toString(), value.desktopWidth.toString(),
        value.enhancedPlayback?.toString().orEmpty(), value.videoMirror?.toString().orEmpty(),
        value.videoFit?.name.orEmpty(), value.userAgent.orEmpty(),
    ) },
    restore = { values -> SiteSettings(
        filtering = values[0].toBoolean(), javaScript = values[1].toBoolean(), images = values[2].toBoolean(),
        thirdPartyCookies = values[3].toBoolean(), desktop = values[4].toBoolean(), textZoom = values[5].toInt(),
        camera = SitePermission.valueOf(values[6]), microphone = SitePermission.valueOf(values[7]),
        location = SitePermission.valueOf(values[8]), protectedMedia = SitePermission.valueOf(values[9]),
        externalApps = SitePermission.valueOf(values[10]), webDarkening = values[11].toBoolean(),
        desktopWidth = values[12].toInt(),
        enhancedPlayback = values[13].ifEmpty { null }?.toBoolean(),
        videoMirror = values[14].ifEmpty { null }?.toBoolean(),
        videoFit = values[15].ifEmpty { null }?.let(VideoFit::valueOf),
        userAgent = values.getOrNull(16)?.ifEmpty { null },
    ) },
)

@Composable
fun SiteSettingsSheet(origin: String, settings: SiteSettings, privateSession: Boolean, busy: Boolean,
    onSave: (SiteSettings) -> Unit, onReset: () -> Unit, onConnectionInfo: (() -> Unit)?, onDismiss: () -> Unit,
    onClearSiteData: (() -> Unit)? = null, temporaryFilteringOff: Boolean = false,
    onTemporaryFilteringChange: (() -> Unit)? = null, defaultEnhancedPlayback: Boolean = true,
    needsRepair: Boolean = false, onRepair: () -> Unit = {}) {
    // Keep unsaved edits while another surface (for example a remembered permission decision)
    // updates this origin's stored settings; only switching origin starts a new draft. The draft is
    // saveable so rotation/Activity recreation does not discard edits.
    // The baseline must survive the same recreation as the draft; otherwise a field that changed
    // externally while the sheet was open looks like a user edit after restoration.
    val initial = rememberSaveable(origin, saver = SiteSettingsSaver) { settings }
    var draft by rememberSaveable(origin, stateSaver = SiteSettingsSaver) { mutableStateOf(settings) }
    var confirmReset by remember { mutableStateOf(false) }
    var editingUserAgent by remember { mutableStateOf(false) }
    var userAgentDraft by remember { mutableStateOf("") }
    BrowserBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 20.dp)) {
            BrowserSheetHeader(stringResource(R.string.site_settings))
            // Keep edge flings in the form. Sheet dragging and stretch overscroll
            // otherwise compete with its changing height/insets at the boundaries.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState(), overscrollEffect = null)) {
                // Long origins and large fonts must not consume the fixed-height viewport.
                Text(origin, style = MaterialTheme.typography.bodyMedium)
                if (needsRepair) SiteStorageRepairNotice(busy, onRepair)
                Text(stringResource(if (privateSession) R.string.site_private_scope else R.string.site_scope),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
                if (onConnectionInfo != null) TextButton(onClick = onConnectionInfo) {
                    Text(stringResource(R.string.site_connection_info))
                }
                SiteToggle(stringResource(R.string.site_filtering), draft.filtering, !busy) { draft = draft.copy(filtering = it) }
                onTemporaryFilteringChange?.let { change -> TextButton(onClick = change, enabled = !busy) {
                    Text(stringResource(if (temporaryFilteringOff) R.string.filter_resume_site else R.string.filter_pause_site))
                } }
                SiteToggle(stringResource(R.string.site_javascript), draft.javaScript, !busy) { draft = draft.copy(javaScript = it) }
                SiteToggle(stringResource(R.string.site_images), draft.images, !busy) { draft = draft.copy(images = it) }
                SiteToggle(stringResource(R.string.site_third_party_cookies), draft.thirdPartyCookies, !busy) { draft = draft.copy(thirdPartyCookies = it) }
                SiteToggle(stringResource(R.string.menu_desktop_site), draft.desktop, !busy) { draft = draft.copy(desktop = it) }
                SiteDropdownRow(
                    label = stringResource(R.string.site_user_agent),
                    valueLabel = stringResource(
                        if (draft.userAgent == null) R.string.site_user_agent_default else R.string.site_user_agent_custom,
                    ),
                    enabled = !busy,
                    options = listOf(false, true),
                    optionLabel = { custom ->
                        stringResource(if (custom) R.string.site_user_agent_custom else R.string.site_user_agent_default)
                    },
                    onSelect = { custom ->
                        if (custom) {
                            userAgentDraft = draft.userAgent.orEmpty()
                            editingUserAgent = true
                        } else {
                            draft = draft.copy(userAgent = null)
                        }
                    },
                )
                draft.userAgent?.let { ua ->
                    Text(ua, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.site_user_agent_priority), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SiteToggle(stringResource(R.string.site_web_darkening), draft.webDarkening, !busy) { draft = draft.copy(webDarkening = it) }
                SiteToggle(stringResource(R.string.site_enhanced_playback), draft.useEnhancedPlayback(defaultEnhancedPlayback), !busy) {
                    draft = draft.copy(enhancedPlayback = it)
                }
                Text(stringResource(R.string.site_enhanced_playback_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The player's picture shape belongs to the site for the same reason enhanced
                // playback does: a source that needs a ratio or a mirror needs it every time.
                SiteToggle(stringResource(R.string.player_mirror), draft.useVideoMirror(), !busy) {
                    draft = draft.copy(videoMirror = it)
                }
                SiteDropdownRow(
                    label = stringResource(R.string.player_video_menu),
                    valueLabel = stringResource(videoFitLabel(draft.useVideoFit())),
                    enabled = !busy,
                    options = VIDEO_FIT_CHOICES,
                    optionLabel = { (label, _) -> stringResource(label) },
                    onSelect = { (_, fit) -> draft = draft.copy(videoFit = fit) },
                )
                SiteDropdownRow(
                    label = stringResource(R.string.site_desktop_viewport),
                    valueLabel = if (draft.desktopWidth == 0) stringResource(R.string.site_viewport_original) else draft.desktopWidth.toString(),
                    enabled = !busy && draft.desktop,
                    options = SiteSettingsRepository.DESKTOP_WIDTHS,
                    optionLabel = { width -> if (width == 0) stringResource(R.string.site_viewport_original) else width.toString() },
                    onSelect = { width -> draft = draft.copy(desktopWidth = width) },
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val textZoomLabel = stringResource(R.string.site_text_zoom, draft.textZoom)
                Text(textZoomLabel, style = MaterialTheme.typography.titleSmall)
                Slider(value = draft.textZoom.toFloat(), onValueChange = { draft = draft.copy(textZoom = it.roundToInt()) },
                    valueRange = 50f..200f, steps = 29, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = textZoomLabel })
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.site_permissions), style = MaterialTheme.typography.titleSmall)
                SiteDropdownRow(
                    label = stringResource(R.string.external_apps),
                    valueLabel = stringResource(draft.externalApps.label()),
                    enabled = !busy,
                    options = SitePermission.entries,
                    optionLabel = { stringResource(it.label()) },
                    onSelect = { draft = draft.copy(externalApps = it) },
                )
                SiteCapability.entries.forEach { capability ->
                    val capabilityLabel = stringResource(capability.label())
                    val currentLabel = stringResource(draft.permission(capability).label())
                    SiteDropdownRow(
                        label = capabilityLabel,
                        valueLabel = currentLabel,
                        enabled = !busy,
                        options = SitePermission.entries,
                        optionLabel = { stringResource(it.label()) },
                        onSelect = { permission -> draft = draft.withPermission(capability, permission) },
                        buttonContentDescription = "$capabilityLabel: $currentLabel",
                    )
                }
                Text(stringResource(R.string.site_os_permission_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { confirmReset = true }, enabled = !busy && !needsRepair) { Text(stringResource(R.string.site_reset)) }
                if (onClearSiteData != null) TextButton(onClick = onClearSiteData, enabled = !busy) { Text(stringResource(R.string.clear_this_site)) }
            }
            if (editingUserAgent) {
                val invalidUserAgent = userAgentDraft.any(Char::isISOControl)
                AlertDialog(
                onDismissRequest = { editingUserAgent = false },
                title = { Text(stringResource(R.string.site_user_agent_dialog)) },
                text = {
                    OutlinedTextField(
                        value = userAgentDraft,
                        onValueChange = { userAgentDraft = it.take(SiteSettingsRepository.MAX_USER_AGENT) },
                        singleLine = true,
                        isError = invalidUserAgent,
                        supportingText = if (invalidUserAgent) {
                            { Text(stringResource(R.string.site_user_agent_invalid)) }
                        } else null,
                        placeholder = { Text(stringResource(R.string.site_user_agent_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !invalidUserAgent,
                        onClick = {
                            draft = draft.copy(
                                userAgent = SiteSettingsRepository.normalizeUserAgent(userAgentDraft),
                            )
                            editingUserAgent = false
                        },
                    ) { Text(stringResource(R.string.action_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingUserAgent = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).fillMaxHeight()) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { onSave(mergeSiteDraft(initial, draft, settings)) },
                    enabled = !busy && !needsRepair, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(stringResource(R.string.site_save_reload))
                }
            }
        }
    }
    if (confirmReset) com.mybrowser.ui.shell.BrowserConfirmDialog(
        title = stringResource(R.string.site_reset),
        message = stringResource(R.string.site_reset_confirm, origin),
        confirmText = stringResource(R.string.site_reset),
        onConfirm = { confirmReset = false; onReset() },
        onDismiss = { confirmReset = false },
    )
}

@Composable
fun ManagedSitesSheet(sites: Map<String, SiteSettings>, onSelect: (String) -> Unit, onDismiss: () -> Unit,
    needsRepair: Boolean = false, busy: Boolean = false, onRepair: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    BrowserBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            BrowserSheetHeader(stringResource(R.string.site_settings), onBack = onDismiss)
            if (needsRepair) SiteStorageRepairNotice(busy, onRepair)
            LibrarySearchField(query, stringResource(R.string.site_search)) { query = it }
            val origins = sites.keys.filter { it.contains(query, true) }.sorted()
            if (origins.isEmpty()) Text(stringResource(R.string.site_none), modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.weight(1f), overscrollEffect = null) {
                items(origins, key = { it }) { origin ->
                    ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        headlineContent = { Text(origin) }, modifier = Modifier.clickable { onSelect(origin) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SiteStorageRepairNotice(busy: Boolean, onRepair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text(stringResource(R.string.site_storage_unreadable), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    TextButton(onClick = { confirm = true }, enabled = !busy) { Text(stringResource(R.string.site_repair)) }
    if (confirm) com.mybrowser.ui.shell.BrowserConfirmDialog(
        title = stringResource(R.string.site_repair),
        message = stringResource(R.string.site_repair_confirm),
        confirmText = stringResource(R.string.site_repair),
        onConfirm = { confirm = false; onRepair() },
        onDismiss = { confirm = false },
        enabled = !busy,
    )
}

@Composable
fun WebsitePermissionDialog(prompt: WebsitePermissions.Prompt, onRespond: (Boolean, Boolean) -> Unit) {
    var remember by remember(prompt) { mutableStateOf(false) }
    com.mybrowser.ui.shell.BrowserAlertDialog(onDismissRequest = { onRespond(false, false) },
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
    // No horizontal inset here: the sheet's own column already indents this form, and the
    // settings-page row padding would stack on top of it.
    SettingsToggle(title = label, checked = checked, enabled = enabled, onChange = onChange,
        modifier = Modifier.heightIn(min = 56.dp))
}

@Composable
private fun <T> SiteDropdownRow(
    label: String,
    valueLabel: String,
    enabled: Boolean,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    buttonContentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = if (buttonContentDescription != null) {
                    Modifier.semantics { contentDescription = buttonContentDescription }
                } else {
                    Modifier
                },
            ) {
                Text(valueLabel)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
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

/** The picture presets the player offers, in the order this row lists them. */
private val VIDEO_FIT_CHOICES = listOf(
    R.string.player_fit_natural to VideoFit.NATURAL,
    R.string.player_fit_ratio_3_4 to VideoFit.RATIO_3_4,
    R.string.player_fit_ratio_16_9 to VideoFit.RATIO_16_9,
    R.string.player_fit_fill to VideoFit.FILL,
)

private fun videoFitLabel(fit: VideoFit): Int =
    VIDEO_FIT_CHOICES.firstOrNull { (_, option) -> option == fit }?.first ?: R.string.player_fit_natural


/**
 * Applies only fields the user changed in this sheet to the settings currently on disk. A permission
 * prompt can update this origin while the sheet is open; saving the whole stale `draft` would put the
 * old permission back.
 */
internal fun mergeSiteDraft(initial: SiteSettings, draft: SiteSettings, current: SiteSettings): SiteSettings {
    fun <T> changed(before: T, after: T, existing: T): T = if (after != before) after else existing
    return current.copy(
        filtering = changed(initial.filtering, draft.filtering, current.filtering),
        javaScript = changed(initial.javaScript, draft.javaScript, current.javaScript),
        images = changed(initial.images, draft.images, current.images),
        thirdPartyCookies = changed(initial.thirdPartyCookies, draft.thirdPartyCookies, current.thirdPartyCookies),
        desktop = changed(initial.desktop, draft.desktop, current.desktop),
        textZoom = changed(initial.textZoom, draft.textZoom, current.textZoom),
        camera = changed(initial.camera, draft.camera, current.camera),
        microphone = changed(initial.microphone, draft.microphone, current.microphone),
        location = changed(initial.location, draft.location, current.location),
        protectedMedia = changed(initial.protectedMedia, draft.protectedMedia, current.protectedMedia),
        externalApps = changed(initial.externalApps, draft.externalApps, current.externalApps),
        webDarkening = changed(initial.webDarkening, draft.webDarkening, current.webDarkening),
        desktopWidth = changed(initial.desktopWidth, draft.desktopWidth, current.desktopWidth),
        userAgent = changed(initial.userAgent, draft.userAgent, current.userAgent),
        enhancedPlayback = changed(initial.enhancedPlayback, draft.enhancedPlayback, current.enhancedPlayback),
        videoMirror = changed(initial.videoMirror, draft.videoMirror, current.videoMirror),
        videoFit = changed(initial.videoFit, draft.videoFit, current.videoFit),
    )
}
