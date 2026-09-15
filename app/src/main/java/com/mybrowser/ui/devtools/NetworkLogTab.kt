package com.mybrowser.ui.devtools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.NetworkCategory
import com.mybrowser.core.NetworkLogQuery
import com.mybrowser.core.NetworkOutcome
import com.mybrowser.core.NetworkRequestLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.localizedResources

@Composable
internal fun NetworkLogTab(entries: List<NetworkRequestLog>, onClear: () -> Unit, onExplain: (NetworkRequestLog) -> Unit) {
    val resources = localizedResources()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(NetworkCategory.ALL) }
    var outcome by rememberSaveable { mutableStateOf(NetworkOutcome.ALL) }
    var appliedQuery by remember { mutableStateOf(query) }
    var statusMenu by remember { mutableStateOf(false) }
    var filtered by remember { mutableStateOf<List<NetworkRequestLog>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<NetworkCategory, Int>>(emptyMap()) }
    val listState = rememberLazyListState()
    var previousFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = NetworkCategory.entries.associateWith { resources.getString(it.label) }
    val outcomes = NetworkOutcome.entries.associateWith { resources.getString(it.label) }
    LaunchedEffect(query) { delay(150); appliedQuery = query }
    LaunchedEffect(entries, appliedQuery, category, outcome, resources.configuration) {
        val result = withContext(Dispatchers.Default) {
            val search = NetworkLogQuery(appliedQuery)
            val matching = entries.asReversed().filter { request ->
                val status = if (request.blocked) outcomes.getValue(NetworkOutcome.BLOCKED)
                    else if (NetworkOutcome.FAILED.matches(request)) outcomes.getValue(NetworkOutcome.FAILED) else ""
                outcome.matches(request) && search.matches(request, labels.getValue(NetworkCategory.of(request)), status)
            }
            val totals = matching.groupingBy(NetworkCategory::of).eachCount() + (NetworkCategory.ALL to matching.size)
            matching.filter { category == NetworkCategory.ALL || NetworkCategory.of(it) == category } to totals
        }
        filtered = result.first
        counts = result.second
    }
    LaunchedEffect(appliedQuery, category, outcome) {
        val filter = "$category/$outcome/$appliedQuery"
        if (previousFilter != null && previousFilter != filter) listState.scrollToItem(0)
        previousFilter = filter
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            LibrarySearchField(query, stringResource(R.string.dev_network_search)) { query = it }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NetworkCategory.entries, key = { it.name }) { item ->
                FilterChip(category == item, { category = item }, label = { Text("${labels.getValue(item)} ${counts[item] ?: 0}") })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { statusMenu = true }) { Text(outcomes.getValue(outcome)) }
                DropdownMenu(statusMenu, { statusMenu = false }) {
                    NetworkOutcome.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(outcomes.getValue(item)) }, onClick = { outcome = item; statusMenu = false })
                    }
                }
            }
            Text(stringResource(R.string.dev_network_count, filtered.size, entries.size), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.ui_clear)) }
        }
        if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(if (entries.isEmpty()) R.string.ui_no_requests_yet_open_or_refresh_a_page else R.string.library_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            items(filtered, key = { it.id }, contentType = { "request" }) { request ->
                NetworkRequestItem(request, onExplain)
            }
        }
    }
}
