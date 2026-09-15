package com.mybrowser.tabs

import org.junit.Assert.*
import org.junit.Test

class RecentTabStoreTest {
    @Test fun aSwitchTakesItsTargetBeforeParkingTheDepartingPage() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(disposed::add)
        pages.put("a", 1)
        assertEquals(1, pages.take("a"))
        pages.put("b", 2)
        assertTrue(disposed.isEmpty())
        pages.put("c", 3)
        // Parking a second page must not evict the first; only pressure does that.
        assertEquals(setOf("b", "c"), pages.ids)
        assertTrue(disposed.isEmpty())
        pages.retain(setOf("other"))
        assertEquals(listOf(2, 3), disposed)
        pages.clear(); assertEquals(2, disposed.size)
    }

    @Test fun everyOpenedTabKeepsItsPageUntilSomethingEvictsIt() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(disposed::add)
        repeat(8) { pages.put("tab$it", it) }
        assertEquals(8, pages.size)
        assertTrue(disposed.isEmpty())
    }

    @Test fun evictOldestFreesTheLeastRecentlyParkedPageFirst() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(disposed::add)
        pages.put("a", 1); pages.put("b", 2); pages.put("c", 3)
        assertTrue(pages.evictOldest()); assertEquals(listOf(1), disposed)
        assertTrue(pages.evictOldest()); assertEquals(listOf(1, 2), disposed)
        assertTrue(pages.evictOldest()); assertEquals(listOf(1, 2, 3), disposed)
        assertFalse(pages.evictOldest())
        assertEquals(0, pages.size)
    }

    @Test fun takingAPageAndParkingItAgainMovesItBehindTheOthers() {
        val disposed = mutableListOf<Int>()
        val pages = RecentTabStore<Int>(disposed::add)
        pages.put("a", 1); pages.put("b", 2)
        assertEquals(1, pages.take("a"))
        pages.put("a", 1)
        assertTrue(pages.evictOldest())
        assertEquals(listOf(2), disposed)
        assertEquals(setOf("a"), pages.ids)
    }
}
