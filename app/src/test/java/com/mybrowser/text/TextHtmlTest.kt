package com.mybrowser.text

import org.junit.Assert.*
import org.junit.Test

class TextHtmlTest {
    @Test fun markupIsInertAndTheFirstNewlineIsPreserved() {
        val html = textHtml("\n</pre><script>alert('&')</script>", 0xFFFFFF, 0)
        assertTrue(html.contains("<pre>\n\n&lt;/pre&gt;&lt;script&gt;alert('&amp;')&lt;/script&gt;</pre>"))
        assertFalse(html.contains("<script>"))
    }
}
