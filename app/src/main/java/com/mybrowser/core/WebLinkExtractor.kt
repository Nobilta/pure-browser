package com.mybrowser.core

/**
 * Extracts explicit HTTP(S) links from omnibar text such as a pasted share blob.
 *
 * Only protocol-prefixed URLs are picked out of mixed prose: a bare domain inside a
 * sentence stays a search (the omnibar's own navigation judgment already handles an
 * input that is nothing but a host). The scan is a bounded single walk over scheme
 * markers — no backtracking regex, no whole-input case folding — and every candidate
 * is re-validated with [UrlUtils.isHttpUrl], so `javascript:`, `data:` and app
 * schemes can never become a "visit" suggestion.
 */
object WebLinkExtractor {

    /** Prose beyond this length is not scanned; the user's text itself is never modified. */
    const val MAX_SCAN_CHARS = 64 * 1024

    /** At most this many distinct links are surfaced; the user chooses among them. */
    const val MAX_LINKS = 3

    private const val HTTP_MARKER = "http://"
    private const val HTTPS_MARKER = "https://"
    private const val LONGEST_MARKER = HTTPS_MARKER.length

    /**
     * Characters that always terminate a URL in running text: CJK quotes and brackets,
     * full-width punctuation, and the ASCII pair characters that URLs cannot open.
     * ASCII `[]` are not stops: they nest in IPv6 hosts and in query values such as
     * `?filter[x]=1`, and the pairing logic in [candidateAt] rejects prose usage.
     * Whitespace and ISO control characters end the candidate as well.
     */
    private val HARD_STOP = "「」『』“”‘’《》〈〉（）【】［］｛｝＜＞，。、！？；：…—·|<>{}\"'\n\r\t"

    /** Extracts up to [MAX_LINKS] distinct links, in order of appearance. */
    fun extractWebLinks(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        // Case-fold nothing: matching is ASCII-only and case-insensitive against the
        // original string, so UTF-16 offsets always point into the caller's text and a
        // capital İ before a link cannot shift the scan. Only the bounded window is
        // ever touched, no matter how long the pasted text is.
        val scanLimit = text.length.coerceAtMost(MAX_SCAN_CHARS)
        val windowCut = text.length > MAX_SCAN_CHARS
        val links = mutableListOf<String>()
        val seen = HashSet<String>()
        var cursor = 0

        while (links.size < MAX_LINKS && cursor < scanLimit) {
            val markerIndex = nextMarker(text, cursor, scanLimit)
            if (markerIndex < 0) break

            val scanned = candidateAt(text, markerIndex, scanLimit, windowCut)
            val candidate = scanned.url
            if (candidate != null && candidate.length > LONGEST_MARKER && seen.add(candidate)) {
                links += candidate
            }
            // A candidate consumes its whole span: a scheme inside a query value (a
            // redirect parameter) is part of the URL, not the start of a new one.
            // Invalid, duplicate and window-truncated candidates consume their span too.
            // Advancing by the trimmed URL length (or only the scheme on failure) would
            // repeatedly scan nested scheme markers in the same rejected candidate.
            cursor = scanned.endExclusive.coerceAtLeast(markerIndex + 1)
        }
        return links
    }

    /** First scheme marker at or after [from], case-insensitive over ASCII only. */
    private fun nextMarker(text: CharSequence, from: Int, limit: Int): Int {
        for (index in from until limit) {
            if (index + LONGEST_MARKER > limit) break
            if (matchesAt(text, index, HTTP_MARKER) || matchesAt(text, index, HTTPS_MARKER)) return index
        }
        return -1
    }

    private fun matchesAt(text: CharSequence, index: Int, marker: String): Boolean {
        for (offset in marker.indices) {
            if (text[index + offset].lowercaseChar() != marker[offset]) return false
        }
        return true
    }

    /**
     * Grows one candidate from a scheme marker. ASCII pair characters are included only
     * while they nest correctly, so "(see https://example.com/a(b))" keeps the inner
     * pair and drops the wrapping one; full-width pairs from CJK text are hard stops.
     * A candidate that runs into the scan window's edge was cut mid-URL and is not a
     * link; one that simply reaches the end of the text is complete.
     */
    private data class Candidate(val url: String?, val endExclusive: Int)

    private fun candidateAt(text: CharSequence, start: Int, limit: Int, windowCut: Boolean): Candidate {
        val depth = ArrayDeque<Char>()
        val builder = StringBuilder()
        var index = start
        while (index < limit) {
            val c = text[index]
            when {
                c.isWhitespace() || c.isISOControl() -> break
                c in HARD_STOP -> break
                c == '(' -> {
                    depth.addLast(c); builder.append(c)
                }
                c == '[' -> {
                    depth.addLast(c); builder.append(c)
                }
                c == ')' -> {
                    if (depth.isEmpty() || depth.last() != '(') break
                    depth.removeLast(); builder.append(c)
                }
                c == ']' -> {
                    if (depth.isEmpty() || depth.last() != '[') break
                    depth.removeLast(); builder.append(c)
                }
                else -> builder.append(c)
            }
            index++
        }
        // The window cut a real URL in half: report nothing rather than a wrong link.
        if (windowCut && index == limit) return Candidate(null, index)
        // Unbalanced opening pair at the tail is prose, not part of the URL.
        while (depth.isNotEmpty() && builder.isNotEmpty()) {
            val last = builder.last()
            if (depth.last() == '(' && last == '(') {
                depth.removeLast(); builder.deleteCharAt(builder.length - 1)
            } else if (depth.last() == '[' && last == '[') {
                depth.removeLast(); builder.deleteCharAt(builder.length - 1)
            } else break
        }
        val candidate = trimTrailing(builder.toString()).trim()
        return Candidate(candidate.takeIf(UrlUtils::isHttpUrl), index)
    }

    /**
     * Drops sentence punctuation from the tail. ASCII closing pairs are dropped only
     * while they outnumber their openers, so `Page_(disambiguation))` in prose keeps
     * its balanced pair and loses only the wrapper's closer. Pair balances are counted
     * once up front — a pasted wall of closing brackets must not make this quadratic.
     */
    private fun trimTrailing(raw: String): String {
        var parens = 0
        var brackets = 0
        var braces = 0
        for (c in raw) when (c) {
            '(' -> parens++
            ')' -> parens--
            '[' -> brackets++
            ']' -> brackets--
            '{' -> braces++
            '}' -> braces--
        }
        var end = raw.length
        while (end > 0) {
            val c = raw[end - 1]
            when {
                c in ".,;:!?…>,。、！？；：）】」』”》" -> end--
                // A negative balance means closers outnumber openers in the text so far:
                // dropping one closer moves the balance back towards zero.
                c == ')' -> if (parens < 0) { end--; parens++ } else break
                c == ']' -> if (brackets < 0) { end--; brackets++ } else break
                c == '}' -> if (braces < 0) { end--; braces++ } else break
                else -> break
            }
        }
        return raw.substring(0, end)
    }
}
