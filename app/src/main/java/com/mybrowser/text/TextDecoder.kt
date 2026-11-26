package com.mybrowser.text

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a file that a file manager handed us as `text/plain`.
 *
 * Chrome/WebView already renders an HTTP `text/plain` response; this is only for local
 * content URIs, where there is no server charset. The order is BOM, then UTF-8, then
 * GB18030 (the common Chinese fallback), then Latin-1, which cannot fail. HTML checks
 * its declared charset after the BOM.
 */
internal object TextDecoder {
    private val UTF8 = Charsets.UTF_8
    private val GB18030 = Charset.forName("GB18030")
    private val LATIN1 = Charsets.ISO_8859_1

    fun decode(bytes: ByteArray, html: Boolean = false): String {
        if (bytes.isEmpty()) return ""
        val bom = bom(bytes)
        val declared = if (html && bom == null) htmlCharset(bytes) else null
        val raw = when {
            bom != null -> decode(bytes, bom.first, bom.second, bytes.size)
            declared != null -> lenient(bytes, declared)
            else -> strict(bytes, UTF8) ?: strict(bytes, GB18030) ?: lenient(bytes, LATIN1)
        }
        return raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    /** HTML prescans the first 1024 bytes; a BOM takes precedence over meta declarations. */
    private fun htmlCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 1024), LATIN1)
        // Consume comments and quoted attributes as a whole so their text is not another tag.
        val tags = Regex("""<!--.*?(?:-->|$)|<([a-z][^\s/>]*)(?=[\s/>])((?:"[^"]*"|'[^']*'|[^'">])*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val attribute = Regex("""([^\s/=<>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")
        for (tag in tags.findAll(head)) {
            if (!tag.groupValues[1].equals("meta", ignoreCase = true)) continue
            val attributes = HashMap<String, String>()
            for (match in attribute.findAll(tag.groupValues[2])) {
                attributes.putIfAbsent(match.groupValues[1].lowercase(),
                    match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value.orEmpty())
            }
            val label = attributes["charset"] ?: attributes["content"]
                ?.takeIf { attributes["http-equiv"].equals("content-type", ignoreCase = true) }
                ?.let { Regex("charset\\s*=\\s*['\"]?([^\\s'\";/>]+)", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.get(1) } ?: continue
            val name = when (label.trim().lowercase()) {
                "gb2312", "gbk" -> "GB18030"
                "iso-8859-1", "latin1", "us-ascii" -> "windows-1252"
                "utf-16", "utf-16le", "utf-16be" -> "UTF-8"
                else -> label.trim()
            }
            try { return Charset.forName(name) }
            catch (_: IllegalArgumentException) { /* Unsupported declarations use normal detection. */ }
        }
        return null
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
