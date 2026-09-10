package com.mybrowser.ui

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

@Composable
internal fun RemotePlaybackControls(device: DlnaDevice, playback: AvTransport.PlaybackStatus?,
    unavailable: Boolean, busy: Boolean, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
    onVolume: (Int) -> Unit, onSeek: (Long) -> Unit, onRefresh: () -> Unit, onDisconnect: () -> Unit) {
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
                BrowserIconAction(R.drawable.ic_play, stringResource(R.string.cast_remote_resume),
                    !busy && playback?.transportState != "PLAYING", onResume)
                BrowserIconAction(R.drawable.ic_pause, stringResource(R.string.cast_remote_pause),
                    !busy && playback?.transportState !in listOf("PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT"), onPause)
                BrowserIconAction(R.drawable.ic_stop, stringResource(R.string.cast_remote_stop), !busy, onStop)
                BrowserIconAction(R.drawable.ic_reload, stringResource(R.string.cast_refresh_status), !busy, onRefresh)
            }
            val duration = playback?.durationSeconds?.takeIf { it > 0 }
            val position = playback?.positionSeconds
            if (duration != null && position != null) RemoteSlider(
                stringResource(R.string.cast_position, AvTransport.formatTime(position), AvTransport.formatTime(duration)),
                position.coerceAtMost(duration).toFloat(), duration.toFloat(), !busy) { onSeek(it.toLong()) }
            playback?.volume?.let { volume ->
                RemoteSlider(stringResource(R.string.cast_volume, volume), volume.toFloat(), 100f, !busy) { onVolume(it.toInt()) }
            }
            TextButton(onClick = onDisconnect, enabled = !busy) { Text(stringResource(R.string.cast_disconnect)) }
        }
    }
}

@Composable
private fun RemoteSlider(label: String, value: Float, maximum: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    Text(label, style = MaterialTheme.typography.bodySmall)
    Slider(value = (draft ?: value).coerceIn(0f, maximum), onValueChange = { draft = it },
        modifier = Modifier.semantics { contentDescription = label },
        valueRange = 0f..maximum, enabled = enabled,
        onValueChangeFinished = { draft?.let(onCommit); draft = null })
}
