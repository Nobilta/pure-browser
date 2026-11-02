package com.mybrowser.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextMimeTypesTest {
    @Test
    fun `common yaml and structured text types are accepted`() {
        assertTrue(PlainTextMimeTypes.accepts("text/yaml"))
        assertTrue(PlainTextMimeTypes.accepts("application/yaml"))
        assertTrue(PlainTextMimeTypes.accepts("application/x-yaml"))
        assertTrue(PlainTextMimeTypes.accepts("application/json; charset=utf-8"))
        assertTrue(PlainTextMimeTypes.accepts("text/markdown"))
        assertTrue(PlainTextMimeTypes.accepts("text/csv"))
    }

    @Test
    fun `html and binaries are not plain-text targets`() {
        assertFalse(PlainTextMimeTypes.accepts("text/html"))
        assertFalse(PlainTextMimeTypes.accepts("application/octet-stream"))
        assertFalse(PlainTextMimeTypes.accepts(null))
    }
}
