package com.mybrowser.text

/**
 * MIME types a file manager uses for text files. Android's MimeTypeMap is inconsistent
 * (`application/yaml`, `text/yaml`, `application/x-yaml` all occur), so the browser
 * registers the common variants instead of treating only `text/plain` as text.
 *
 * `text/html` is deliberately absent: a local HTML document should be rendered by the
 * browser, not shown as source in the plain-text viewer.
 */
internal object PlainTextMimeTypes {
    val all: List<String> = listOf(
        "text/plain",
        "text/yaml",
        "text/x-yaml",
        "application/yaml",
        "application/x-yaml",
        "application/json",
        "text/json",
        "application/xml",
        "text/xml",
        "text/markdown",
        "text/x-markdown",
        "text/csv",
        "text/comma-separated-values",
        "text/tab-separated-values",
        "application/toml",
        "application/x-toml",
        "application/x-sh",
        "application/x-shellscript",
        "text/x-shellscript",
        "text/x-properties",
        "text/x-ini",
        "text/x-log",
        "text/x-python",
        "text/x-java",
        "text/x-c",
        "text/x-c++",
        "text/x-sql",
    )

    fun accepts(type: String?): Boolean {
        if (type.isNullOrBlank()) return false
        val normalized = type.substringBefore(';').trim().lowercase()
        return normalized in all
    }
}
