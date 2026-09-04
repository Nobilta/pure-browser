package com.mybrowser.search

import com.mybrowser.core.UrlUtils

/**
 * Determines whether user input is a URL or a search query.
 */
object UrlOrSearch {
    /**
     * Returns a URL to navigate to: either the input itself if it's a valid URL,
     * or a search URL if it's a search query.
     */
    fun resolve(input: String, searchEngine: SearchEngine): String {
        // Keep one URL-vs-search decision in the application.  The old implementation
        // used Patterns.WEB_URL here while the Rust/Kotlin UrlUtils implementation used a
        // different heuristic, so the same text could navigate differently depending on
        // which caller reached it.  SearchEngine templates are normalised to the `%s`
        // placeholder expected by UrlUtils; custom engines may use either spelling.
        val template = searchEngine.searchUrlTemplate
            .replace("{query}", "%s")
            .ifBlank { SearchEngine.BAIDU.searchUrlTemplate.replace("{query}", "%s") }
        return UrlUtils.normalizeOrSearch(input, template)
    }
}
