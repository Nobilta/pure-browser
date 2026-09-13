package com.mybrowser.ui

/** Plain token data: parsing never needs Android, a theme, or the UI thread. */
internal enum class SourceToken { TAG, ATTRIBUTE, STRING, COMMENT, KEYWORD, NUMBER, PUNCTUATION }
internal data class SourceSpan(val start: Int, val end: Int, val token: SourceToken)
internal data class SourceBlock(val line: Int, val continuation: Boolean, val text: String, val spans: List<SourceSpan>)
internal data class SourceDocument(val blocks: List<SourceBlock>, val characters: Int, val truncated: Boolean)

/**
 * A linear HTML/JS/CSS lexer, not a formatter: whitespace and source order stay intact.
 * Both minified lines and newline-heavy documents have bounded layout/allocation costs.
 */
internal object SourceHighlighter {
    const val MAX_SOURCE_CHARS = 1_000_000
    const val MAX_BLOCK_CHARS = 768
    private const val MAX_BLOCK_LINES = 12
    private const val MAX_BLOCKS = 4_096
    private const val MAX_SPANS_PER_BLOCK = 384
    private val keywords = setOf("async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "from", "function", "get", "if", "import", "in", "instanceof", "let", "new", "null", "of",
        "return", "set", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
        "undefined", "var", "void", "while", "with", "yield")

