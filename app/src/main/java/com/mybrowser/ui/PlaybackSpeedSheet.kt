package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.media.PlaybackSpeed

/** Material 3 single-choice sheet for the currently playing HTML5 video. */
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.playback_speed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            PlaybackSpeed.OPTIONS.forEach { speed ->
                val selected = PlaybackSpeed.equivalent(speed, currentSpeed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(speed) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelect(speed) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (PlaybackSpeed.equivalent(speed, PlaybackSpeed.DEFAULT)) {
                            stringResource(
                                R.string.playback_speed_normal,
                                PlaybackSpeed.label(speed),
                            )
                        } else {
                            PlaybackSpeed.label(speed)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
