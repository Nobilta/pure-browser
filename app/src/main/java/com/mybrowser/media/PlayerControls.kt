package com.mybrowser.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.PlaybackSpeed

internal enum class PlayerMenu { SPEED, CAST }

internal data class PlayerControlsState(
    val visible: Boolean,
    val locked: Boolean,
    val title: String,
    val playing: Boolean,
    val hasVideo: Boolean,
    val canSeek: Boolean,
    val progress: Float,
    val position: String,
    val rate: Float,
    val canCast: Boolean,
    val menu: PlayerMenu?,
    val hud: String?,
)

/** Everything, including the anchored menus, is drawn in the video's existing window. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerControls(
    state: PlayerControlsState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onLock: () -> Unit,
    onExit: () -> Unit,
    onRotate: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onMenu: (PlayerMenu?) -> Unit,
    onSpeed: (Float) -> Unit,
    castContent: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val viewportHeight = maxHeight
        if (state.visible && !state.locked) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent)))
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIcon(R.drawable.ic_back, stringResource(R.string.ui_exit_fullscreen), onExit)
                Text(state.title, Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleMedium, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                onPictureInPicture?.let {
                    PlayerIcon(R.drawable.ic_pip, stringResource(R.string.picture_in_picture), it)
                }
                PlayerIcon(R.drawable.ic_rotate, stringResource(R.string.ui_rotate_screen), onRotate)
            }
        }

        state.hud?.let { message ->
            // Feedback must stay touch-transparent so consecutive gestures reach the video.
            Box(Modifier.align(Alignment.Center).padding(24.dp)
                .shadow(6.dp, RoundedCornerShape(20.dp))
                .background(colors.inverseSurface.copy(alpha = .96f), RoundedCornerShape(20.dp))
                .semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(message, Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleMedium, color = colors.inverseOnSurface)
            }
        }

        // The first outside tap only dismisses the menu; it cannot pause or seek the video.
        if (state.menu != null) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { onMenu(null) },
            ).clearAndSetSemantics { })
        }

        if (state.visible && !state.locked) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.menu?.let { menu ->
                    val bar = with(density) { barHeight.toDp() }
                    val available = (viewportHeight - bar - 44.dp).coerceAtLeast(48.dp)
                    val popupWidth = if (menu == PlayerMenu.SPEED) 288.dp else 336.dp
                    val heading = stringResource(if (menu == PlayerMenu.SPEED)
                        R.string.playback_speed_title else R.string.cd_cast)
                    Surface(
                        Modifier.widthIn(max = popupWidth).fillMaxWidth()
                            .heightIn(max = minOf(360.dp, available))
                            .testTag(if (menu == PlayerMenu.SPEED) "player_speed_menu" else "player_cast_menu")
                            .semantics { paneTitle = heading },
                        shape = RoundedCornerShape(24.dp),
                        color = colors.surfaceContainerHigh, contentColor = colors.onSurface,
                        shadowElevation = 8.dp,
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                PlayerIcon(R.drawable.ic_close, stringResource(R.string.ui_close)) { onMenu(null) }
                            }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                            if (menu == PlayerMenu.SPEED) {
                                LazyColumn(Modifier.fillMaxWidth().selectableGroup().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(PlaybackSpeed.OPTIONS.chunked(3)) { rates ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            rates.forEach { rate ->
                                                val selected = PlaybackSpeed.equivalent(rate, state.rate)
                                                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
                                                    .selectable(selected, role = Role.RadioButton, onClick = { onSpeed(rate) })
                                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                                    contentAlignment = Alignment.Center) {
                                                    Text(PlaybackSpeed.label(rate), style = MaterialTheme.typography.labelLarge,
                                                        color = if (selected) colors.onPrimaryContainer else colors.onSurface)
                                                }
                                            }
                                            repeat(3 - rates.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            } else castContent()
                        }
                    }
                }
                // Blank parts of the bar stay touch-transparent for native video gestures.
                Box(
                    Modifier.fillMaxWidth().onSizeChanged { barHeight = it.height }
                        .testTag("player_control_bar")
                        .clip(RoundedCornerShape(28.dp))
                        .background(colors.surfaceContainer.copy(alpha = .94f)),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val seekLabel = stringResource(R.string.ui_video_progress)
                        Slider(value = state.progress, onValueChange = onSeek,
                            onValueChangeFinished = onSeekFinished, enabled = state.canSeek,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .semantics { contentDescription = seekLabel })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val playLabel = stringResource(if (state.playing) R.string.ui_pause_video else R.string.ui_play_video)
                            FilledIconButton(onClick = onPlayPause, enabled = state.hasVideo, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play), playLabel,
                                    Modifier.size(24.dp))
                            }
                            Text(state.position, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = colors.onSurfaceVariant)
                            val speedLabel = PlaybackSpeed.label(state.rate)
                            val speedDescription = stringResource(R.string.ui_playback_speed, speedLabel)
                            FilledTonalButton(
                                onClick = { onMenu(if (state.menu == PlayerMenu.SPEED) null else PlayerMenu.SPEED) },
                                modifier = Modifier.height(48.dp).testTag("player_speed_action")
                                    .semantics { contentDescription = speedDescription },
                                shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (state.menu == PlayerMenu.SPEED) colors.primaryContainer else colors.secondaryContainer,
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_speed), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(speedLabel, style = MaterialTheme.typography.labelLarge)
                            }
                            if (state.canCast) {
                                FilledTonalIconButton(
                                    onClick = { onMenu(if (state.menu == PlayerMenu.CAST) null else PlayerMenu.CAST) },
                                    modifier = Modifier.size(48.dp).testTag("player_cast_action"), shape = RoundedCornerShape(16.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = if (state.menu == PlayerMenu.CAST) colors.primaryContainer else colors.secondaryContainer,
                                    ),
                                ) { Icon(painterResource(R.drawable.ic_cast), stringResource(R.string.cd_cast), Modifier.size(24.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if ((state.visible || state.locked) && state.menu == null) {
            FilledTonalIconButton(onClick = onLock,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (state.locked) colors.primaryContainer else colors.surfaceContainerHigh.copy(alpha = .9f),
                    contentColor = if (state.locked) colors.onPrimaryContainer else colors.onSurface,
                ),
            ) {
                Icon(painterResource(if (state.locked) R.drawable.ic_lock_open else R.drawable.ic_lock),
                    stringResource(if (state.locked) R.string.ui_unlock_screen else R.string.ui_lock_screen), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun PlayerIcon(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Icon(painterResource(icon), description, Modifier.size(24.dp))
    }
}
