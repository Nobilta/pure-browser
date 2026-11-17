package com.mybrowser.reader

import android.content.Context
import androidx.core.content.edit

/**
 * The reading typography the reader applies and keeps across sessions.
 *
 * [Paper.SYSTEM] follows the app theme (white in light, near-black in dark); warm and dark are the
 * explicit choices. Size and line height stay inside the ranges the controller clamps, so the two
 * sides cannot disagree.
 */
data class ReaderStyle(
    val size: Int = DEFAULT_SIZE,
    val lineHeight: Float = DEFAULT_LINE_HEIGHT,
    val paper: Paper = Paper.SYSTEM,
) {
    enum class Paper(val wire: String) { SYSTEM("system"), WARM("warm"), DARK("dark") }

    companion object {
        const val DEFAULT_SIZE = 18
        const val DEFAULT_LINE_HEIGHT = 1.75f
        const val MIN_SIZE = 14
        const val MAX_SIZE = 26
        const val SIZE_STEP = 2
        val LINE_HEIGHTS = listOf(1.5f, 1.75f, 2.0f)

        private const val PREFS_NAME = "reader_settings"
        private const val KEY_SIZE = "font_size"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_PAPER = "paper"

        fun load(context: Context): ReaderStyle {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val paper = Paper.entries.firstOrNull { it.wire == prefs.getString(KEY_PAPER, null) } ?: Paper.SYSTEM
            return ReaderStyle(
                size = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE),
                lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, DEFAULT_LINE_HEIGHT)
                    .takeIf { it in LINE_HEIGHTS.first()..LINE_HEIGHTS.last() } ?: DEFAULT_LINE_HEIGHT,
                paper = paper,
            )
        }

        fun save(context: Context, style: ReaderStyle) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt(KEY_SIZE, style.size)
                putFloat(KEY_LINE_HEIGHT, style.lineHeight)
                putString(KEY_PAPER, style.paper.wire)
            }
        }
    }
}
