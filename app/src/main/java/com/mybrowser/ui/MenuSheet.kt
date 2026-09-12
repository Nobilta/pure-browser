package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.media.PlaybackSpeed

/** Browser actions grouped into predictable Material 3 sections. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MenuSheet(
    isIncognito: Boolean,
    isFilterEnabled: Boolean,
    blockedCount: Int,
    mediaCount: Int,
    hasCastSession: Boolean,
    hasVideo: Boolean,
    playbackSpeed: Float,
    isDesktopMode: Boolean,
    isCurrentPageBookmarked: Boolean,
    canUsePageActions: Boolean,
    onOpenSiteSettings: () -> Unit,
    onPinWebsite: () -> Unit = {},
    onToggleIncognito: () -> Unit,
    onToggleFilter: (Boolean) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onOpenFind: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleBookmark: () -> Unit,
    onClearData: () -> Unit,
    onOpenDeveloperTools: () -> Unit = {},
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textResources = localizedResources()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The dialog has a separate saved-state registry. Keep scroll state in the
    // route's composition so returning from Settings restores the actual menu row.
    val scrollState = rememberScrollState()
    // Capture host insets before entering the dialog (Android 10 can report zero
    // inside it). A tall menu must not put its drag handle under the status bar.
    val contentInsets = WindowInsets.safeDrawing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { contentInsets },
    ) {
        ApplySheetSystemBars()
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("browser_menu")
                .verticalScroll(scrollState)
                .padding(start = 12.dp, end = 12.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.menu_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickMenuAction(R.drawable.ic_bookmark, stringResource(if (isCurrentPageBookmarked) R.string.menu_remove_bookmark else R.string.menu_add_bookmark), canUsePageActions, onToggleBookmark, Modifier.weight(1f))
                QuickMenuAction(R.drawable.ic_search, stringResource(R.string.menu_find), canUsePageActions, onOpenFind, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                QuickMenuAction(R.drawable.ic_bookmark, stringResource(R.string.menu_bookmarks), true, onOpenBookmarks, Modifier.weight(1f))
                QuickMenuAction(R.drawable.ic_download, stringResource(R.string.menu_downloads), true, onOpenDownloads, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                QuickMenuAction(R.drawable.ic_settings, stringResource(R.string.site_settings), canUsePageActions, onOpenSiteSettings, Modifier.weight(1f))
                QuickMenuAction(R.drawable.ic_settings, stringResource(R.string.menu_settings), true, onOpenSettings, Modifier.weight(1f))
            }

            MenuSection(title = stringResource(R.string.menu_section_page)) {
                MenuRow(iconRes = R.drawable.ic_home, title = stringResource(R.string.pin_website),
                    subtitle = "", enabled = canUsePageActions && !isIncognito, onClick = onPinWebsite)
                MenuRow(
                    iconRes = R.drawable.ic_desktop,
                    title = stringResource(R.string.menu_desktop_site),
                    enabled = canUsePageActions,
                    subtitle = stringResource(
                        if (isDesktopMode) R.string.menu_desktop_on
                        else R.string.menu_desktop_off,
                    ),
                    onClick = onToggleDesktopMode,
                    toggleState = isDesktopMode,
                    trailing = {
                        Switch(
                            checked = isDesktopMode,
                            onCheckedChange = null,
                            enabled = canUsePageActions,
                        )
                    },
                )
                MenuRow(
                    iconRes = R.drawable.ic_speed,
                    title = stringResource(R.string.menu_playback_speed),
                    subtitle = if (hasVideo) {
                        stringResource(
                            R.string.menu_playback_speed_current,
                            PlaybackSpeed.label(playbackSpeed),
                        )
                    } else {
                        stringResource(R.string.menu_playback_speed_unavailable)
                    },
                    enabled = hasVideo,
                    onClick = onOpenPlaybackSpeed,
                )
                MenuRow(
                    iconRes = R.drawable.ic_cast,
                    title = stringResource(R.string.menu_cast),
                    subtitle = when {
                        hasCastSession -> stringResource(R.string.cast_manage_session)
                        mediaCount > 0 -> stringResource(R.string.menu_cast_found, mediaCount)
                        hasVideo -> stringResource(R.string.menu_cast_no_direct_source)
                        else -> stringResource(R.string.menu_cast_none)
                    },
                    enabled = mediaCount > 0 || hasCastSession,
                    onClick = onOpenMedia,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_data)) {
                MenuRow(
                    iconRes = R.drawable.ic_history,
                    title = stringResource(R.string.menu_history),
                    subtitle = "",
                    onClick = onOpenHistory,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_privacy)) {
                MenuRow(
                    iconRes = R.drawable.ic_incognito,
                    title = stringResource(
                        if (isIncognito) R.string.menu_exit_incognito
                        else R.string.menu_enter_incognito,
                    ),
                    subtitle = "",
                    onClick = onToggleIncognito,
                )
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
                    trailing = {
                        Switch(
                            checked = isFilterEnabled,
                            onCheckedChange = null,
                        )
                    },
                )
                MenuRow(
                    iconRes = R.drawable.ic_delete,
                    title = stringResource(R.string.menu_clear_data),
                    subtitle = "",
                    onClick = onClearData,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_tools)) {
                MenuRow(
                    iconRes = R.drawable.ic_code,
                    title = stringResource(R.string.menu_developer_tools),
                    subtitle = "",
                    onClick = onOpenDeveloperTools,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            MenuRow(
                iconRes = R.drawable.ic_close,
                title = textResources.getString(R.string.ui_exit_browser),
                subtitle = "",
                onClick = onExit,
            )
        }
    }
}

@Composable
private fun MenuSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp),
    )
    Column(modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun MenuRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    toggleState: Boolean? = null,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (toggleState != null) Modifier.toggleable(toggleState, enabled, Role.Switch) { onClick() } else Modifier.clickable(enabled = enabled, onClick = onClick))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            if (subtitle.isNotEmpty()) Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun QuickMenuAction(icon: Int, label: String, enabled: Boolean, action: () -> Unit, modifier: Modifier) {
    Column(modifier.heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
