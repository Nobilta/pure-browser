package com.mybrowser.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Supported browser-controlled HTML5 video playback rates and their stable labels.
 *
 * Lives in `core` because the rate vocabulary is shared: the persisted preferences need the
 * allowed range and its normalisation, while playback and the settings UI need the labels. With
 * it in `media`, the data layer had to depend on the playback layer even though the playback
 * layer already depends on the data layer.
 *
 * Rates are continuous between [MIN] and [MAX] on a [STEP] grid, so the player's slider, the
 * persisted preferences and the page command guard all speak the same vocabulary; [OPTIONS],
 * [STEPS] and [BOOST_OPTIONS] only name the shortcuts the UI still offers as lists.
 */
object PlaybackSpeed {
    const val DEFAULT = 1f
    const val MIN = 0.5f
    const val MAX = 5f
    const val STEP = 0.1f
    private const val MIN_OBSERVED = 0.25f
    private const val EPSILON = 0.001f

    /** Quick picks for the settings list; the player's panel drives a slider instead. */
    val OPTIONS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)

    /** Tick marks between the slider's ends: one per [STEP] across the range. */
    val STEPS: Int = ((MAX - MIN) / STEP).roundToInt() - 1

    /** Hold-to-boost starting rates the settings offer; dragging up from one reaches [MAX]. */
    val BOOST_OPTIONS: List<Float> = listOf(2f, 3f)

    /**
     * The rate as the page receives it.
     *
     * A float cannot hold 4.7 exactly, so sending [quantize]'s value straight as a double would
     * put 4.699999809265137 on the wire and the page's grid guard would reject it. Rounding the
     * scaled integer first keeps the payload exactly on the grid the page validates against.
     */
    fun wireRate(value: Float): Double = (quantize(value) * 10f).roundToInt() / 10.0

    /** One grid for the slider, the persisted preferences and the page command guard. */
    fun quantize(value: Float): Float =
        if (value.isFinite()) (value * 10f).roundToInt() / 10f else value

    /** Only rates inside the browser's range may cross into page JavaScript. */
    fun normalizeSelection(value: Float): Float? {
        if (!value.isFinite()) return null
        val safe = quantize(value)
        return safe.takeIf { it in MIN..MAX }
    }

    /** A page may report a custom rate; keep it bounded and display it on the shared grid. */
    fun sanitizeObserved(value: Float): Float? {
        if (!value.isFinite() || value !in MIN_OBSERVED..MAX) return null
        return quantize(value)
    }

    fun equivalent(first: Float, second: Float): Boolean = abs(quantize(first) - quantize(second)) < EPSILON

    fun label(value: Float): String {
        val safe = sanitizeObserved(value) ?: DEFAULT
        val hundredths = (safe * 100f).roundToInt()
        val number = when {
            hundredths % 100 == 0 -> (hundredths / 100).toString()
            hundredths % 10 == 0 -> "${hundredths / 100}.${hundredths % 100 / 10}"
            else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
        return "$number×"
    }
}
