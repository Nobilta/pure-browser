package com.mybrowser.search

import com.mybrowser.R
import android.content.res.Resources
import android.net.Uri

/**
 * Represents a search engine with its name, URL template, and optional icon.
 */
data class SearchEngine(
    val id: String,
    val name: String,
    val searchUrlTemplate: String,  // Template with {query} placeholder
    val suggestUrl: String? = null,
    val isCustom: Boolean = false,
) {
    /**
     * Builds the search URL for the given query.
     */
    fun buildSearchUrl(query: String): String {
        val encoded = Uri.encode(query)
        return searchUrlTemplate
            .replace("{query}", encoded)
            .replace("%s", encoded)
    }

    fun displayName(resources: Resources): String =
        if (!isCustom && id == "baidu") resources.getString(R.string.ui_baidu) else name

    companion object {
        // Built-in search engines
        val BAIDU = SearchEngine(
            id = "baidu",
            name = "Baidu",
            searchUrlTemplate = "https://www.baidu.com/s?wd={query}",
        )

        val GOOGLE = SearchEngine(
            id = "google",
            name = "Google",
            searchUrlTemplate = "https://www.google.com/search?q={query}",
        )

        val BING = SearchEngine(
            id = "bing",
            name = "Bing",
            searchUrlTemplate = "https://www.bing.com/search?q={query}",
        )

        val DUCKDUCKGO = SearchEngine(
            id = "duckduckgo",
            name = "DuckDuckGo",
            searchUrlTemplate = "https://duckduckgo.com/?q={query}",
        )

        val BUILTIN_ENGINES = listOf(BAIDU, GOOGLE, BING, DUCKDUCKGO)
    }
}
