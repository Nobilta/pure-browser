package com.mybrowser.text

internal enum class TextFormat {
    PLAIN, HTML, MARKDOWN, JSON, XML, CSV, TSV, YAML, TOML, INI, PROPERTIES;

    val hasPreview: Boolean get() = this != PLAIN && this != HTML

    companion object {
        fun detect(type: String?, name: String): TextFormat = when (type?.substringBefore(';')?.trim()?.lowercase()) {
            "text/html" -> HTML
            "text/markdown", "text/x-markdown" -> MARKDOWN
            "application/json", "text/json" -> JSON
            "application/xml", "text/xml" -> XML
            "text/csv", "text/comma-separated-values" -> CSV
            "text/tab-separated-values" -> TSV
            "text/yaml", "text/x-yaml", "application/yaml", "application/x-yaml" -> YAML
            "application/toml", "application/x-toml" -> TOML
            "text/x-ini" -> INI
            "text/x-properties" -> PROPERTIES
            null, "", "text/plain", "application/octet-stream" -> when (name.substringAfterLast('.').lowercase()) {
                "md", "markdown" -> MARKDOWN
                "json" -> JSON
                "xml" -> XML
                "csv" -> CSV
                "tsv" -> TSV
                "yaml", "yml" -> YAML
                "toml" -> TOML
                "ini" -> INI
                "properties" -> PROPERTIES
                else -> PLAIN
            }
            else -> PLAIN
        }
    }
}
