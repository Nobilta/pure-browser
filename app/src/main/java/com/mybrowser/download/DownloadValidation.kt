package com.mybrowser.download

import com.mybrowser.core.UrlUtils

/** Input limits shared by new requests and persisted records. */
internal object DownloadValidation {
    const val MAX_URL_LENGTH = 8_192
    const val MAX_USER_AGENT_LENGTH = 1_024
    const val MAX_COOKIE_LENGTH = 65_536
    const val MAX_CONTENT_DISPOSITION_LENGTH = 8_192
    private const val MAX_MIME_LENGTH = 256
    const val MAX_DESTINATION_LABEL_LENGTH = 120
    /** Placeholder for a file supplied by a document; it can never be fetched by HTTP. */
    const val PAGE_FILE_PREFIX = "page-file:"
    private val MIME_PATTERN = Regex("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+")

    fun validHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return value.takeIf { UrlUtils.isHttpUrl(it, MAX_URL_LENGTH) }
    }

    /** Every address a task may fetch: HTTP for the engine and the playlist, FTP for one stream. */
    fun validDownloadUrl(raw: String?): String? = validHttpUrl(raw) ?: validFtpUrl(raw)

    fun validFtpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        return value.takeIf { FtpTarget.parse(it) != null }
    }

    /** Removes userinfo without decoding or rewriting the resource path and query. */
    fun redactCredentials(url: String): String = url.replace(USER_INFO, "$1")

    fun displaySource(origin: String?, referer: String?, url: String): String =
        redactCredentials(validHttpUrl(origin) ?: validHttpUrl(referer) ?: url.substringBefore('#'))

    private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#]*@")

    fun sanitizeFilename(raw: String): String =
        FilenameParser.sanitize(raw)

    fun sanitizeHeader(raw: String?, maxLength: Int): String? = raw
        ?.filterNot { it == '\r' || it == '\n' || it.isISOControl() }
        ?.trim()
        ?.take(maxLength)
        ?.ifEmpty { null }

    fun sanitizeMime(raw: String?): String {
        val value = sanitizeHeader(raw, MAX_MIME_LENGTH).orEmpty()
        return value.takeIf { MIME_PATTERN.matches(it) }.orEmpty()
    }
}
