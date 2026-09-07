package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.media.PlaybackSpeed

/** Browser actions grouped into predictable Material 3 sections. */
@Composable
fun MenuSheet(
    isIncognito: Boolean,
    isFilterEnabled: Boolean,
    blockedCount: Int,
    mediaCount: Int,
    hasVideo: Boolean,
    playbackSpeed: Float,
    isDesktopMode: Boolean,
    isCurrentPageBookmarked: Boolean,
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.menu_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            MenuSection(title = stringResource(R.string.menu_section_page)) {
                MenuRow(
                    iconRes = R.drawable.ic_bookmark,
                    title = stringResource(
                        if (isCurrentPageBookmarked) R.string.menu_remove_bookmark
                        else R.string.menu_add_bookmark,
                    ),
                    subtitle = stringResource(
                        if (isCurrentPageBookmarked) R.string.menu_remove_bookmark_hint
                        else R.string.menu_add_bookmark_hint,
                    ),
                    onClick = onToggleBookmark,
                )
                MenuRow(
                    iconRes = R.drawable.ic_search,
                    title = stringResource(R.string.menu_find),
                    subtitle = stringResource(R.string.menu_find_hint),
                    onClick = onOpenFind,
                )
                MenuRow(
                    iconRes = R.drawable.ic_desktop,
                    title = stringResource(R.string.menu_desktop_site),
                    subtitle = stringResource(
                        if (isDesktopMode) R.string.menu_desktop_on
                        else R.string.menu_desktop_off,
                    ),
                    onClick = onToggleDesktopMode,
                    trailing = {
                        Switch(
                            checked = isDesktopMode,
                            onCheckedChange = { onToggleDesktopMode() },
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
                    subtitle = if (mediaCount > 0) {
                        stringResource(R.string.menu_cast_found, mediaCount)
                    } else {
                        stringResource(R.string.menu_cast_none)
                    },
                    enabled = mediaCount > 0,
                    onClick = onOpenMedia,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_data)) {
                MenuRow(
                    iconRes = R.drawable.ic_bookmark,
                    title = stringResource(R.string.menu_bookmarks),
                    subtitle = stringResource(R.string.menu_bookmarks_hint),
                    onClick = onOpenBookmarks,
                )
                MenuRow(
                    iconRes = R.drawable.ic_history,
                    title = stringResource(R.string.menu_history),
                    subtitle = stringResource(R.string.menu_history_hint),
                    onClick = onOpenHistory,
                )
                MenuRow(
                    iconRes = R.drawable.ic_download,
                    title = stringResource(R.string.menu_downloads),
                    subtitle = stringResource(R.string.menu_downloads_hint),
                    onClick = onOpenDownloads,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_privacy)) {
                MenuRow(
                    iconRes = R.drawable.ic_incognito,
                    title = stringResource(
                        if (isIncognito) R.string.menu_exit_incognito
                        else R.string.menu_enter_incognito,
                    ),
                    subtitle = stringResource(
                        if (isIncognito) R.string.menu_incognito_active_hint
                        else R.string.menu_incognito_hint,
                    ),
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
                    trailing = {
                        Switch(
                            checked = isFilterEnabled,
                            onCheckedChange = onToggleFilter,
                        )
                    },
                )
                MenuRow(
                    iconRes = R.drawable.ic_delete,
                    title = stringResource(R.string.menu_clear_data),
                    subtitle = stringResource(R.string.menu_clear_data_hint),
                    onClick = onClearData,
                )
            }

            MenuSection(title = stringResource(R.string.menu_section_tools)) {
                MenuRow(
                    iconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.menu_settings),
                    subtitle = stringResource(R.string.menu_settings_hint),
                    onClick = onOpenSettings,
                )
                MenuRow(
                    iconRes = R.drawable.ic_code,
                    title = stringResource(R.string.menu_developer_tools),
                    subtitle = stringResource(R.string.menu_developer_tools_hint),
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
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
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
