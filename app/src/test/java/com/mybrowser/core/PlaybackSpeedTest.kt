package com.mybrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PlaybackSpeedTest {

    @Test
    fun `options are ordered bounded on the shared grid and contain normal speed`() {
        assertEquals(PlaybackSpeed.OPTIONS.sorted(), PlaybackSpeed.OPTIONS)
        assertTrue(PlaybackSpeed.DEFAULT in PlaybackSpeed.OPTIONS)
        assertTrue(PlaybackSpeed.OPTIONS.all { it in PlaybackSpeed.MIN..PlaybackSpeed.MAX })
        // A listed rate must survive normalisation unchanged, or the settings list would offer a
        // label it cannot store.
        assertTrue(PlaybackSpeed.OPTIONS.all { PlaybackSpeed.normalizeSelection(it) == it })
        // The slider's ticks must land exactly on the stored grid, or a release would commit a
        // value it cannot hold.
        assertEquals(PlaybackSpeed.STEPS + 1, ((PlaybackSpeed.MAX - PlaybackSpeed.MIN) / PlaybackSpeed.STEP).roundToInt())
    }

    @Test
    fun `selection accepts the continuous range and rejects what cannot be played`() {
        assertEquals(1.1f, PlaybackSpeed.normalizeSelection(1.1f))
        assertEquals(1.1f, PlaybackSpeed.normalizeSelection(1.13f))
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.normalizeSelection(5f))
        assertNull(PlaybackSpeed.normalizeSelection(PlaybackSpeed.MIN - 0.1f))
        assertNull(PlaybackSpeed.normalizeSelection(PlaybackSpeed.MAX + 0.1f))
        assertNull(PlaybackSpeed.normalizeSelection(Float.NaN))
        assertNull(PlaybackSpeed.normalizeSelection(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `observed page rates are bounded and sit on the same grid`() {
        assertEquals(1.3f, PlaybackSpeed.sanitizeObserved(1.333f))
        assertEquals(4.4f, PlaybackSpeed.sanitizeObserved(4.4f))
        assertNull(PlaybackSpeed.sanitizeObserved(0f))
        assertNull(PlaybackSpeed.sanitizeObserved(PlaybackSpeed.MAX + 1f))
    }

    @Test
    fun `a rate on the wire sits exactly on the grid the page checks`() {
        // Pinning a float into a double carries its rounding error (4.7f -> 4.699999809265137),
        // and the page rejects anything that is not a tenth. Every slider stop must survive.
        for (tenths in 5..50) {
            val wire = PlaybackSpeed.wireRate(tenths / 10f)
            org.junit.Assert.assertEquals("rate ${tenths / 10f}", 0.0, wire * 10 - tenths, 1e-9)
        }
    }

    @Test
    fun `labels avoid unnecessary trailing zeroes`() {
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
        assertEquals("2.5×", PlaybackSpeed.label(2.5f))
        assertEquals("0.5×", PlaybackSpeed.label(0.5f))
    }
}
