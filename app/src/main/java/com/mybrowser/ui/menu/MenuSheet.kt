package com.mybrowser.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.userscript.UserScriptStatus
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources

/**
 * Browser menu, organised by what the user is acting on.
 *
 * The sheet has two pages: the main page for the current page and the destinations used most,
 * and a second page for low-frequency tools. Keeping the second page inside the same sheet means
 * one dialog, one scrim and one Back chain; it also keeps the main page short enough to read.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MenuSheet(
    isIncognito: Boolean,
    isFilterEnabled: Boolean,
    blockedCount: Int,
    mediaCount: Int,
    hasCastSession: Boolean,
    hasVideo: Boolean,
    isDesktopMode: Boolean,
    isBrowserFullscreen: Boolean,
    isCurrentPageBookmarked: Boolean,
    canUsePageActions: Boolean,
    onOpenSiteSettings: () -> Unit,
    onPinWebsite: () -> Unit = {},
    onToggleIncognito: () -> Unit,
    onToggleFilter: (Boolean) -> Unit,
    onBlockElement: () -> Unit = {},
    onToggleDesktopMode: () -> Unit,
    onToggleBrowserFullscreen: (Boolean) -> Unit,
    onOpenFind: () -> Unit,
    onOpenTranslation: () -> Unit = {},
    onOpenMedia: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleBookmark: () -> Unit,
    onClearData: () -> Unit,
    onOpenDeveloperTools: () -> Unit = {},
    onManageUserScripts: () -> Unit = {},
    onOpenMoreTools: () -> Unit = {},
    /** What the userscript layer did for this page; null while no page is loaded. */
    userScriptStatus: UserScriptStatus? = null,
    onScanQr: () -> Unit = {},
    onSharePage: () -> Unit = {},
    siteTitle: String = "",
    siteHost: String = "",
    isSecure: Boolean = false,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textResources = localizedResources()
    val scrollState = rememberScrollState()

    BrowserBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("browser_menu")) {
            MenuSiteHeader(
                title = siteTitle.ifBlank { stringResource(R.string.menu_title) },
                host = siteHost,
                isSecure = isSecure,
                onDismiss = onDismiss,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scrollState)
                    .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                QuickActions(
                    isCurrentPageBookmarked = isCurrentPageBookmarked,
                    canUsePageActions = canUsePageActions,
                    onToggleBookmark = onToggleBookmark,
                    onOpenFind = onOpenFind,
                    onOpenTranslation = onOpenTranslation,
                    onSharePage = onSharePage,
                )

                if (canUsePageActions) {
                    MenuSectionLabel(stringResource(R.string.menu_section_page))
                    MenuCard {
                        MenuRow(
                            iconRes = R.drawable.ic_site_settings,
                            title = stringResource(R.string.site_settings),
                            subtitle = "",
                            onClick = onOpenSiteSettings,
                        )
                        MenuDivider()
                        MenuRow(
                            iconRes = R.drawable.ic_desktop,
                            title = stringResource(R.string.menu_desktop_site),
                            subtitle = "",
                            onClick = onToggleDesktopMode,
                            toggleState = isDesktopMode,
                            trailing = { Switch(checked = isDesktopMode, onCheckedChange = null) },
                        )
                        MenuDivider()
                        MenuRow(
                            iconRes = R.drawable.ic_fullscreen,
                            title = stringResource(R.string.browser_fullscreen),
                            subtitle = "",
                            onClick = { onToggleBrowserFullscreen(!isBrowserFullscreen) },
                            toggleState = isBrowserFullscreen,
                            trailing = { Switch(checked = isBrowserFullscreen, onCheckedChange = null) },
                        )
                        MenuDivider()
                        MenuRow(
                            iconRes = R.drawable.ic_cast,
                            title = stringResource(R.string.menu_cast),
                            subtitle = castSubtitle(hasCastSession, mediaCount, hasVideo),
                            enabled = mediaCount > 0 || hasCastSession,
                            onClick = onOpenMedia,
                        )
                    }
                }

                MenuSectionLabel(stringResource(R.string.menu_section_privacy))
                MenuCard {
                    MenuRow(
                        iconRes = R.drawable.ic_incognito,
                        title = stringResource(
                            if (isIncognito) R.string.menu_exit_incognito
                            else R.string.menu_enter_incognito,
                        ),
                        subtitle = "",
                        onClick = onToggleIncognito,
                    )
                    MenuDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_shield,
                        title = stringResource(R.string.menu_adblock),
                        subtitle = if (isFilterEnabled) {
                            stringResource(R.string.menu_adblock_blocked, blockedCount)
                        } else {
                            stringResource(R.string.menu_adblock_off)
                        },
                        onClick = { onToggleFilter(!isFilterEnabled) },
                        toggleState = isFilterEnabled,
                        trailing = { Switch(checked = isFilterEnabled, onCheckedChange = null) },
                    )
                    MenuDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_block,
                        title = stringResource(R.string.menu_block_element),
                        subtitle = "",
                        enabled = canUsePageActions,
                        onClick = onBlockElement,
                    )
                }

                MenuSectionLabel(stringResource(R.string.menu_section_go_to))
                MenuCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        QuickDestination(R.drawable.ic_bookmark, stringResource(R.string.menu_bookmarks), true, onOpenBookmarks, Modifier.weight(1f))
                        QuickDestination(R.drawable.ic_history, stringResource(R.string.menu_history), true, onOpenHistory, Modifier.weight(1f))
                        QuickDestination(R.drawable.ic_download, stringResource(R.string.menu_downloads), true, onOpenDownloads, Modifier.weight(1f))
                        QuickDestination(R.drawable.ic_settings, stringResource(R.string.menu_settings), true, onOpenSettings, Modifier.weight(1f))
                    }
                }

                MenuSectionLabel(stringResource(R.string.menu_section_more))
                MenuCard {
                    MenuRow(
                        iconRes = R.drawable.ic_more,
                        title = stringResource(R.string.menu_more_tools),
                        subtitle = stringResource(R.string.menu_more_tools_summary),
                        onClick = onOpenMoreTools,
                        trailing = {
                            Icon(painterResource(R.drawable.ic_forward), null, Modifier.size(18.dp))
                        },
                    )
                    MenuDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_close,
                        title = textResources.getString(R.string.ui_exit_browser),
                        subtitle = "",
                        onClick = onExit,
                        destructive = true,
                    )
                }
            }
        }
    }
}

