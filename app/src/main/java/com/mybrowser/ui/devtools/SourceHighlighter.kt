package com.mybrowser.ui.devtools

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
    private val nativeAvailable = try {
        System.loadLibrary("mybrowser_url_utils")
        true
    } catch (_: UnsatisfiedLinkError) { false }

    @JvmStatic private external fun nativeSpans(input: CharArray, blockEnds: IntArray): IntArray?

    fun parse(input: String, checkCancelled: () -> Unit = {}): SourceDocument {
        val blockStarts = ArrayList<Int>()
        val blocks = ArrayList<SourceBlock>()
        val spans = ArrayList<ArrayList<SourceSpan>>()
        var limit = minOf(input.length, MAX_SOURCE_CHARS)
        if (limit < input.length && limit > 0 && input[limit - 1].isHighSurrogate() && input[limit].isLowSurrogate()) limit--
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
        checkCancelled()
        // One bounded JNI call returns UTF-16 offsets, matching Compose without byte conversion.
        // A missing native library still leaves the complete, selectable plain source available,
        // and so does a copy that does not fit in memory on a low-RAM device.
        val tokens = if (nativeAvailable && end > 0) try {
            nativeSpans(input.toCharArray(0, end),
                IntArray(blocks.size) { blockStarts[it] + blocks[it].text.length })
        } catch (_: UnsatisfiedLinkError) { null }
        catch (_: OutOfMemoryError) { null } else null
        checkCancelled()
        var block = 0
        if (tokens != null) for (index in tokens.indices step 3) {
            if (index % 768 == 0) checkCancelled()
            val start = tokens[index]
            val stop = tokens[index + 1]
            while (block < blocks.size && blockStarts[block] + blocks[block].text.length <= start) block++
            if (block >= blocks.size) break
            spans[block].add(SourceSpan(start - blockStarts[block], stop - blockStarts[block], SourceToken.entries[tokens[index + 2]]))
        }
        return SourceDocument(blocks, end, end < input.length)
    }
}
