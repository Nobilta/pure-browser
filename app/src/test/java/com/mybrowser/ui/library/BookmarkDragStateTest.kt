package com.mybrowser.ui.library

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.*
import org.junit.Test

class BookmarkDragStateTest {
    private fun rows(keys: List<Long>) = keys.mapIndexed { index, key ->
        object : LazyListItemInfo {
            override val index = index
            override val key: Any = key
            override val offset = index * 100
            override val size = 100
            override val contentType: Any? = null
        }
    }

    @Test fun onePointerEventCanCrossSeveralRowsInEitherDirection() {
        val order = (1L..8L).toList()
        assertEquals(4L, bookmarkDragTarget(order, 7L, 320f, -330f, rows(order)))
        assertEquals(5L, bookmarkDragTarget(order, 2L, 480f, 330f, rows(order)))
        assertNull(bookmarkDragTarget(order, 7L, 640f, -10f, rows(order)))
    }

    @Test fun fastDragStaysWithinItsGroupAndIgnoresStalePositions() {
        val order = listOf(-1L, -2L, -3L, 1L, 2L, 3L)
        assertEquals(-3L, bookmarkDragTarget(order, -1L, 550f, 500f, rows(order)))
        assertEquals(1L, bookmarkDragTarget(order, 3L, 0f, -550f, rows(order)))
        val reordered = listOf(-1L, -2L, -3L, 2L, 1L, 3L)
        assertNull(bookmarkDragTarget(reordered, 3L, 320f, -230f, rows(order)))
    }
}
