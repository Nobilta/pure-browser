package com.mybrowser.ui.shell

import com.mybrowser.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mybrowser.core.UrlUtils
import com.mybrowser.core.WebLinkExtractor
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchSuggestionProvider
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** What clicking a suggestion row does; "search this exact text" no longer re-guesses. */
sealed interface SuggestionAction {
    data class Visit(val url: String) : SuggestionAction
    data class Search(val query: String) : SuggestionAction
}

data class Suggestion(
    val title: String,
    val url: String,
    val type: SuggestionType,
    val action: SuggestionAction,
    val visitCount: Int = 0,
    val lastVisit: Long = 0,
    val faviconUrl: String? = null
) {
    /** Stable identity for dedup and LazyColumn keys. */
    val key: String
        get() = when (val a = action) {
            is SuggestionAction.Visit -> "v:${a.url}"
            is SuggestionAction.Search -> "s:${a.query}"
        }

    /** Text the fill button drops into the omnibar. */
    val fillText: String
        get() = when (val a = action) {
            is SuggestionAction.Visit -> a.url
            is SuggestionAction.Search -> a.query
        }
}

enum class SuggestionType {
    BOOKMARK,
    HISTORY,
    SEARCH,
    /** An HTTP(S) link extracted from pasted text. */
    LINK,
    /** A keyword suggestion returned by the current search engine. */
    SEARCH_SUGGESTION
}

