package com.mybrowser.tabs

/** Main-thread LRU for inactive pages. The active page never counts as a cache entry. */
class RecentTabStore<T>(val capacity: Int, private val dispose: (T) -> Unit) {
    init { require(capacity in 0..2) }
    private val entries = linkedMapOf<String, T>()
    val ids: Set<String> get() = entries.keys.toSet()
    fun take(id: String): T? = entries.remove(id)
    fun put(id: String, value: T) {
        entries.remove(id)?.let(dispose)
        entries[id] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())?.let(dispose)
    }
    fun remove(id: String) { entries.remove(id)?.let(dispose) }
    fun removeWhere(predicate: (T) -> Boolean) { entries.filterValues(predicate).keys.toList().forEach(::remove) }
    fun retain(ids: Set<String>) { (entries.keys - ids).forEach(::remove) }
    fun clear() { val old = entries.values.toList(); entries.clear(); old.forEach(dispose) }
}
