package com.mybrowser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangesTest {

    @Test
    fun `small files do not waste connections`() {
        assertEquals(1, DownloadRanges.effectiveThreadCount(512 * 1024L, 16))
        assertEquals(4, DownloadRanges.effectiveThreadCount(4 * 1024 * 1024L, 8))
    }

    @Test
    fun `split covers every byte exactly once`() {
        val ranges = DownloadRanges.split(10L, 3)

        assertEquals(listOf(0L..3L, 4L..6L, 7L..9L), ranges)
        assertEquals(10L, ranges.sumOf { it.last - it.first + 1L })
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.last + 1L, right.first)
        }
    }

    @Test
    fun `range calculation keeps long file sizes intact`() {
        val total = Int.MAX_VALUE.toLong() * 3L
        val ranges = DownloadRanges.split(total, 16)

        assertEquals(16, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(total - 1L, ranges.last().last)
        assertTrue(ranges.all { !it.isEmpty() })
        assertEquals(total, ranges.sumOf { it.last - it.first + 1L })
    }
}