@Composable
fun MoreToolsSheet(
    canUsePageActions: Boolean,
    isIncognito: Boolean,
    userScriptStatus: UserScriptStatus?,
    onPinWebsite: () -> Unit,
    onManageUserScripts: () -> Unit,
    onScanQr: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    onClearData: () -> Unit,
    onDismiss: () -> Unit,
) {
    BrowserBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("browser_menu")) {
            BrowserSheetHeader(
                title = stringResource(R.string.menu_more_tools),
                onBack = onDismiss,
                onDismiss = onDismiss,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuCard {
                    MenuRow(
                        iconRes = R.drawable.ic_home,
                        title = stringResource(R.string.pin_website),
                        subtitle = "",
                        enabled = canUsePageActions && !isIncognito,
                        onClick = onPinWebsite,
                    )
                    MenuDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_code,
                        title = stringResource(R.string.script_title),
                        subtitle = userScriptSubtitle(userScriptStatus),
                        onClick = onManageUserScripts,
                    )
                }
                MenuCard {
                    MenuRow(R.drawable.ic_qr_scan, stringResource(R.string.qr_scan), "", onScanQr)
                    MenuDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_code,
                        title = stringResource(R.string.menu_developer_tools),
                        subtitle = "",
                        onClick = onOpenDeveloperTools,
                    )
                }
                MenuCard {
                    MenuRow(
                        iconRes = R.drawable.ic_delete,
                        title = stringResource(R.string.menu_clear_data),
                        subtitle = "",
                        onClick = onClearData,
                        destructive = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSiteHeader(
    title: String,
    host: String,
    isSecure: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.trim().firstOrNull()?.uppercase() ?: "•",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (host.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSecure) {
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = host + " · " + stringResource(
                            if (isSecure) R.string.ui_secure_connection else R.string.ui_insecure_connection,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSecure) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        BrowserIconAction(R.drawable.ic_close, stringResource(R.string.ui_close), onClick = onDismiss)
    }
}

@Composable
private fun QuickActions(
    isCurrentPageBookmarked: Boolean,
    canUsePageActions: Boolean,
    onToggleBookmark: () -> Unit,
    onOpenFind: () -> Unit,
    onOpenTranslation: () -> Unit,
    onSharePage: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        QuickMenuAction(
            icon = if (isCurrentPageBookmarked) R.drawable.ic_bookmark_remove else R.drawable.ic_bookmark_add,
            label = stringResource(if (isCurrentPageBookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark),
            enabled = canUsePageActions,
            action = onToggleBookmark,
            modifier = Modifier.weight(1f),
        )
        QuickMenuAction(
            icon = R.drawable.ic_search,
            label = stringResource(R.string.menu_find),
            enabled = canUsePageActions,
            action = onOpenFind,
            modifier = Modifier.weight(1f),
        )
        QuickMenuAction(
            icon = R.drawable.ic_translate,
            label = stringResource(R.string.menu_translate),
            enabled = canUsePageActions,
            action = onOpenTranslation,
            modifier = Modifier.weight(1f),
        )
        QuickMenuAction(
            icon = R.drawable.ic_share,
            label = stringResource(R.string.menu_share_page),
            enabled = canUsePageActions,
            action = onSharePage,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun MenuRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    toggleState: Boolean? = null,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .then(
                if (toggleState != null) {
                    Modifier.toggleable(toggleState, enabled, Role.Switch) { onClick() }
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                },
            )
            .padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), null, Modifier.size(22.dp), tint = contentColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun QuickMenuAction(
    icon: Int,
    label: String,
    enabled: Boolean,
    action: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuickDestination(
    icon: Int,
    label: String,
    enabled: Boolean,
    action: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun userScriptSubtitle(status: UserScriptStatus?): String =
    status?.running?.takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.script_status_running, it.joinToString(", ")) }
        .orEmpty()

@Composable
private fun castSubtitle(hasCastSession: Boolean, mediaCount: Int, hasVideo: Boolean): String = when {
    hasCastSession -> stringResource(R.string.cast_manage_session)
    mediaCount > 0 -> stringResource(R.string.menu_cast_found, mediaCount)
    hasVideo -> stringResource(R.string.menu_cast_no_direct_source)
    else -> stringResource(R.string.menu_cast_none)
}