    fun parse(input: String, checkCancelled: () -> Unit = {}): SourceDocument {
        val blockStarts = ArrayList<Int>()
        val blocks = ArrayList<SourceBlock>()
        val spans = ArrayList<ArrayList<SourceSpan>>()
        val limit = minOf(input.length, MAX_SOURCE_CHARS)
        var position = 0
        var line = 1
        var continuation = false
        while (position < limit && blocks.size < MAX_BLOCKS) {
            checkCancelled()
            val start = position
            val firstLine = line
            var lines = 0
            var end = minOf(start + MAX_BLOCK_CHARS, limit)
            // A split must not turn a valid supplementary Unicode character into two glyphs.
            if (end < input.length && end > start && input[end - 1].isHighSurrogate() && input[end].isLowSurrogate()) end--
            while (position < end) {
                if (input[position++] == '\n') {
                    line++
                    if (++lines == MAX_BLOCK_LINES) break
                }
            }
            blockStarts.add(start)
            val blockSpans = ArrayList<SourceSpan>()
            spans.add(blockSpans)
            blocks.add(SourceBlock(firstLine, continuation, input.substring(start, position), blockSpans))
            continuation = input[position - 1] != '\n'
        }
        val end = position
        var spanBlock = 0
        // The scanner emits in source order. No regex backtracking or per-token binary search.
        fun paint(from: Int, until: Int, token: SourceToken) {
            var offset = from
            while (spanBlock < blocks.size && blockStarts[spanBlock] + blocks[spanBlock].text.length <= offset) spanBlock++
            while (offset < until && spanBlock < blocks.size) {
                val blockStart = blockStarts[spanBlock]
                val blockEnd = blockStart + blocks[spanBlock].text.length
                val stop = minOf(until, blockEnd)
                if (spans[spanBlock].size < MAX_SPANS_PER_BLOCK) {
                    spans[spanBlock].add(SourceSpan(offset - blockStart, stop - blockStart, token))
                }
                offset = stop
                if (offset >= blockEnd) spanBlock++
            }
        }
        fun starts(text: String, at: Int, ignoreCase: Boolean = false) =
            at + text.length <= end && input.regionMatches(at, text, 0, text.length, ignoreCase)
        fun closing(text: String, at: Int, bound: Int = end): Int {
            val found = input.indexOf(text, at)
            return if (found < 0 || found >= bound) bound else minOf(found + text.length, bound)
        }
        fun quoted(at: Int, bound: Int, escapes: Boolean): Int {
            val quote = input[at]
            var next = at + 1
            while (next < bound) {
                val char = input[next++]
                if (escapes && char == '\\' && next < bound) next++
                else if (char == quote) break
            }
            return next
        }
        fun identifier(char: Char) = char.isLetterOrDigit() || char == '_' || char == '$' || char == '-' || char == ':'
        fun code(from: Int, until: Int, css: Boolean) {
            var cursor = from
            while (cursor < until) {
                checkCancelled()
                val start = cursor
                val char = input[cursor]
                when {
                    starts("/*", cursor) -> { cursor = closing("*/", cursor + 2, until); paint(start, cursor, SourceToken.COMMENT) }
                    !css && starts("//", cursor) -> {
                        val newline = input.indexOf('\n', cursor + 2)
                        cursor = if (newline < 0) until else minOf(newline, until)
                        paint(start, cursor, SourceToken.COMMENT)
                    }
                    char == '\'' || char == '"' || (!css && char == '`') -> {
                        cursor = quoted(cursor, until, escapes = true); paint(start, cursor, SourceToken.STRING)
                    }
                    char.isDigit() || (css && char == '#') -> {
                        cursor++
                        while (cursor < until && (input[cursor].isLetterOrDigit() || input[cursor] in ".%_")) cursor++
                        paint(start, cursor, SourceToken.NUMBER)
                    }
                    char.isLetter() || char == '_' || char == '$' || (css && char in "-@") -> {
                        cursor++
                        while (cursor < until && (input[cursor].isLetterOrDigit() || input[cursor] in "_$" || (css && input[cursor] == '-'))) cursor++
                        val word = input.substring(start, cursor)
                        var lookAhead = cursor
                        while (lookAhead < until && input[lookAhead].isWhitespace()) lookAhead++
                        if (css || word in keywords) paint(start, cursor,
                            if (css && lookAhead < until && input[lookAhead] == ':') SourceToken.ATTRIBUTE else SourceToken.KEYWORD)
                    }
                    char in "{}[]();:.,=+*/!?&|<>%-" -> { cursor++; paint(start, cursor, SourceToken.PUNCTUATION) }
                    else -> cursor++
                }
            }
        }
        position = 0
        while (position < end) {
            checkCancelled()
            val start = position
            when {
                starts("<!--", position) -> { position = closing("-->", position + 4); paint(start, position, SourceToken.COMMENT) }
                starts("<!", position) || starts("<?", position) -> {
                    position = closing(">", position + 2); paint(start, position, SourceToken.KEYWORD)
                }
                input[position] == '<' && position + 1 < end && (input[position + 1].isLetter() || input[position + 1] == '/') -> {
                    position++
                    val isClosing = input[position] == '/'
                    if (isClosing) position++
                    val nameStart = position
                    while (position < end && identifier(input[position])) position++
                    val name = input.substring(nameStart, position)
                    paint(start, position, SourceToken.TAG)
                    var selfClosing = false
                    while (position < end && input[position] != '>') {
                        val attributeStart = position
                        when {
                            input[position].isWhitespace() -> position++
                            input[position] == '\'' || input[position] == '"' -> {
                                position = quoted(position, end, escapes = false); paint(attributeStart, position, SourceToken.STRING)
                            }
                            input[position] == '=' -> { position++; paint(attributeStart, position, SourceToken.PUNCTUATION) }
                            input[position] == '/' -> { position++; selfClosing = true; paint(attributeStart, position, SourceToken.TAG) }
                            else -> {
                                position++
                                while (position < end && !input[position].isWhitespace() && input[position] !in "=/>\"'") position++
                                paint(attributeStart, position, SourceToken.ATTRIBUTE)
                            }
                        }
                    }
                    if (position < end) { paint(position, position + 1, SourceToken.TAG); position++ }
                    if (!isClosing && !selfClosing && (name.equals("script", true) || name.equals("style", true))) {
                        val close = input.indexOf("</$name", position, ignoreCase = true)
                        val rawEnd = if (close < 0) end else minOf(close, end)
                        code(position, rawEnd, name.equals("style", true))
                        position = rawEnd
                    }
                }
                input[position] == '&' -> {
                    position++
                    while (position < minOf(end, start + 32) && (input[position].isLetterOrDigit() || input[position] == '#')) position++
                    if (position < end && input[position] == ';') { position++; paint(start, position, SourceToken.NUMBER) }
                }
                else -> position++
            }
        }
        return SourceDocument(blocks, end, end < input.length)
    }
}
