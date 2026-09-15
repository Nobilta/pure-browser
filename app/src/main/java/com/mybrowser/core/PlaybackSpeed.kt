package com.mybrowser.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Supported browser-controlled HTML5 video playback rates and their stable labels.
 *
 * Lives in `core` because the rate vocabulary is shared: the persisted preferences need the
 * allowed set and its normalisation, while playback and the settings UI need the labels. With
 * it in `media`, the data layer had to depend on the playback layer even though the playback
 * layer already depends on the data layer.
 */
object PlaybackSpeed {
    const val DEFAULT = 1f
    private const val MIN_OBSERVED = 0.25f
    private const val MAX_OBSERVED = 4f
    private const val EPSILON = 0.001f

    val OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

    /** Only rates exposed by the browser UI may cross into page JavaScript. */
    fun normalizeSelection(value: Float): Float? {
        if (!value.isFinite()) return null
        return OPTIONS.firstOrNull { equivalent(it, value) }
    }

    /** A page may report a custom rate; keep it bounded and display it to two decimals. */
    fun sanitizeObserved(value: Float): Float? {
        if (!value.isFinite() || value !in MIN_OBSERVED..MAX_OBSERVED) return null
        return (value * 100f).roundToInt() / 100f
    }

    fun equivalent(first: Float, second: Float): Boolean = abs(first - second) < EPSILON

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
