package com.mybrowser.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.dlna.AvTransport
import com.mybrowser.dlna.DlnaDevice
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.localizedResources

@Composable
internal fun RemotePlaybackControls(device: DlnaDevice, playback: AvTransport.PlaybackStatus?,
    unavailable: Boolean, busy: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
    onVolume: (Int) -> Unit, onSeek: (Long) -> Unit, onSkip: (Long) -> Unit,
    onRefresh: () -> Unit, onDisconnect: () -> Unit,
    /** Increments on every device report, so a value that repeats still signals a fresh report. */
    playbackRevision: Long = 0) {
    val resources = localizedResources()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(device.displayName(resources), style = MaterialTheme.typography.titleMedium)
            val status = when {
                unavailable -> R.string.cast_status_unavailable
                playback?.transportState == "PLAYING" -> R.string.cast_remote_playing
                playback?.transportState == "PAUSED_PLAYBACK" -> R.string.cast_remote_paused
                playback?.transportState in listOf("STOPPED", "NO_MEDIA_PRESENT") -> R.string.cast_remote_stopped
                else -> R.string.cast_remote_waiting
            }
            Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
                color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // Skipping needs a position to skip from; without one the device is asked and says so
                // itself, which is why this is not tied to the slider being drawn.
                val skippable = !busy && playback?.positionSeconds != null
                BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cast_skip_back),
                    skippable) { onSkip(-SKIP_SECONDS) }
                BrowserIconAction(R.drawable.ic_play, stringResource(R.string.cast_remote_resume),
                    !busy && playback?.transportState != "PLAYING", onResume)
                BrowserIconAction(R.drawable.ic_pause, stringResource(R.string.cast_remote_pause),
                    !busy && playback?.transportState !in listOf("PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT"), onPause)
                BrowserIconAction(R.drawable.ic_forward, stringResource(R.string.cast_skip_forward),
                    skippable) { onSkip(SKIP_SECONDS) }
                BrowserIconAction(R.drawable.ic_stop, stringResource(R.string.cast_remote_stop), !busy, onStop)
                BrowserIconAction(R.drawable.ic_reload, stringResource(R.string.cast_refresh_status), !busy, onRefresh)
            }
            val duration = playback?.durationSeconds?.takeIf { it > 0 }
            val position = playback?.positionSeconds
            if (duration != null && position != null) RemoteSlider(
                stringResource(R.string.cast_position, AvTransport.formatTime(position), AvTransport.formatTime(duration)),
                position.coerceAtMost(duration).toFloat(), duration.toFloat(), !busy,
                playbackRevision) { onSeek(it.toLong()) }
            playback?.volume?.let { volume ->
                // The revision is what clears the draft: without it a device that refuses a volume
                // change leaves the thumb on a value that never took effect, because the later report
                // carries the same number and a value-keyed effect would not run.
                RemoteSlider(stringResource(R.string.cast_volume, volume), volume.toFloat(), 100f, !busy,
                    playbackRevision) { onVolume(it.toInt()) }
            }
            TextButton(onClick = onDisconnect, enabled = !busy) { Text(stringResource(R.string.cast_disconnect)) }
        }
    }
}

@Composable
private fun RemoteSlider(
    label: String,
    value: Float,
    maximum: Float,
    enabled: Boolean,
    /** Changes on every device report even when [value] does not; see the position slider. */
    revision: Long = 0,
    onCommit: (Float) -> Unit,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    // The device's position keeps moving while the panel is open, so a poll must not move the thumb
    // out from under a finger that is still down. Once the finger is up, the next position the
    // device reports is the truth again — including when it disagrees with what was asked for,
    // which is what a refused seek looks like, and the thumb then shows that instead of holding a
    // value that never took effect.
    //
    // Keyed on the report, not on the value: a device that reports the same position twice —
    // paused, or refusing the seek — would leave a value-keyed effect un-run and the thumb holding
    // a position that never took effect. [revision] advances on every report, so the draft is
    // cleared exactly when the device has spoken again, which is what the promise above says.
    LaunchedEffect(revision) { if (!dragging) draft = null }
    Text(label, style = MaterialTheme.typography.bodySmall)
    Slider(value = (draft ?: value).coerceIn(0f, maximum),
        onValueChange = { dragging = true; draft = it },
        modifier = Modifier.semantics { contentDescription = label },
        valueRange = 0f..maximum, enabled = enabled,
        onValueChangeFinished = { dragging = false; draft?.let(onCommit) })
}

/** How far the skip buttons jump. Ten is the step every player's remote uses. */
private const val SKIP_SECONDS = 10L
