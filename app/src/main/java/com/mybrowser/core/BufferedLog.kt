package com.mybrowser.core

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Constant-space ring. Appending never shifts or copies the retained history. */
internal class LogRing<T>(private val capacity: Int) {
    private val values = arrayOfNulls<Any?>(capacity.also { require(it > 0) })
    private var start = 0
    var size: Int = 0
        private set
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T = values[(start + index) % capacity] as T
    operator fun set(index: Int, value: T) { values[(start + index) % capacity] = value }
    fun add(value: T) {
        if (size == capacity) { values[start] = value; start = (start + 1) % capacity }
        else { values[(start + size) % capacity] = value; size++ }
    }
    fun clear() { values.fill(null); start = 0; size = 0 }
    fun indexOfLast(predicate: (T) -> Boolean): Int {
        for (i in size - 1 downTo 0) if (predicate(get(i))) return i
        return -1
    }
    fun snapshot(): List<T> = List(size, ::get)
}

/** Recent logs exist while hidden; UI snapshots are built at most every 150 ms while visible. */
internal class BufferedLog<T>(capacity: Int) {
    private val ring = LogRing<T>(capacity)
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow<List<T>>(emptyList())
    val entries = mutable.asStateFlow()
    private var visible = false
    private var scheduled = false
    private val publish = Runnable { synchronized(lock) {
        scheduled = false
        if (visible) mutable.value = ring.snapshot()
    } }

    fun <R> edit(action: (LogRing<T>) -> R): R = synchronized(lock) {
        val result = action(ring)
        if (visible && !scheduled) { scheduled = true; handler.postDelayed(publish, 150) }
        result
    }
    fun setVisible(value: Boolean) = synchronized(lock) {
        visible = value
        handler.removeCallbacks(publish)
        scheduled = false
        if (visible) mutable.value = ring.snapshot()
    }
    fun clear() = synchronized(lock) { ring.clear(); mutable.value = emptyList() }
    fun snapshot(): List<T> = synchronized(lock) { ring.snapshot() }
}
