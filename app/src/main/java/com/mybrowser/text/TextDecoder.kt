package com.mybrowser.text

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a file that a file manager handed us as `text/plain`.
 *
 * Chrome/WebView already renders an HTTP `text/plain` response; this is only for local
 * content URIs, where there is no server charset. The order is BOM, then UTF-8, then
 * GB18030 (the common Chinese fallback), then Latin-1, which cannot fail.
 */
internal object TextDecoder {
    private val UTF8 = Charsets.UTF_8
    private val GB18030 = Charset.forName("GB18030")
    private val LATIN1 = Charsets.ISO_8859_1

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val bom = bom(bytes)
        val raw = when {
            bom != null -> decode(bytes, bom.first, bom.second, bytes.size)
            else -> strict(bytes, UTF8) ?: strict(bytes, GB18030) ?: lenient(bytes, LATIN1)
        }
        return raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    /** BOM bytes plus the charset they announce. */
    private fun bom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            UTF8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun decode(
        bytes: ByteArray,
        charset: Charset,
        offset: Int,
        end: Int,
    ): String {
        val body = if (offset <= end) bytes.copyOfRange(offset, end) else ByteArray(0)
        return strict(body, charset) ?: lenient(body, charset)
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun lenient(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** A NUL in the first block means the picker lied about the MIME type. */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bom(bytes) != null) return false
        val sample = bytes.size.coerceAtMost(4 * 1024)
        var nul = 0
        for (index in 0 until sample) {
            when (bytes[index]) {
                0.toByte() -> nul++
                // Tab, LF and CR are expected in text; other C0 controls are suspicious
                // but common in terminal logs, so only NUL is treated as decisive.
            }
        }
        return nul > 0
    }
}
