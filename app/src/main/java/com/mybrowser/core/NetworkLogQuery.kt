package com.mybrowser.core

import com.mybrowser.R

internal enum class NetworkCategory(val label: Int) {
    ALL(R.string.dev_network_all), DOCUMENT(R.string.dev_network_document), FETCH(R.string.dev_network_fetch),
    SCRIPT(R.string.dev_network_script), STYLE(R.string.dev_network_style), IMAGE(R.string.dev_network_image),
    MEDIA(R.string.dev_network_media), FONT(R.string.dev_network_font), OTHER(R.string.dev_network_other);

    companion object {
        fun of(entry: NetworkRequestLog): NetworkCategory = if (entry.isForMainFrame) DOCUMENT else when (
            ResourceType.entries.getOrNull(entry.resourceType)
        ) {
            ResourceType.DOCUMENT, ResourceType.SUBDOCUMENT -> DOCUMENT
            ResourceType.XML_HTTP_REQUEST -> FETCH
            ResourceType.SCRIPT -> SCRIPT
            ResourceType.STYLESHEET -> STYLE
            ResourceType.IMAGE -> IMAGE
            ResourceType.MEDIA -> MEDIA
            ResourceType.FONT -> FONT
            else -> OTHER
        }
    }
}

internal enum class NetworkOutcome(val label: Int) {
    ALL(R.string.dev_network_any_status), BLOCKED(R.string.dev_network_blocked), FAILED(R.string.dev_network_failed);

    fun matches(entry: NetworkRequestLog): Boolean = when (this) {
        ALL -> true
        BLOCKED -> entry.blocked
        FAILED -> !entry.blocked && (entry.errorCode != null || (entry.statusCode ?: 0) >= 400)
    }
}

/** Literal, case-insensitive AND search; user text is never interpreted as a regex. */
internal class NetworkLogQuery(query: String) {
    private val terms = query.take(256).trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    fun matches(entry: NetworkRequestLog, categoryLabel: String, statusLabel: String): Boolean = terms.all { term ->
        entry.url.contains(term, true) || entry.method.contains(term, true) ||
            categoryLabel.contains(term, true) || statusLabel.contains(term, true) ||
            entry.statusCode?.toString()?.contains(term) == true ||
            entry.errorCode?.toString()?.contains(term) == true ||
            entry.errorDescription?.contains(term, true) == true
    }
}
