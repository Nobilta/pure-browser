package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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

    BrowserBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cd_back), onClick = onDismiss)
                Text(
                    text = stringResource(R.string.playback_speed_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
            PlaybackSpeed.OPTIONS.forEach { speed ->
                val selected = PlaybackSpeed.equivalent(speed, currentSpeed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(speed) })
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
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
