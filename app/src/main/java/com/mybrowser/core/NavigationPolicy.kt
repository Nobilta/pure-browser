package com.mybrowser.core

import com.mybrowser.search.SearchEngine
import com.mybrowser.search.UrlOrSearch

/**
 * The result of submitting text from the omnibar.
 *
 * Keeping this decision separate from [MainActivity] makes the security boundary explicit:
 * only HTTP(S), `about:` and the small external-scheme allow-list can become a direct
 * navigation. Everything else is converted to a search URL, even if a native helper returns
 * an opaque scheme unchanged after a library upgrade.
 */
sealed interface NavigationTarget {
    data class Load(val url: String) : NavigationTarget
    data class External(val url: String) : NavigationTarget
}

/** Pure URL/search routing shared by the visible action and the imperative WebView host. */
object NavigationPolicy {

    fun resolve(input: String, searchEngine: SearchEngine): NavigationTarget {
        val normalized = UrlOrSearch.resolve(input, searchEngine)
        val directInput = UrlUtils.isNavigableInput(input)
        val scheme = UrlUtils.schemeOf(normalized)

        return when {
            directInput && (scheme == "http" || scheme == "https" || scheme == "about") ->
                NavigationTarget.Load(normalized)

            directInput && UrlUtils.isAllowedExternalScheme(normalized) ->
                NavigationTarget.External(normalized)

            // Do not trust a native normalizer with an unknown opaque scheme. This second
            // construction is deliberately the safe branch and cannot execute javascript:
            // or hand a page-controlled custom protocol to another application.
            else -> NavigationTarget.Load(searchEngine.buildSearchUrl(input.trim()))
        }
    }
}
