package com.mybrowser.ui.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.security.SecurityLevel
import com.mybrowser.userscript.UserScriptStatus
import com.mybrowser.ui.components.BrowserGroupCard
import com.mybrowser.ui.components.BrowserGroupDivider
import com.mybrowser.ui.components.BrowserListItem
import com.mybrowser.ui.components.BrowserTileCard
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.theme.BrowserColors

/**
 * Browser menu: four purpose-built zones instead of a flat grid.
 *
 * 1. the page's ID card, 2. the four libraries, 3. a 2x4 matrix in which switches and panel
 * launchers are visibly different, 4. the exit / more / settings bar at the thumb edge. The exit
 * is deliberately not the bottom-right-most target: double-tapping the menu button must land on
 * Settings, not on quitting the browser.
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
    onOpenMoreTools: () -> Unit = {},
    runningScriptCount: Int = 0,
    onSharePage: () -> Unit = {},
    siteTitle: String = "",
    siteHost: String = "",
    securityLevel: SecurityLevel = SecurityLevel.SECURE,
    isReaderActive: Boolean = false,
    isTranslateActive: Boolean = false,
    onToggleReader: () -> Unit = {},
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()

    BrowserBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("browser_menu")) {
            SiteSummaryCard(
                title = if (canUsePageActions) {
                    siteTitle.ifBlank { stringResource(R.string.menu_title) }
                } else {
                    stringResource(R.string.ui_pure_browser)
                },
                host = if (canUsePageActions) siteHost else stringResource(R.string.menu_home_ready),
                securityLevel = securityLevel,
                isCurrentPageBookmarked = isCurrentPageBookmarked,
                canUsePageActions = canUsePageActions,
                onOpenSiteSettings = onOpenSiteSettings,
                onToggleBookmark = onToggleBookmark,
                onSharePage = onSharePage,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                LibraryCapsules(
                    onOpenBookmarks = onOpenBookmarks,
                    onOpenHistory = onOpenHistory,
                    onOpenDownloads = onOpenDownloads,
                    onOpenFind = onOpenFind,
                )
                Spacer(Modifier.height(14.dp))
                ModeGrid(
                    isIncognito = isIncognito,
                    isDesktopMode = isDesktopMode,
                    isReaderActive = isReaderActive,
                    isTranslateActive = isTranslateActive,
                    isBrowserFullscreen = isBrowserFullscreen,
                    isFilterEnabled = isFilterEnabled,
                    blockedCount = blockedCount,
                    mediaCount = mediaCount,
                    hasCastSession = hasCastSession,
                    hasVideo = hasVideo,
                    canUsePageActions = canUsePageActions,
                    onToggleIncognito = onToggleIncognito,
                    onToggleDesktopMode = onToggleDesktopMode,
                    onToggleReader = onToggleReader,
                    onOpenTranslation = onOpenTranslation,
                    onToggleBrowserFullscreen = onToggleBrowserFullscreen,
                    onToggleFilter = onToggleFilter,
                    onBlockElement = onBlockElement,
                    onOpenMedia = onOpenMedia,
                )
                Spacer(Modifier.height(18.dp))
                FooterRow(
                    onExit = onExit,
                    onOpenMoreTools = onOpenMoreTools,
                    onOpenSettings = onOpenSettings,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SiteSummaryCard(
    title: String,
    host: String,
    securityLevel: SecurityLevel,
    isCurrentPageBookmarked: Boolean,
    canUsePageActions: Boolean,
    onOpenSiteSettings: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSharePage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = canUsePageActions, role = Role.Button, onClick = onOpenSiteSettings)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val trimmedTitle = title.trim()
                        Text(
                            text = if (trimmedTitle.isEmpty()) {
                                "•"
                            } else {
                                trimmedTitle.substring(0, trimmedTitle.offsetByCodePoints(0, 1)).uppercase()
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (canUsePageActions) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canUsePageActions) {
                            // The site card and the omnibar read the same SecurityLevel, so a
                            // certificate failure cannot look like a plain-HTTP warning here.
                            val (icon, tint) = when (securityLevel) {
                                SecurityLevel.SECURE -> R.drawable.ic_ui_lock to BrowserColors.secure
                                SecurityLevel.INSECURE, SecurityLevel.WARNING ->
                                    R.drawable.ic_ui_warning to BrowserColors.warning
                                SecurityLevel.DANGEROUS ->
                                    R.drawable.ic_ui_close to colors.error
                            }
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = tint,
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            text = host.ifBlank { stringResource(R.string.menu_title) },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            QuickIcon(
                icon = if (isCurrentPageBookmarked) R.drawable.ic_ui_bookmark_filled else R.drawable.ic_ui_bookmark_add,
                description = stringResource(if (isCurrentPageBookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark),
                enabled = canUsePageActions,
                active = isCurrentPageBookmarked,
                onClick = onToggleBookmark,
            )
            QuickIcon(
                icon = R.drawable.ic_ui_share,
                description = stringResource(R.string.menu_share_page),
                enabled = canUsePageActions,
                onClick = onSharePage,
            )
        }
    }
}

@Composable
private fun QuickIcon(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (active && enabled) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = when {
                    !enabled -> colors.onSurface.copy(alpha = 0.38f)
                    active -> colors.primary
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun LibraryCapsules(
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenFind: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryCapsule(R.drawable.ic_ui_bookmark, stringResource(R.string.menu_bookmarks), onOpenBookmarks, Modifier.weight(1f).fillMaxHeight())
        LibraryCapsule(R.drawable.ic_ui_history, stringResource(R.string.menu_history), onOpenHistory, Modifier.weight(1f).fillMaxHeight())
        LibraryCapsule(R.drawable.ic_ui_download, stringResource(R.string.menu_downloads), onOpenDownloads, Modifier.weight(1f).fillMaxHeight())
        LibraryCapsule(R.drawable.ic_ui_search, stringResource(R.string.menu_find), onOpenFind, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LibraryCapsule(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The 2x4 matrix: five boolean switches and three panel launchers, no empty cell.
 *
 * Every tile is the same [BrowserTileCard]; [BrowserTileCard] alone decides whether the row
 * announces itself as a switch or as a button, so the visual and the TalkBack semantics cannot
 * drift apart.
 */
