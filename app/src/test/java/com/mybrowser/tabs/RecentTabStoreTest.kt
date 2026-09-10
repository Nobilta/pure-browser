package com.mybrowser.tabs

import org.junit.Assert.*
import org.junit.Test

class RecentTabStoreTest {
    @Test fun aSwitchTakesItsTargetBeforeParkingTheDepartingPage() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(1, disposed::add)
        pages.put("a", 1)
        assertEquals(1, pages.take("a"))
        pages.put("b", 2)
        assertTrue(disposed.isEmpty())
        pages.put("c", 3)
        assertEquals(listOf(2), disposed)
        pages.retain(setOf("other"))
        assertEquals(listOf(2, 3), disposed)
        pages.clear(); assertEquals(2, disposed.size)
    }

    @Test fun lowMemoryBudgetNeverRetainsAnInactiveView() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(0, disposed::add)
        pages.put("a", 1)
        assertTrue(pages.ids.isEmpty()); assertEquals(listOf(1), disposed)
    }
}
