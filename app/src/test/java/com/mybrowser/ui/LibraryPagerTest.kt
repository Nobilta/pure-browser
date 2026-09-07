package com.mybrowser.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPagerTest {
    @Test
    fun pagesHaveNoGapsAndStopAtEnd() = runTest {
        val rows = (1L..123L).toList()
        val offsets = mutableListOf<Int>()
        val pager = LibraryPager(this, { it: Long -> it }, StandardTestDispatcher(testScheduler)) { _, limit, offset ->
            offsets += offset
            rows.drop(offset).take(limit)
        }
        pager.refresh()
        advanceUntilIdle()
        assertEquals(rows.take(50), pager.entries)
        assertTrue(pager.hasMore)
        pager.loadMore()
        advanceUntilIdle()
        pager.loadMore()
        advanceUntilIdle()
        pager.loadMore()
        assertEquals(rows, pager.entries)
        assertEquals(listOf(0, 50, 100), offsets)
        assertFalse(pager.hasMore)
    }

    @Test
    fun rapidQueriesFetchOnlyLatestValue() = runTest {
        val queries = mutableListOf<String>()
        val pager = LibraryPager(this, { it: Long -> it }, StandardTestDispatcher(testScheduler)) { query, _, _ ->
            queries += query
            listOf(1L)
        }
        pager.search("old")
        pager.search("latest")
        advanceUntilIdle()
        assertEquals(listOf("latest"), queries)
        assertEquals(listOf(1L), pager.entries)
        assertFalse(pager.loading)
    }

    @Test
    fun supersededResultCannotReplaceNewQuery() = runTest {
        lateinit var pager: LibraryPager<Long>
        pager = LibraryPager(this, { it: Long -> it }, StandardTestDispatcher(testScheduler)) { query, _, _ ->
            if (query == "old") {
                pager.search("new")
                listOf(1L)
            } else listOf(2L)
        }
        pager.search("old")
        advanceUntilIdle()
        assertEquals("new", pager.query)
        assertEquals(listOf(2L), pager.entries)
        assertFalse(pager.loading)
    }

    @Test
    fun failedRefreshRetriesFirstPageEvenAfterLoadingMore() = runTest {
        var fail = false
        val offsets = mutableListOf<Int>()
        val pager = LibraryPager(this, { it: Long -> it }, StandardTestDispatcher(testScheduler)) { _, limit, offset ->
            offsets += offset
            check(!fail)
            (1L..120L).drop(offset).take(limit)
        }
        pager.refresh()
        advanceUntilIdle()
        pager.loadMore()
        advanceUntilIdle()
        fail = true
        pager.refresh()
        advanceUntilIdle()
        assertTrue(pager.error)
        assertEquals(100, pager.entries.size)
        fail = false
        pager.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, 50, 0, 0), offsets)
        assertEquals(50, pager.entries.size)
        assertFalse(pager.error)
    }

    @Test
    fun failedSecondPageRetriesSameOffsetAndDeduplicatesMovedRows() = runTest {
        var fail = true
        val offsets = mutableListOf<Int>()
        val pager = LibraryPager(this, { it: Long -> it }, StandardTestDispatcher(testScheduler)) { _, _, offset ->
            offsets += offset
            when (offset) {
                0 -> (1L..51L).toList()
                50 -> { check(!fail); (50L..100L).toList() }
                else -> listOf(101L)
            }
        }
        pager.refresh()
        advanceUntilIdle()
        pager.loadMore()
        advanceUntilIdle()
        assertTrue(pager.error)
        fail = false
        pager.loadMore()
        advanceUntilIdle()
        pager.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, 50, 50, 100), offsets)
        assertEquals(pager.entries.size, pager.entries.distinct().size)
        assertFalse(pager.hasMore)
    }
}
