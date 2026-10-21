package com.mybrowser.filter

import java.io.IOException

/** Validation rejects error pages and empty updates before replacing a working snapshot. */
object FilterListFormat {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_LINES = 200_000

    /**
     * How many lists may be loaded at once: every bundled list, every custom subscription, and the
     * one local list of rules the user wrote by hand. A payload beyond this is refused outright,
     * which leaves the engine holding its previous snapshot — so the number has to track the lists
     * the app can actually produce rather than being a round figure. [FilterSubscriptions] holds
     * the two counts this adds up, and `UserRuleStoreTest` asserts the sum.
     */
    const val MAX_LISTS = 36

    fun validate(text: String): Int {
        if (FilterController.utf8Bytes(text) > MAX_BYTES || text.contains('\u0000')) throw IOException("Invalid filter list")
        val start = text.trimStart().take(512).lowercase()
        if (start.startsWith("<") || start.startsWith("{") || (start.startsWith("[") && !start.startsWith("[adblock")) ||
            HTML_PREFIX.containsMatchIn(text.take(65_536))) {
            throw IOException("Not an Adblock filter list")
        }
        var count = 0
        var lines = 0
        text.lineSequence().forEach { raw ->
            if (++lines > MAX_LINES) throw IOException("Too many filter lines")
            val line = raw.trim()
            if (line.isNotEmpty() && line.length <= 8192 && !line.startsWith('!') && !line.startsWith('[')) {
                // The counting rule this app writes its own rules against: a line with a separator,
                // an anchor, or a cosmetic marker is a rule. What it deliberately does not do is
                // demand one of those characters from the *anchored* text — `||ads^` is a host
                // pattern, and a test for a separator on the stripped body misses it.
                if (line.contains("##") || line.contains("#@#")) {
                    count++
                    return@forEach
                }
                if (line.any(Char::isWhitespace)) return@forEach
                val body = line.removePrefix("@@")
                val pattern = if (body.startsWith("||")) body.substring(2) else body.removePrefix("|")
                val unanchored = pattern.removeSuffix("|")
                // A body that only repeats separators (`|`, `||`, `/`, `.`) names nothing: the
                // engine refuses it for having an empty pattern, so counting it would let a payload
                // of bare anchors pass as a list with rules in it.
                if (unanchored.none { it.isLetterOrDigit() }) return@forEach
                if (unanchored.contains('.') || unanchored.contains('/') || line.contains('|')) count++
            }
        }
        if (count == 0) throw IOException("No filter rules")
        return count
    }

    /** Compiled once: every downloaded list is validated, and most are megabytes. */
    private val HTML_PREFIX = Regex("(?i)<(?:!doctype\\s+html|html\\b|head\\b|body\\b)")
}
