package com.mybrowser.ui

import androidx.compose.ui.platform.LocalResources
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.data.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    onSelectHistory: (String) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text(stringResource(R.string.history_clear))
                    }
                }
            }

            // History list
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 600.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(history, key = { it.id }) { entry ->
                        HistoryItem(
                            entry = entry,
                            onClick = {
                                onSelectHistory(entry.url)
                                onDismiss()
                            },
                            onDelete = { onDeleteHistory(entry.id) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon placeholder
            Icon(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Title, URL and time
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTime(entry.visitTime, LocalResources.current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long, textResources: Resources): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> textResources.getString(R.string.ui_just_now)
        diff < 3600_000 -> textResources.getString(R.string.ui_minutes_ago, diff / 60_000)
        diff < 86400_000 -> textResources.getString(R.string.ui_hours_ago, diff / 3600_000)
        diff < 604800_000 -> textResources.getString(R.string.ui_days_ago, diff / 86400_000)
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
