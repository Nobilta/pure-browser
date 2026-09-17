package com.mybrowser.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import com.mybrowser.ui.shell.BrowserMotion

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
    val buffering: Boolean,
    val networkSpeed: String?,
    val statusTime: String,
    val statusBattery: Int?,
    val statusCharging: Boolean,
)

/** Clock and battery read below [LOW_BATTERY_PERCENT]; charging is never an alert. */
private const val LOW_BATTERY_PERCENT = 20

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
    onSpeedPreview: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    castContent: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var barHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val viewportHeight = maxHeight
        val topBarVisible = state.visible && !state.locked
        AnimatedVisibility(
            visible = topBarVisible,
            enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                slideInVertically(animationSpec = BrowserMotion.overlayEnter) { -it },
            exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                slideOutVertically(animationSpec = BrowserMotion.overlayExit) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
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
                // The immersive bars hide the system clock, so the controls carry time and
                // battery for as long as they are on screen; they leave with the bar.
                PlayerStatusStrip(
                    state.statusTime, state.statusBattery, state.statusCharging,
                    Modifier.padding(start = 8.dp, end = 4.dp),
                )
            }
        }

        // Feedback and buffering share the centre: a stall and a gesture can be visible at once.
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visible = state.buffering,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter),
                exit = fadeOut(animationSpec = BrowserMotion.localExit),
            ) {
                val label = stringResource(R.string.ui_player_buffering)
                Row(
                    Modifier.testTag("player_buffering")
                        .shadow(6.dp, RoundedCornerShape(20.dp))
                        .background(colors.inverseSurface.copy(alpha = .96f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = label
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = colors.inverseOnSurface)
                    Text(label, style = MaterialTheme.typography.labelLarge, color = colors.inverseOnSurface)
                    // The rate changes twice a second; announcing it would flood TalkBack.
                    state.networkSpeed?.let { speed ->
                        Text(stringResource(R.string.ui_player_network_speed, speed),
                            Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.inverseOnSurface.copy(alpha = .8f))
                    }
                }
            }
            // A gesture message leaves the same way the buffering pill does, keeping its text while
            // it fades instead of blanking the surface first.
            var lastHud by remember { mutableStateOf(state.hud) }
            if (state.hud != null) lastHud = state.hud
            AnimatedVisibility(
                visible = state.hud != null,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter),
                exit = fadeOut(animationSpec = BrowserMotion.localExit),
            ) {
                lastHud?.let { message ->
                    // Feedback must stay touch-transparent so consecutive gestures reach the video.
                    Box(Modifier.shadow(6.dp, RoundedCornerShape(20.dp))
                        .background(colors.inverseSurface.copy(alpha = .96f), RoundedCornerShape(20.dp))
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(message, Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.titleMedium, color = colors.inverseOnSurface)
                    }
                }
            }
        }

        // The first outside tap only dismisses the menu; it cannot pause or seek the video.
        if (state.menu != null) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { onMenu(null) },
            ).clearAndSetSemantics { })
        }

        // The bar leaves towards the edge it sits on, the way the player's chrome does.
        AnimatedVisibility(
            visible = topBarVisible,
            enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                slideInVertically(animationSpec = BrowserMotion.overlayEnter) { it },
            exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                slideOutVertically(animationSpec = BrowserMotion.overlayExit) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The panel keeps its last page composed while it leaves.
                var lastMenu by remember { mutableStateOf(state.menu) }
                if (state.menu != null) lastMenu = state.menu
                AnimatedVisibility(
                    visible = state.menu != null,
                    enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                        scaleIn(animationSpec = BrowserMotion.localEnter,
                            transformOrigin = TransformOrigin(1f, 1f), initialScale = 0.92f),
                    exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                        scaleOut(animationSpec = BrowserMotion.localExit,
                            transformOrigin = TransformOrigin(1f, 1f), targetScale = 0.94f),
                ) {
                lastMenu?.let { menu ->
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
                                SpeedPanel(state.rate, onSpeedPreview, onSpeed)
                            } else castContent()
                        }
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

        AnimatedVisibility(
            visible = (state.visible || state.locked) && state.menu == null,
            enter = fadeIn(animationSpec = BrowserMotion.localEnter),
            exit = fadeOut(animationSpec = BrowserMotion.localExit),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        ) {
            FilledTonalIconButton(onClick = onLock,
                modifier = Modifier.size(48.dp),
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

/**
 * The rate is continuous, so the panel offers one slider on the shared 0.1 grid; its tick marks
 * are the grid, which is all the shortcuts the panel needs.
 *
 * The draft is kept here rather than read back from playback: a JS round trip and a 1 s probe
 * pulse both lag the finger, and the release must commit what the user last saw.
 */
@Composable
private fun SpeedPanel(
    rate: Float,
    onSpeedPreview: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = (draft ?: rate).coerceIn(PlaybackSpeed.MIN, PlaybackSpeed.MAX)
    val heading = stringResource(R.string.playback_speed_title)
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_current_playback_speed), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(PlaybackSpeed.label(shown),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = shown,
            onValueChange = { value ->
                val quantized = PlaybackSpeed.quantize(value)
                draft = quantized
                onSpeedPreview(quantized)
            },
            onValueChangeFinished = {
                draft?.let(onSpeed)
                draft = null
            },
            valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
            steps = PlaybackSpeed.STEPS,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("player_speed_slider")
                .semantics { contentDescription = heading },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PlaybackSpeed.label(PlaybackSpeed.MIN), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(PlaybackSpeed.label(PlaybackSpeed.MAX), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Clock and battery for the top of the immersive player.
 *
 * The colour is the theme's error role rather than a fixed red: the player always renders in the
 * dark scheme, so this picks up the dynamic palette on Android 12+ and the baseline error colour
 * elsewhere. A low battery is the only alert: it colours the battery and its percentage, and the
 * clock keeps the surface colour. Charging is not an alert, so a plugged-in battery is normal.
 */
@Composable
private fun PlayerStatusStrip(time: String, battery: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    if (time.isEmpty() && battery == null) return
    val alert = battery != null && battery < LOW_BATTERY_PERCENT && !charging
    // Only the battery carries the alert colour; the clock stays on the surface colour.
    val clockColor = MaterialTheme.colorScheme.onSurface
    val batteryColor = if (alert) MaterialTheme.colorScheme.error else clockColor
    Row(modifier.semantics(mergeDescendants = true) {}.testTag("player_status_strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (time.isNotEmpty()) {
            Text(time, maxLines = 1,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = clockColor)
        }
        if (battery != null) {
            Icon(painterResource(R.drawable.ic_battery), null, Modifier.size(16.dp), tint = batteryColor)
            Text("$battery%", maxLines = 1,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = batteryColor)
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
