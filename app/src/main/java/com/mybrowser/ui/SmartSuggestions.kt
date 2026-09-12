package com.mybrowser.ui

import com.mybrowser.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class Suggestion(
    val title: String,
    val url: String,
    val type: SuggestionType,
    val visitCount: Int = 0,
    val lastVisit: Long = 0,
    val faviconUrl: String? = null
)

enum class SuggestionType {
    BOOKMARK,
    HISTORY,
    SEARCH
}

@Composable
fun SmartSuggestions(
    query: String,
    bookmarkManager: BookmarkManager,
    historyManager: HistoryManager?,
    onSuggestionClick: (String) -> Unit,
    onFillSuggestion: (String) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = 400.dp,
    modifier: Modifier = Modifier
) {
    val textResources = localizedResources()
    // Do not leave rows for the previous query clickable during debounce.
    var suggestions by remember(query, bookmarkManager, historyManager) { mutableStateOf<List<Suggestion>>(emptyList()) }

    LaunchedEffect(query, bookmarkManager, historyManager) {
        if (query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }

        delay(150) // Debounce
        val results = withContext(Dispatchers.IO) {
            val found = mutableListOf<Suggestion>()
            runCatching { bookmarkManager.suggestions(query) }
                .getOrDefault(emptyList())
                .forEach { bookmark ->
                    found += Suggestion(
                        title = bookmark.title,
                        url = bookmark.url,
                        type = SuggestionType.BOOKMARK,
                        faviconUrl = bookmark.faviconUrl,
                    )
                }
            runCatching { historyManager?.suggestions(query).orEmpty() }
                .getOrDefault(emptyList())
                .forEach { entry ->
                    found += Suggestion(
                        title = entry.title,
                        url = entry.url,
                        type = SuggestionType.HISTORY,
                        visitCount = entry.visitCount,
                        lastVisit = entry.visitTime,
                    )
                }
            found
        }.toMutableList()

        val ranked = rankSuggestions(query, results)
        val search = if (!com.mybrowser.core.UrlUtils.isNavigableInput(query)) listOf(Suggestion(
            title = textResources.getString(R.string.ui_search, query), url = query, type = SuggestionType.SEARCH)) else emptyList()
        suggestions = ranked.take(8 - search.size) + search

    }

    if (suggestions.isNotEmpty()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight)
            ) {
                items(suggestions, key = { it.type.name + ":" + it.url }) { suggestion ->
                    SuggestionItem(
                        suggestion = suggestion,
                        onClick = { onSuggestionClick(suggestion.url) },
                        onFill = { onFillSuggestion(suggestion.url) }
                    )
                    if (suggestion != suggestions.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    suggestion: Suggestion,
    onClick: () -> Unit,
    onFill: () -> Unit,
) {
    val textResources = localizedResources()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (suggestion.type) {
                        SuggestionType.BOOKMARK -> MaterialTheme.colorScheme.primaryContainer
                        SuggestionType.HISTORY -> MaterialTheme.colorScheme.secondaryContainer
                        SuggestionType.SEARCH -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (suggestion.type) {
                    SuggestionType.BOOKMARK -> Icons.Default.Star
                    SuggestionType.HISTORY -> Icons.Default.DateRange
                    SuggestionType.SEARCH -> Icons.Default.Search
                },
                contentDescription = null,
                tint = when (suggestion.type) {
                    SuggestionType.BOOKMARK -> MaterialTheme.colorScheme.onPrimaryContainer
                    SuggestionType.HISTORY -> MaterialTheme.colorScheme.onSecondaryContainer
                    SuggestionType.SEARCH -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and URL
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (suggestion.type != SuggestionType.SEARCH) {
                Text(
                    text = suggestion.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        IconButton(onClick = onFill) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_forward),
                contentDescription = textResources.getString(R.string.suggestion_fill, suggestion.title))
        }

        // Visit count badge for history
        if (suggestion.type == SuggestionType.HISTORY && suggestion.visitCount > 1) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = "${suggestion.visitCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

internal fun rankSuggestions(query: String, candidates: List<Suggestion>, now: Long = System.currentTimeMillis()): List<Suggestion> {
    val needle = query.trim().lowercase(java.util.Locale.ROOT).removePrefix("https://").removePrefix("http://").trimEnd('/')
    val merged = candidates.groupBy { it.url }.values.map { group ->
        val saved = group.firstOrNull { it.type == SuggestionType.BOOKMARK } ?: group.first()
        saved.copy(visitCount = group.maxOf { it.visitCount }, lastVisit = group.maxOf { it.lastVisit })
    }
    fun score(s: Suggestion): Long {
        val address = s.url.lowercase(java.util.Locale.ROOT).removePrefix("https://").removePrefix("http://")
        val host = address.substringBefore('/').removePrefix("www.")
        return (when {
            host == needle || address.trimEnd('/') == needle -> 10000L
            host.startsWith(needle) -> 6000L
            address.startsWith(needle) -> 4000L
            s.title.startsWith(query.trim(), true) -> 3000L
            else -> 1000L
        }) + (if (s.type == SuggestionType.BOOKMARK) 200L else 0L) +
            s.visitCount.coerceIn(0, 100) + (if (s.lastVisit > 0 && now - s.lastVisit < 86_400_000) 100L else 0L)
    }
    return merged.sortedWith(compareByDescending<Suggestion>(::score).thenByDescending { it.lastVisit }.thenBy { it.url })
}
