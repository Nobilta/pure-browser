package com.mybrowser.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `options are ordered bounded and contain normal speed`() {
        assertEquals(PlaybackSpeed.OPTIONS.sorted(), PlaybackSpeed.OPTIONS)
        assertTrue(PlaybackSpeed.DEFAULT in PlaybackSpeed.OPTIONS)
        assertTrue(PlaybackSpeed.OPTIONS.all { it in 0.5f..3f })
    }

    @Test
    fun `selection accepts only exposed finite rates`() {
        assertEquals(1.25f, PlaybackSpeed.normalizeSelection(1.25f))
        assertNull(PlaybackSpeed.normalizeSelection(1.1f))
        assertNull(PlaybackSpeed.normalizeSelection(Float.NaN))
        assertNull(PlaybackSpeed.normalizeSelection(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `observed page rates are bounded and rounded`() {
        assertEquals(1.33f, PlaybackSpeed.sanitizeObserved(1.333f))
        assertNull(PlaybackSpeed.sanitizeObserved(0f))
        assertNull(PlaybackSpeed.sanitizeObserved(8f))
    }

    @Test
    fun `labels avoid unnecessary trailing zeroes`() {
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
        assertEquals("0.75×", PlaybackSpeed.label(0.75f))
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
    }
}
