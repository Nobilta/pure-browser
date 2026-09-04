package com.mybrowser.ui

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
import androidx.compose.ui.graphics.Color
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
    historyManager: HistoryManager,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }

        delay(150) // Debounce
        val results = withContext(Dispatchers.IO) {
            val found = mutableListOf<Suggestion>()
            runCatching { bookmarkManager.searchBookmarks(query) }
                .getOrDefault(emptyList())
                .forEach { bookmark ->
                    found += Suggestion(
                        title = bookmark.title,
                        url = bookmark.url,
                        type = SuggestionType.BOOKMARK,
                        faviconUrl = bookmark.faviconUrl,
                    )
                }
            runCatching { historyManager.searchHistory(query, limit = 20) }
                .getOrDefault(emptyList())
                .forEach { entry ->
                    found += Suggestion(
                        title = entry.title,
                        url = entry.url,
                        type = SuggestionType.HISTORY,
                        visitCount = entry.visitCount,
                    )
                }
            found
        }.toMutableList()

        // Keep a search action available for ordinary text, while letting host-like input
        // resolve through the same URL heuristic as the omnibar.
        if (!query.contains("://") && !query.contains(".") && !query.contains(" ")) {
            results += Suggestion(
                title = "搜索: $query",
                url = query,
                type = SuggestionType.SEARCH,
            )
        }

        suggestions = results
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<Suggestion> { it.type == SuggestionType.BOOKMARK }
                    .thenByDescending { it.visitCount },
            )
            .take(8)
    }

    if (suggestions.isNotEmpty()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(suggestions) { suggestion ->
                    SuggestionItem(
                        suggestion = suggestion,
                        onClick = { onSuggestionClick(suggestion.url) }
                    )
                    if (suggestion != suggestions.last()) {
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    suggestion: Suggestion,
    onClick: () -> Unit
) {
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
                        SuggestionType.BOOKMARK -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                        SuggestionType.HISTORY -> Color(0xFF2196F3).copy(alpha = 0.1f)
                        SuggestionType.SEARCH -> Color(0xFFFF9800).copy(alpha = 0.1f)
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
                    SuggestionType.BOOKMARK -> Color(0xFF4CAF50)
                    SuggestionType.HISTORY -> Color(0xFF2196F3)
                    SuggestionType.SEARCH -> Color(0xFFFF9800)
                },
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and URL
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (suggestion.type != SuggestionType.SEARCH) {
                Text(
                    text = suggestion.url,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        // Visit count badge for history
        if (suggestion.type == SuggestionType.HISTORY && suggestion.visitCount > 1) {
            Surface(
                color = Color(0xFF2196F3).copy(alpha = 0.1f),
                shape = CircleShape
            ) {
                Text(
                    text = "${suggestion.visitCount}",
                    fontSize = 10.sp,
                    color = Color(0xFF2196F3),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