@Composable
private fun ModeGrid(
    isIncognito: Boolean,
    isDesktopMode: Boolean,
    isReaderActive: Boolean,
    isTranslateActive: Boolean,
    isBrowserFullscreen: Boolean,
    isFilterEnabled: Boolean,
    blockedCount: Int,
    mediaCount: Int,
    hasCastSession: Boolean,
    hasVideo: Boolean,
    canUsePageActions: Boolean,
    onToggleIncognito: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleReader: () -> Unit,
    onOpenTranslation: () -> Unit,
    onToggleBrowserFullscreen: (Boolean) -> Unit,
    onToggleFilter: (Boolean) -> Unit,
    onBlockElement: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_incognito,
                title = stringResource(if (isIncognito) R.string.menu_incognito_exit_short else R.string.menu_incognito_short),
                active = isIncognito,
                isToggle = true,
                onClick = onToggleIncognito,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_monitor,
                title = stringResource(R.string.menu_desktop_site),
                active = isDesktopMode,
                isToggle = true,
                onClick = onToggleDesktopMode,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_reader,
                title = stringResource(R.string.reader_mode),
                active = isReaderActive,
                isToggle = true,
                enabled = canUsePageActions,
                onClick = onToggleReader,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_translate,
                title = stringResource(R.string.menu_translate),
                active = isTranslateActive,
                isToggle = false,
                enabled = canUsePageActions,
                onClick = onOpenTranslation,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_shield,
                title = stringResource(R.string.menu_adblock),
                active = isFilterEnabled,
                isToggle = true,
                subtitle = if (isFilterEnabled) {
                    stringResource(R.string.menu_adblock_blocked, blockedCount)
                } else "",
                onClick = { onToggleFilter(!isFilterEnabled) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_block,
                title = stringResource(R.string.menu_block_element),
                active = false,
                isToggle = false,
                enabled = canUsePageActions,
                onClick = onBlockElement,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_cast,
                title = stringResource(R.string.menu_cast),
                active = hasCastSession,
                isToggle = false,
                enabled = mediaCount > 0 || hasCastSession,
                subtitle = castSubtitle(hasCastSession, mediaCount, hasVideo),
                onClick = onOpenMedia,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BrowserTileCard(
                iconRes = R.drawable.ic_ui_fullscreen,
                title = stringResource(R.string.browser_fullscreen),
                active = isBrowserFullscreen,
                isToggle = true,
                onClick = { onToggleBrowserFullscreen(!isBrowserFullscreen) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * The bottom bar: exit on the left, settings on the right.
 *
 * The right-most cell is the one a second tap on the menu button lands on, so it must be the safe
 * action. Exit keeps the white card face and borrows only the error colour for its glyph and label.
 */
@Composable
private fun FooterRow(onExit: () -> Unit, onOpenMoreTools: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FooterAction(
            icon = R.drawable.ic_ui_exit,
            label = stringResource(R.string.menu_exit_short),
            destructive = true,
            onClick = onExit,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        FooterAction(
            icon = R.drawable.ic_ui_more,
            label = stringResource(R.string.menu_more_tools),
            destructive = false,
            onClick = onOpenMoreTools,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        FooterAction(
            icon = R.drawable.ic_ui_settings,
            label = stringResource(R.string.menu_settings),
            destructive = false,
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun FooterAction(
    icon: Int,
    label: String,
    destructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (destructive) colors.error else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (destructive) colors.error else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
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
    onPrintPage: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    BrowserBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandleOnly = true,
    ) {
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("browser_menu")) {
            BrowserSheetHeader(
                title = stringResource(R.string.menu_more_tools),
                onBack = onDismiss,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowserGroupCard {
                    MenuRow(
                        iconRes = R.drawable.ic_ui_home,
                        title = stringResource(R.string.pin_website),
                        subtitle = "",
                        enabled = canUsePageActions && !isIncognito,
                        onClick = onPinWebsite,
                    )
                    BrowserGroupDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_ui_script,
                        title = stringResource(R.string.script_title),
                        subtitle = userScriptSubtitle(userScriptStatus),
                        onClick = onManageUserScripts,
                    )
                }
                BrowserGroupCard {
                    MenuRow(
                        iconRes = R.drawable.ic_ui_print,
                        title = stringResource(R.string.menu_print),
                        subtitle = "",
                        enabled = canUsePageActions,
                        onClick = onPrintPage,
                    )
                }
                BrowserGroupCard {
                    MenuRow(R.drawable.ic_ui_qr, stringResource(R.string.qr_scan), "", onScanQr)
                    BrowserGroupDivider()
                    MenuRow(
                        iconRes = R.drawable.ic_ui_terminal,
                        title = stringResource(R.string.menu_developer_tools),
                        subtitle = "",
                        onClick = onOpenDeveloperTools,
                    )
                }
                BrowserGroupCard {
                    MenuRow(
                        iconRes = R.drawable.ic_ui_delete,
                        title = stringResource(R.string.menu_clear_data),
                        subtitle = "",
                        onClick = onClearData,
                        destructive = true,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
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
    BrowserListItem(
        title = title,
        subtitle = subtitle,
        iconRes = iconRes,
        onClick = onClick,
        enabled = enabled,
        destructive = destructive,
        toggleState = toggleState,
        trailing = trailing,
    )
}

@Composable
private fun userScriptSubtitle(status: UserScriptStatus?): String =
    status?.running?.takeIf { it.isNotEmpty() }
        ?.let { running ->
            stringResource(R.string.script_status_running_count, running.size, running.joinToString(", "))
        }
        .orEmpty()

@Composable
private fun castSubtitle(hasCastSession: Boolean, mediaCount: Int, hasVideo: Boolean): String = when {
    hasCastSession -> stringResource(R.string.cast_manage_session)
    mediaCount > 0 -> stringResource(R.string.menu_cast_found, mediaCount)
    hasVideo -> stringResource(R.string.menu_cast_no_direct_source)
    else -> ""
}
