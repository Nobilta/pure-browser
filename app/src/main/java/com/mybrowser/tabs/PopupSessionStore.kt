package com.mybrowser.tabs

/**
 * Live JavaScript windows, separate from openerTabId (also used for manual new tabs).
 * Both ends remain resident, including private and still-loading pages. The tab limit
 * bounds the entries. A background opener whose last popup closed stays live until
 * selected: its message handler may still be completing a fetch. Selecting an unlinked
 * page ends this special retention; ordinary parking applies on its next departure.
 */
class PopupSessionStore<T> {
    private val pages = linkedMapOf<String, T>()
    private val openers = mutableMapOf<String, String>()

    operator fun get(id: String?): T? = pages[id]
    fun idOf(predicate: (T) -> Boolean): String? = pages.entries.firstOrNull { predicate(it.value) }?.key

    fun connect(openerId: String, opener: T, popupId: String, popup: T) {
        require(openerId != popupId)
        pages[openerId] = opener
        pages[popupId] = popup
        openers[popupId] = openerId
    }

    fun remove(id: String): T? {
        openers.remove(id)
        openers.entries.removeAll { it.value == id }
        return pages.remove(id)
    }

    /** Transfers an unlinked foreground instance back to ordinary tab ownership. */
    fun releaseIfUnlinked(id: String): T? =
        if (id !in openers && id !in openers.values) pages.remove(id) else null

    fun clear(): List<T> = pages.values.toList().also { pages.clear(); openers.clear() }

    fun retain(ids: Set<String>): List<T> = (pages.keys - ids).mapNotNull(::remove)
}
