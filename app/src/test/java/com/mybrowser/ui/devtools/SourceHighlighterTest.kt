package com.mybrowser.ui.devtools

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Use the same sandbox as the other url_utils JNI tests: a native library cannot be
// loaded by both Gradle's test classloader and Robolectric's sandbox in one JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceHighlighterTest {
    @Test fun nativeTokensUseUtf16OffsetsAndKeepTheOriginalText() {
        val source = "😀<p title='广告😀'>text</p><script>const x=23;/*note*/</script><style>.x{color:#fff}</style>"
        val document = SourceHighlighter.parse(source)
        assertEquals(source, document.blocks.joinToString("") { it.text })
        val painted = document.blocks.flatMap { block -> block.spans.map { block.text.substring(it.start, it.end) to it.token } }
        assertTrue(painted.contains("'广告😀'" to SourceToken.STRING))
        assertTrue(painted.contains("const" to SourceToken.KEYWORD))
        assertTrue(painted.contains("/*note*/" to SourceToken.COMMENT))
        assertTrue(painted.contains("color" to SourceToken.ATTRIBUTE))
        assertFalse(document.truncated)
    }

    @Test fun blockAndDocumentLimitsNeverSplitSurrogatePairsOrProduceEmptyBlocks() {
        for (input in listOf("x".repeat(767) + "😀tail", "x".repeat(SourceHighlighter.MAX_SOURCE_CHARS - 1) + "😀tail")) {
            val document = SourceHighlighter.parse(input)
            assertTrue(document.blocks.all { it.text.isNotEmpty() && it.text.length <= SourceHighlighter.MAX_BLOCK_CHARS })
            assertTrue(document.blocks.none { it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate() })
            assertEquals(input.take(document.characters), document.blocks.joinToString("") { it.text })
        }
    }

    @Test fun commentsSpanBlocksAndPunctuationHasABoundedStyleCount() {
        val comment = "<!--" + "x".repeat(1600) + "-->"
        val document = SourceHighlighter.parse(comment)
        assertTrue(document.blocks.all { b -> b.spans.single().let { it.start == 0 && it.end == b.text.length && it.token == SourceToken.COMMENT } })
        val code = SourceHighlighter.parse("<script>" + ";".repeat(5000) + "</script>")
        assertTrue(code.blocks.all { it.spans.size <= 384 })
        val lines = SourceHighlighter.parse("a\n".repeat(100_000))
        assertTrue(lines.truncated)
        assertEquals(4096, lines.blocks.size)
        assertEquals(13, lines.blocks[1].line)
    }

    @Test fun cancellationIsObservedAndEmptyInputIsValid() {
        assertTrue(SourceHighlighter.parse("").blocks.isEmpty())
        val cancelled = java.util.concurrent.CancellationException()
        try { SourceHighlighter.parse("<p>hello</p>") { throw cancelled }; fail() }
        catch (error: java.util.concurrent.CancellationException) { assertSame(cancelled, error) }
    }

    private fun numbers(source: String) = tokens(source).filter { it.second == SourceToken.NUMBER }.map { it.first }
    private fun keywords(source: String) = tokens(source).filter { it.second == SourceToken.KEYWORD }.map { it.first }
    private fun attributes(source: String) = tokens(source).filter { it.second == SourceToken.ATTRIBUTE }.map { it.first }

    private fun tokens(source: String) =
        SourceHighlighter.parse(source).blocks.flatMap { block -> block.spans.map { block.text.substring(it.start, it.end) to it.token } }

    @Test fun unicodeClassificationMatchesTheLegacyKotlinLexer() {
        // No/Nl numbers never join a NUMBER token; Nd digits like \u0663 still do.
        assertEquals(listOf("1"), numbers("<script>1\u00BD</script>"))
        assertEquals(listOf("2"), numbers("<script>2\u00B2</script>"))
        assertEquals(listOf("1.5e3"), numbers("<script>1.5e3\u00B2</script>"))
        assertEquals(listOf("1"), numbers("<script>1\u2160</script>"))
        assertEquals(listOf("\u0663"), numbers("<script>\u0663</script>"))
        assertTrue(numbers("<style>width:1\u00B2px{color:#fff}</style>").contains("1"))
        assertTrue(keywords("<style>width:1\u00B2px{color:#fff}</style>").contains("px"))
        // A combining mark is not a letter: `const` keeps KEYWORD highlighting.
        assertTrue(keywords("<script>const\u05B0 x=1;</script>").contains("const"))
        // U+0085 is not whitespace (KEYWORD); U+001C..U+001F are (ATTRIBUTE); NBSP is.
        assertTrue(keywords("<style>width\u0085:1px</style>").contains("width"))
        for (unit in listOf('\u001C', '\u001D', '\u001E', '\u001F')) {
            assertTrue("U+%04X".format(unit.code), attributes("<style>width$unit:1px</style>").contains("width"))
        }
        assertTrue(attributes("<style>width\u00A0:1px</style>").contains("width"))
        assertTrue(attributes("<style>width\u2007:1px</style>").contains("width"))
        // Script-closing tag edge keeps the intended fix: </scriptx> does not end the block.
        assertEquals(2, keywords("<script>const x=1;</scriptx>const y=2;</script>").count { it == "const" })
    }
}
