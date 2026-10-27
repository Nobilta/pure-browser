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

    fun sanitizeFilename(raw: String): String =
        FilenameParser.sanitize(raw, keepExtension = false)

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
