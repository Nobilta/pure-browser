package com.mybrowser.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object VideoGestureMath {
    fun level(start: Float, verticalFraction: Float): Float =
        (start + verticalFraction * 1.8f).coerceIn(0f, 1f)

    fun seek(start: Double, horizontalFraction: Float, duration: Double, lower: Double, upper: Double): Double {
        if (!duration.isFinite() || duration <= 0 || upper <= lower) return start
        val span = min(120.0, max(10.0, duration / 2))
        return (start + horizontalFraction * span).coerceIn(lower, upper)
    }

    fun time(seconds: Double): String {
        val total = (seconds.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0).toLong()
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)
    }

    fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100).roundToInt()
}
