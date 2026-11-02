package com.mybrowser.text

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {
    @Test fun utf8WithAndWithoutBomPreservesChineseAndEmoji() {
        val text = "hello 世界😀"
        val bytes = text.toByteArray()
        assertEquals(text, TextDecoder.decode(bytes))
        assertEquals(text, TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes))
    }

    @Test fun utf16BomSelectsTheByteOrder() {
        val text = "中文😀\nnext"
        for ((charset, bom) in listOf(
            Charsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            Charsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
        )) assertEquals(text, TextDecoder.decode(bom + text.toByteArray(charset)))
    }

    @Test fun gb18030AndLatin1RemainSupported() {
        for (text in listOf("中文日志", "a".repeat(1000) + "中文", "a".repeat(500) + "鄝")) {
            assertEquals(text, TextDecoder.decode(text.toByteArray(Charset.forName("GB18030"))))
        }
        val text = "café\n".repeat(20_000)
        assertEquals(text, TextDecoder.decode(text.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun completeChineseAtTheFiveMiBBoundaryIsPreserved() {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"))) {
            val suffix = "中文".toByteArray(charset)
            val prefix = "a".repeat(PlainTextLoader.MAX_BYTES - suffix.size)
            assertEquals(prefix + "中文", TextDecoder.decode(prefix.toByteArray() + suffix))
        }
    }

    @Test fun lineEndingsAreNormalisedAndEmptyFilesAreValid() {
        assertEquals("a\nb\nc", TextDecoder.decode("a\r\nb\rc".toByteArray()))
        assertEquals("", TextDecoder.decode(byteArrayOf()))
    }

    @Test fun nulMarksBinaryButUtf16BomDoesNot() {
        assertTrue(TextDecoder.looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
        assertFalse(TextDecoder.looksBinary(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00)))
    }
}
