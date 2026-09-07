package com.mybrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.data.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>, query: String, loading: Boolean, hasMore: Boolean, error: Boolean,
    onQueryChange: (String) -> Unit, onLoadMore: () -> Unit,
    onSelectHistory: (String) -> Unit, onOpenNewTab: (String) -> Unit, onCopy: (String) -> Unit,
    onDeleteHistory: (Long) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit,
) {
    val resources = localizedResources()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = resources.configuration.locales[0]
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val groups = history.groupBy { Instant.ofEpochMilli(it.visitTime).atZone(zone).toLocalDate() }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.history_clear), history.isNotEmpty(), onClearAll)
            }
            LibrarySearchField(query, stringResource(R.string.history_search), onQueryChange)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (history.isEmpty() && !loading) item {
                    Text(stringResource(if (error) R.string.library_load_failed else if (query.isBlank()) R.string.history_empty else R.string.library_no_results),
                        modifier = Modifier.padding(vertical = 40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                groups.forEach { (date, entries) ->
                    stickyHeader(key = date.toString()) {
                        Text(when (date) {
                            today -> resources.getString(R.string.history_today)
                            today.minusDays(1) -> resources.getString(R.string.history_yesterday)
                            else -> dateFormatter.format(date)
                        }, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    items(entries, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable { onSelectHistory(entry.url) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(timeFormatter.format(Instant.ofEpochMilli(entry.visitTime).atZone(zone)),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LibraryItemMenu(onOpenNewTab = { onOpenNewTab(entry.url) }, onCopy = { onCopy(entry.url) },
                                onDelete = { onDeleteHistory(entry.id) })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item { LibraryFooter(loading, hasMore, onLoadMore, error) }
            }
        }
    }
}
