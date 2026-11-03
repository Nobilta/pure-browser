package com.mybrowser.devtools

/**
 * One place for every collection budget.
 *
 * Units are explicit because the two are not interchangeable: network bodies are bounded by
 * UTF-8 bytes on the wire, while everything the WebView hands back to Kotlin (JS strings, DOM
 * text, bridge messages) is UTF-16 characters. Cache accounting uses bytes so a body of CJK
 * text is not silently counted as its character count.
 */
internal object DevToolsBudgets {
    /** Requests retained for the document on screen. Older records release their bodies. */
    const val NETWORK_ENTRIES_PER_DOCUMENT = 500

    /** Request and response preview, measured in UTF-8 bytes on the wire. */
    const val BODY_PREVIEW_BYTES = 256 * 1024

    /** Hard cap on retained preview text, measured in UTF-16 characters. */
    const val BODY_PREVIEW_CHARS = 256 * 1024

    /** All body previews kept for one tab, across documents, measured in UTF-8 bytes. */
    const val BODY_CACHE_BYTES_PER_TAB = 8 * 1024 * 1024

    /** Largest bridge message accepted from the page, including JSON encoding overhead. */
    const val BRIDGE_MESSAGE_CHARS = 64 * 1024

    /** One batch of DOM children. */
    const val DOM_CHILDREN_PER_PAGE = 100

    /** Cached DOM nodes before the oldest entries are dropped from the page-side map. */
    const val DOM_NODE_CACHE = 20_000

    const val DOM_TEXT_PREVIEW_CHARS = 2_048
    const val DOM_HTML_COPY_CHARS = 64 * 1024

    /** One source page: bounded independently by characters and by lines. */
    const val SOURCE_PAGE_CHARS = 64 * 1024
    const val SOURCE_PAGE_LINES = 1_000

    /** Storage listing limits; the full value is read on demand. */
    const val STORAGE_ITEMS = 500
    const val STORAGE_VALUE_PREVIEW_CHARS = 64 * 1024
    const val STORAGE_TOTAL_PREVIEW_CHARS = 1024 * 1024
    const val STORAGE_VALUE_READ_CHARS = 64 * 1024

    const val HEADER_COUNT = 32
    const val HEADER_VALUE_CHARS = 1_024
    const val URL_CHARS = 8_192
}
