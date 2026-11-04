package com.mybrowser.ui.library

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Negative keys identify folders; positive keys identify bookmarks. */
internal class BookmarkDragState(val list: LazyListState) {
    var order by mutableStateOf<List<Long>>(emptyList())
        private set
    var active by mutableStateOf<Long?>(null)
        private set
    var center by mutableFloatStateOf(0f)
        private set
    private var original = emptyList<Long>()
    private var direction = 0f
    private var initialIndex = 0
    private var initialOffset = 0

    fun sync(keys: List<Long>, saving: Boolean) {
        if (saving) return
        val keySet = keys.toSet()
        if (active == null) order = keys
        else if (active !in keySet) cancel(keys)
        else {
            val previous = order.toSet()
            val initial = original.toSet()
            order = order.filter { it in keySet } + keys.filter { it !in previous }
            original = original.filter { it in keySet } + keys.filter { it !in initial }
        }
    }

    fun start(key: Long): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        original = order
        initialIndex = list.firstVisibleItemIndex
        initialOffset = list.firstVisibleItemScrollOffset
        active = key
        center = item.offset + item.size / 2f
        direction = 0f
        return true
    }

    fun drag(delta: Float) {
        if (active == null) return
        center += delta
        direction = delta
        moveAtCenter()
    }

    private fun moveAtCenter() {
        val key = active ?: return
        val from = order.indexOf(key)
        val target = list.layoutInfo.visibleItemsInfo.lastOrNull { item ->
            val candidate = item.key as? Long
            candidate != null && candidate != key && (candidate < 0) == (key < 0) &&
                item.index == order.indexOf(candidate) &&
                if (direction > 0) item.index > from && center > item.offset + item.size / 2f
                else item.index < from && center < item.offset + item.size / 2f
        } ?: return
        val to = order.indexOf(target.key)
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        order = order.toMutableList().apply { removeAt(from); add(to, key) }
    }

    fun translation(key: Long): Float {
        if (active != key) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return center - item.offset - item.size / 2f
    }

    suspend fun scrollAtEdge(edge: Float, loadMore: () -> Unit) {
        while (active != null) {
            val info = list.layoutInfo
            val speed = when {
                center < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - center) / edge).coerceIn(0f, 1f)
                center > info.viewportEndOffset - edge -> ((center - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) {
                direction = speed
                val key = active ?: break
                val peers = order.filter { (it < 0) == (key < 0) }
                // Do not scroll a folder into the bookmark section (or a bookmark
                // above its own section), which would dispose its pointer target.
                if (speed < 0 && key == peers.firstOrNull() || speed > 0 && key < 0 && key == peers.lastOrNull()) {
                    delay(16)
                    continue
                }
                // Only bookmarks can load another page; folder order stays within its group.
                if (speed > 0 && (active ?: 0) > 0 &&
                    (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= order.size - 3) loadMore()
                list.scrollBy(speed * edge / 5f)
                moveAtCenter()
            }
            delay(16)
        }
    }

    fun finish(): Move? {
        val key = active ?: return null
        active = null
        if (order == original) return null
        val peers = order.filter { (it < 0) == (key < 0) }
        val index = peers.indexOf(key)
        return if (index > 0) Move(key, peers[index - 1], true)
        else peers.getOrNull(1)?.let { Move(key, it, false) }
    }

    fun cancel(keys: List<Long> = original) {
        if (active != null) list.requestScrollToItem(initialIndex, initialOffset)
        active = null
        order = keys
    }
    data class Move(val key: Long, val anchor: Long, val after: Boolean)
}
