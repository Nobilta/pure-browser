package com.mybrowser.download

import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/**
 * Working out what to call a downloaded file.
 *
 * `Content-Disposition` is one of those headers where the spec and reality diverge.
 * Three forms appear in the wild:
 *
 *   filename="report.pdf"                      plain, RFC 6266
 *   filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf    RFC 5987 extended, percent-encoded
 *   filename="=?UTF-8?B?...?="                 RFC 2047 MIME word, technically invalid
 *
 * `filename*` wins when both are present, per RFC 6266. Chinese sites in particular send
 * GBK-encoded `filename*` or raw high bytes in `filename`, so decoding has to be
 * defensive rather than assume UTF-8.
 */
object FilenameParser {

    private const val FALLBACK_NAME = "download"
    private const val MAX_LENGTH = 127

    /**
     * Characters illegal in a filename, plus control characters. Spaces and
     * hyphens are deliberately left alone: both are legal, and stripping them
     * mangles ordinary names.
     */
    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F\x7F]""")

    private val EXT_PARAM = Regex(
        """filename\*\s*=\s*([^']*)'([^']*)'([^;]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val QUOTED_PARAM = Regex(
        """filename\s*=\s*"([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PARAM = Regex(
        """filename\s*=\s*([^;\s]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val MIME_WORD = Regex(
        """=\?([^?]+)\?([BbQq])\?([^?]*)\?=""",
    )

    /**
     * @param contentDisposition raw header value, may be null
     * @param url the request URL, used when the header gives nothing usable
     * @param mimeType used to append an extension when the name has none
     */
    fun resolve(
        contentDisposition: String?,
        url: String,
        mimeType: String?,
    ): String {
        val fromHeader = contentDisposition?.let { parseHeader(it) }
        val base = fromHeader?.takeIf { it.isNotBlank() }
            ?: fromUrl(url)
            ?: FALLBACK_NAME

        return sanitize(ensureExtension(base, mimeType))
    }

    private fun parseHeader(header: String): String? {
        // RFC 5987 extended form takes precedence.
        EXT_PARAM.find(header)?.let { m ->
            val charsetName = m.groupValues[1].trim().ifEmpty { "UTF-8" }
            val encoded = m.groupValues[3].trim().trim('"')
            decodePercent(encoded, charsetName)?.let { return it }
        }

        val raw = QUOTED_PARAM.find(header)?.groupValues?.get(1)
            ?: BARE_PARAM.find(header)?.groupValues?.get(1)
            ?: return null

        // Some servers send a MIME encoded-word here even though it is not legal.
        decodeMimeWord(raw)?.let { return it }

        // Others percent-encode inside the plain filename parameter.
        if (raw.contains('%')) {
            decodePercent(raw, "UTF-8")?.let { candidate ->
                if (!candidate.contains('�')) return candidate
            }
        }

        return repairMojibake(raw)
    }

    private fun decodePercent(value: String, charsetName: String): String? = try {
        val charset = charsetOf(charsetName) ?: return null
        // RFC 5987 is percent-encoding, not form encoding: a literal '+' in a
        // filename must stay '+', while URLDecoder would turn it into a space.
        URLDecoder.decode(value.replace("+", "%2B"), charset.name())
            .takeIf { it.isNotBlank() && !it.contains('�') }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeMimeWord(value: String): String? {
        val m = MIME_WORD.find(value) ?: return null
        val charset = charsetOf(m.groupValues[1]) ?: return null
        val encoding = m.groupValues[2].uppercase()
        val payload = m.groupValues[3]

        return try {
            val bytes = when (encoding) {
                "B" -> java.util.Base64.getMimeDecoder().decode(payload)
                "Q" -> decodeQuotedPrintable(payload)
                else -> return null
            }
            String(bytes, charset).takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeQuotedPrintable(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '=' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16)
                    if (b != null) {
                        out.add(b.toByte()); i += 3
                    } else {
                        out.add(s[i].code.toByte()); i++
                    }
                }
                s[i] == '_' -> { out.add(' '.code.toByte()); i++ }
                else -> { out.add(s[i].code.toByte()); i++ }
            }
        }
        return out.toByteArray()
    }

    /**
     * Recovers a name whose bytes were read as Latin-1 but are really GBK or UTF-8.
     *
     * HttpURLConnection decodes header bytes as ISO-8859-1, so a server sending raw
     * GBK bytes in `filename=` arrives as mojibake. Re-encoding to Latin-1 and decoding
     * as the real charset restores it. GBK first because that is the common case here;
     * a UTF-8 attempt follows.
     */
    private fun repairMojibake(raw: String): String {
        // Modern Android/WebView stacks may already hand us a proper Unicode header.
        // Re-encoding characters outside ISO-8859-1 would replace them with '?', so
        // leave an already-decoded value untouched.
        if (raw.all { it.code < 0x80 } || raw.any { it.code > 0xFF }) return raw

        val latin1Bytes = raw.toByteArray(Charsets.ISO_8859_1)
        // UTF-8 mojibake is common on modern servers and must be tried before GBK:
        // both decoders can accept some byte sequences, but UTF-8 is unambiguous when
        // it validates without replacement characters.
        for (name in arrayOf("UTF-8", "GBK")) {
            val charset = charsetOf(name) ?: continue
            val decoded = String(latin1Bytes, charset)
            if (!decoded.contains('�')) return decoded
        }
        return raw
    }

    private fun charsetOf(name: String): Charset? = try {
        Charset.forName(name.trim())
    } catch (_: IllegalCharsetNameException) {
        null
    } catch (_: UnsupportedCharsetException) {
        null
    }

    private fun fromUrl(url: String): String? {
        val path = runCatching { url.toUri().lastPathSegment }.getOrNull()
            ?: return null
        val decoded = runCatching {
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(path)
        return decoded.takeIf { it.isNotBlank() }
    }

    private fun ensureExtension(name: String, mimeType: String?): String {
        if (name.contains('.') && !name.endsWith('.')) return name
        val ext = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: return name
        return "${name.trimEnd('.')}.$ext"
    }

    private fun sanitize(name: String): String {
        var s = ILLEGAL.replace(name, "_")
            // A leading dot hides the file; a trailing dot or space breaks some tools.
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')

        if (s.isBlank()) s = FALLBACK_NAME

        if (s.length > MAX_LENGTH) {
            // Truncate the stem, keep the extension: a cut-off extension means the file
            // will not open.
            val dot = s.lastIndexOf('.')
            s = if (dot > 0 && s.length - dot <= 12) {
                val ext = s.substring(dot)
                s.take(MAX_LENGTH - ext.length) + ext
            } else {
                s.take(MAX_LENGTH)
            }
        }
        return s
    }
}
