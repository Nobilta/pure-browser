package com.mybrowser.ui.devtools

import org.junit.Assert.*
import org.junit.Test

class DevToolsTextTest {
    @Test fun selectionPagesPreserveNewlinesAndUnicodeWithoutTruncation() {
        val text = "header\n" + "x".repeat(16376) + "😀\r\n" + "长行".repeat(20000) + "\nend"
        val pages = selectableTextPages(text)
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { it.length <= 16384 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun newlinesDoNotDropTextAtPageBoundaries() {
        val text = (1..10000).joinToString("\r\n") { "line $it" }
        assertEquals(text, selectableTextPages(text).joinToString(""))
        assertEquals(listOf(""), selectableTextPages(""))
    }

    @Test fun whitespacePreviewDistinguishesEscapesFromActualLineBreaks() {
        assertEquals("\\n\\t\\r\\f", domTextPreview("\n\t\r\u000c"))
        assertEquals("\\\\n", domTextPreview("\\n"))
        assertEquals("···", domTextPreview("   "))
        assertEquals("a b", domTextPreview("a b"))
    }
}
