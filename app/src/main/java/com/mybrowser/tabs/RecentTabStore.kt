package com.mybrowser.tabs

/**
 * Main-thread store for inactive pages. The active page never counts as an entry.
 *
 * Retention is deliberately unbounded: how much memory parked pages cost should follow how
 * many tabs the user actually opened, not a fixed slot count. Memory is bounded by eviction
 * instead — [evictOldest] drops the least recently parked page, which the owner calls when the
 * allocator runs out or the system reports pressure. `take`/`put` refresh an entry's position,
 * so the order is least-recently-parked first.
 */
class RecentTabStore<T>(private val dispose: (T) -> Unit) {
    private val entries = linkedMapOf<String, T>()

    val ids: Set<String> get() = entries.keys.toSet()
    val size: Int get() = entries.size

    fun take(id: String): T? = entries.remove(id)

    fun put(id: String, value: T) {
        entries.remove(id)?.let(dispose)
        entries[id] = value
    }

    fun remove(id: String) { entries.remove(id)?.let(dispose) }

    fun removeWhere(predicate: (T) -> Boolean) { entries.filterValues(predicate).keys.toList().forEach(::remove) }

    fun retain(ids: Set<String>) { (entries.keys - ids).forEach(::remove) }

    fun clear() { val old = entries.values.toList(); entries.clear(); old.forEach(dispose) }

    /** Drops the least recently parked page. False when there was nothing left to free. */
    fun evictOldest(): Boolean {
        val id = entries.keys.firstOrNull() ?: return false
        entries.remove(id)?.let(dispose)
        return true
    }
}