@Composable
fun SmartSuggestions(
    query: String,
    bookmarkManager: BookmarkManager,
    historyManager: HistoryManager?,
    searchEngine: SearchEngine,
    onlineSuggestionsEnabled: Boolean,
    suggestionProvider: SearchSuggestionProvider?,
    onSuggestionAction: (SuggestionAction) -> Unit,
    onFillSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
    isIncognito: Boolean = false,
    hasComposingText: Boolean = false,
    maxHeight: androidx.compose.ui.unit.Dp = 400.dp,
) {
    val textResources = localizedResources()
    // Each source updates independently so local rows never wait on the network.
    var extractedLinks by remember { mutableStateOf<List<String>>(emptyList()) }
    // The query whose link scan has completed. A null/other value means "not scanned yet",
    // which is different from "scanned and found no links" and must never allow a network send.
    var linksScannedFor by remember { mutableStateOf<String?>(null) }
    var localSuggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var onlineTerms by remember { mutableStateOf<List<String>>(emptyList()) }

    // Local pass: links, bookmarks and (outside incognito) history.
    LaunchedEffect(query, bookmarkManager, historyManager) {
        extractedLinks = emptyList()
        linksScannedFor = null
        localSuggestions = emptyList()
        if (query.isBlank()) return@LaunchedEffect

        delay(150) // Debounce
        val links = withContext(Dispatchers.IO) { WebLinkExtractor.extractWebLinks(query) }
        extractedLinks = links
        linksScannedFor = query
        val local = withContext(Dispatchers.IO) {
            val found = mutableListOf<Suggestion>()
            runCatching { bookmarkManager.suggestions(query) }
                .getOrDefault(emptyList())
                .forEach { bookmark ->
                    found += Suggestion(
                        title = bookmark.title,
                        url = bookmark.url,
                        type = SuggestionType.BOOKMARK,
                        action = SuggestionAction.Visit(bookmark.url),
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
                        action = SuggestionAction.Visit(entry.url),
                        visitCount = entry.visitCount,
                        lastVisit = entry.visitTime,
                    )
                }
            found
        }
        localSuggestions = rankSuggestions(query, local)
    }

    // Online pass: the current engine's keyword suggestions. Restarts (and cancels) on
    // every query, engine, switch or mode change, and keeps its rows empty while the IME
    // is still composing so half-typed pinyin never becomes a suggestion.
    LaunchedEffect(query, searchEngine.id, onlineSuggestionsEnabled, suggestionProvider, hasComposingText, isIncognito, extractedLinks, linksScannedFor) {
        onlineTerms = emptyList()
        if (!onlineSuggestionsEnabled || suggestionProvider == null || hasComposingText) return@LaunchedEffect

        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) return@LaunchedEffect
        // Wait for the scan of this exact query; an empty list from the previous query (or
        // before the scan starts) is not proof that the pasted text contains no link.
        if (linksScannedFor != query) return@LaunchedEffect
        // A URL-shaped input, or mixed text that already contains a link, must not be
        // shipped to the search engine as a keyword. The link extraction already ran on
        // Dispatchers.IO for the local rows; reuse its result instead of parsing the text
        // again on the main thread.
        if (UrlUtils.isNavigableInput(query)) return@LaunchedEffect
        if (extractedLinks.isNotEmpty()) return@LaunchedEffect
        // Belt and braces for a scheme the extractor could not turn into a valid link: never
        // ship text that still visibly contains a URL scheme to the suggestion service.
        if (query.contains("http://", ignoreCase = true) || query.contains("https://", ignoreCase = true)) {
            return@LaunchedEffect
        }

        delay(250) // Debounce
        val engine = searchEngine
        val terms = suggestionProvider.fetch(engine, trimmed, isIncognito)
        // Cancellation disconnects the old request; also cover the window after resume.
        currentCoroutineContext().ensureActive()
        onlineTerms = terms
    }

    val suggestions = if (query.isBlank()) emptyList() else assembleSuggestions(
        searchTitle = textResources.getString(R.string.ui_search, query.trim()),
        query = query,
        links = extractedLinks,
        onlineTerms = onlineTerms,
        localRanked = localSuggestions,
        linkTitle = { url -> textResources.getString(R.string.suggestion_visit_site, UrlUtils.hostOf(url) ?: url) },
    )

    if (suggestions.isNotEmpty()) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .border(
                    1.dp,
                    BrowserColors.cardBorder,
                    MaterialTheme.shapes.large,
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight)
            ) {
                items(suggestions, key = { it.key }) { suggestion ->
                    SuggestionItem(
                        suggestion = suggestion,
                        onClick = { onSuggestionAction(suggestion.action) },
                        onFill = { onFillSuggestion(suggestion.fillText) }
                    )
                    if (suggestion != suggestions.last()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
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
                        SuggestionType.BOOKMARK, SuggestionType.LINK -> MaterialTheme.colorScheme.primaryContainer
                        SuggestionType.HISTORY -> MaterialTheme.colorScheme.secondaryContainer
                        SuggestionType.SEARCH, SuggestionType.SEARCH_SUGGESTION -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    when (suggestion.type) {
                        SuggestionType.BOOKMARK -> R.drawable.ic_ui_bookmark
                        SuggestionType.HISTORY -> R.drawable.ic_ui_history
                        SuggestionType.LINK -> R.drawable.ic_ui_open_in_new
                        else -> R.drawable.ic_ui_search
                    },
                ),
                contentDescription = null,
                tint = when (suggestion.type) {
                    SuggestionType.BOOKMARK, SuggestionType.LINK -> MaterialTheme.colorScheme.onPrimaryContainer
                    SuggestionType.HISTORY -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
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
            // LINK rows show the full target so the user sees where a click lands.
            if (suggestion.url.isNotEmpty() &&
                suggestion.type != SuggestionType.SEARCH && suggestion.type != SuggestionType.SEARCH_SUGGESTION
            ) {
                Text(
                    text = suggestion.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        IconButton(onClick = onFill) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_ui_forward),
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

/**
 * Merges all sources into the final rows: links first, then the explicit search row,
 * then online suggestions (capped), then local matches — at most [maxRows] total.
 * Duplicate actions across sources collapse to their first occurrence.
 */
internal fun assembleSuggestions(
    searchTitle: String,
    query: String,
    links: List<String>,
    onlineTerms: List<String>,
    localRanked: List<Suggestion>,
    linkTitle: (String) -> String,
    maxRows: Int = 8,
    maxOnline: Int = 5,
): List<Suggestion> {
    val rows = mutableListOf<Suggestion>()
    val seen = HashSet<String>()
    fun add(suggestion: Suggestion): Boolean {
        if (!seen.add(suggestion.key)) return false
        rows += suggestion
        return true
    }

    links.take(WebLinkExtractor.MAX_LINKS).forEach { url ->
        add(Suggestion(title = linkTitle(url), url = url, type = SuggestionType.LINK, action = SuggestionAction.Visit(url)))
    }
    // Always offer searching the full input, even when it looks like a URL: the user may
    // genuinely want to search for "react.js" or a domain name.
    add(Suggestion(title = searchTitle, url = "", type = SuggestionType.SEARCH, action = SuggestionAction.Search(query.trim())))

    var onlineCount = 0
    for (term in onlineTerms) {
        if (rows.size >= maxRows || onlineCount >= maxOnline) break
        val clean = term.trim()
        if (clean.isEmpty() || clean == query.trim()) continue
        if (add(Suggestion(title = clean, url = "", type = SuggestionType.SEARCH_SUGGESTION, action = SuggestionAction.Search(clean)))) {
            onlineCount++
        }
    }
    for (local in localRanked) {
        if (rows.size >= maxRows) break
        add(local)
    }
    return rows.take(maxRows)
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
