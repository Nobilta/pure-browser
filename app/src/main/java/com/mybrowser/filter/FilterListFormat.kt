package com.mybrowser.filter

import java.io.IOException

/** Validation rejects error pages and empty updates before replacing a working snapshot. */
object FilterListFormat {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_LINES = 200_000

    fun validate(text: String): Int {
        if (text.toByteArray().size > MAX_BYTES || text.contains('\u0000')) throw IOException("Invalid filter list")
        val start = text.trimStart().take(512).lowercase()
        if (start.startsWith("<") || start.startsWith("{") || (start.startsWith("[") && !start.startsWith("[adblock")) ||
            Regex("(?i)<(?:!doctype\\s+html|html\\b|head\\b|body\\b)").containsMatchIn(text.take(65536))) {
            throw IOException("Not an Adblock filter list")
        }
        var count = 0
        var lines = 0
        text.lineSequence().forEach { raw ->
            if (++lines > MAX_LINES) throw IOException("Too many filter lines")
            val line = raw.trim()
            if (line.isNotEmpty() && line.length <= 8192 && !line.startsWith('!') && !line.startsWith('[')) {
                if (line.contains("##") || line.contains("#@#") ||
                    (line.none(Char::isWhitespace) && (line.contains('.') || line.contains('/') || line.contains('|')))) count++
            }
        }
        if (count == 0) throw IOException("No filter rules")
        return count
    }
}
