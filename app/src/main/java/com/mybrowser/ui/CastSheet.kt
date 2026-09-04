package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mybrowser.R
import com.mybrowser.dlna.DlnaDevice
import com.mybrowser.media.MediaSniffer

/**
 * Two-step cast flow: pick the stream, then pick the renderer.
 *
 * Split in two because both lists can be long and both can be ambiguous — a page often
 * exposes several qualities of the same video, and a living room often has several renderers.
 * Collapsing them into one grid would multiply the two.
 */
@Composable
fun CastSheet(
    candidates: List<MediaSniffer.Candidate>,
    devices: List<DlnaDevice>,
    isSearching: Boolean,
    onSearch: () -> Unit,
    onCast: (MediaSniffer.Candidate, DlnaDevice) -> Unit,
    onCopyUrl: (MediaSniffer.Candidate) -> Unit,
    onDismiss: () -> Unit,
    preferredCandidate: MediaSniffer.Candidate? = null,
    playingCandidateUrls: Set<String> = emptySet(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A player often requests the same manifest more than once with a cache-busting or
    // rotating-auth query.  Those URLs must remain selectable (dropping the query can make
    // a cast fail), but identical labels make the picker look like it contains duplicates.
    // Annotate only the presentation copy and keep the original Candidate for casting.
    val displayLabels = remember(candidates) { duplicateAwareLabels(candidates) }
    var selected by remember { mutableStateOf(preferredCandidate ?: candidates.firstOrNull()) }
    var userSelected by remember { mutableStateOf(false) }

    // Candidates may arrive after the sheet opens, and the active player can change while it
    // remains open. Keep the default selection aligned with the latest detected stream while
    // preserving an explicit user choice when it is still present.
    LaunchedEffect(preferredCandidate?.url, candidates) {
        val selectedStillExists = selected?.url?.let { url -> candidates.any { it.url == url } } == true
        if (!selectedStillExists) userSelected = false
        if (!userSelected || !selectedStillExists) {
            selected = preferredCandidate ?: candidates.firstOrNull()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Keep both sections in one lazy list. Two sibling LazyColumns inside a sheet
        // compete for the same bounded height: the first can consume the entire viewport,
        // making renderer devices unreachable. A single list gives every item one scroll
        // position and behaves consistently on short phones and landscape screens.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.cast_pick_media)) }

            items(candidates, key = { it.url }) { candidate ->
                MediaRow(
                    candidate = candidate,
                    displayLabel = displayLabels[candidate.url] ?: candidate.label,
                    isSelected = candidate.url == selected?.url,
                    isPlaying = candidate.url in playingCandidateUrls,
                    onClick = {
                        selected = candidate
                        userSelected = true
                    },
                    onCopy = { onCopyUrl(candidate) },
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.cast_pick_device),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        TextButton(onClick = onSearch) {
                            Text(stringResource(R.string.cast_search_again))
                        }
                    }
                }
            }

            if (devices.isEmpty() && !isSearching) {
                item {
                    Text(
                        text = stringResource(R.string.cast_no_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }

            items(devices, key = { it.controlUrl }) { device ->
                DeviceRow(
                    device = device,
                    // A stream with no selection cannot be cast; the row stays visible
                    // but inert rather than vanishing.
                    enabled = selected != null,
                    onClick = { selected?.let { onCast(it, device) } },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun MediaRow(
    candidate: MediaSniffer.Candidate,
    displayLabel: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (isSelected) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked,
            ),
            contentDescription = null,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isPlaying) {
                    Text(
                        text = stringResource(R.string.cast_playing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                // Host only. The full URL of a video stream is routinely 300+ characters of
                // signed query string and tells the user nothing.
                text = runCatching { candidate.url.toUri().host }
                    .getOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCopy) {
            Text(stringResource(R.string.cast_copy))
        }
    }
}

/**
 * Adds a stable, human-readable variant suffix when two URLs would otherwise render with
 * the same basename.  The map is keyed by the full URL so signed/query-bearing candidates
 * remain distinct and can still be copied or cast independently.
 */
private fun duplicateAwareLabels(
    candidates: List<MediaSniffer.Candidate>,
): Map<String, String> {
    val counts = candidates.groupingBy { it.label }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return candidates.associate { candidate ->
        val count = counts[candidate.label] ?: 1
        val index = (seen[candidate.label] ?: 0) + 1
        seen[candidate.label] = index
        candidate.url to if (count > 1) {
            "${candidate.label}（变体 $index）"
        } else {
            candidate.label
        }
    }
}

@Composable
private fun DeviceRow(
    device: DlnaDevice,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tv),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = device.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
