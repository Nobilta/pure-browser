package com.mybrowser.text

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class TextDecoderTest {
    @Test fun htmlHonorsMetaCharsetAndBomPrecedence() {
        for (meta in listOf("<meta charset=gbk>", "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">")) {
            val text = meta + "<p>中文</p>"
            assertEquals(text, TextDecoder.decode(text.toByteArray(Charset.forName("GB18030")), html = true))
        }
        val latin = "<meta charset=windows-1252><p>€ café</p>"
        assertEquals(latin, TextDecoder.decode(latin.toByteArray(Charset.forName("windows-1252")), html = true))
        val utf8 = "<meta charset=gbk><p>中文😀</p>"
        assertEquals(utf8, TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8.toByteArray(), html = true))
        val commented = "<!-- <meta charset=gbk> --><meta charset=unknown><meta charset=utf-8>中文😀"
        assertEquals(commented, TextDecoder.decode(commented.toByteArray(), html = true))
    }

    @Test fun utf8WithAndWithoutBomPreservesChineseAndEmoji() {
        val text = "hello 世界😀"
        val bytes = text.toByteArray()
        assertEquals(text, TextDecoder.decode(bytes))
        assertEquals(text, TextDecoder.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes))
    }

    @Test fun htmlCharsetUsesOnlyEncodingAttributes() {
        for (head in listOf(
            """<meta name=description content="example charset=gbk"><meta charset=utf-8>""",
            """<meta data-charset=gbk charset=utf-8>""",
            """<meta name=description content="charset=gbk > text" charset=utf-8>""",
            """<div data-example='<meta charset=gbk>'></div><meta charset=utf-8>""",
            """<!-- <meta charset=gbk> --><meta charset=utf-8 charset=gbk>""",
            """<meta content="text/html; charset=gbk"><meta charset=utf-8>""",
        )) {
            val text = head + "<p>中文 😀</p>"
            assertEquals(text, TextDecoder.decode(text.toByteArray(), html = true))
        }
        val legacy = """<META CONTENT='text/html; charset=gbk' HTTP-EQUIV='Content-Type'><p>中文</p>"""
        assertEquals(legacy, TextDecoder.decode(legacy.toByteArray(Charset.forName("GB18030")), html = true))
    }

    @Test fun htmlUtf16DeclarationsUseUtf8UnlessThereIsABom() {
        for (label in listOf("utf-16", "UTF-16LE", "utf-16be")) {
            val text = "<meta charset='$label'><p>中文 😀</p>"
            assertEquals(text, TextDecoder.decode(text.toByteArray(), html = true))
            assertEquals(text, TextDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                text.toByteArray(Charsets.UTF_16LE), html = true))
        }
    }

    @Test fun htmlCharsetDoesNotScanPastTheFirst1024Bytes() {
        val text = " ".repeat(1024) + "<meta charset=gbk><p>中文 😀</p>"
        assertEquals(text, TextDecoder.decode(text.toByteArray(), html = true))
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
