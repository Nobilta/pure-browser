package com.mybrowser.backup

/** Bound strings before formatting/layout too, including previews constructed without decoding. */
internal object ImportPreviewText {
    private const val MAX_NOTE_CHARS = 768
    private const val MAX_VISIBLE_IDS = 8

    fun limit(text: String, maxChars: Int = MAX_NOTE_CHARS): String {
        require(maxChars >= 2)
        if (text.length <= maxChars) return text
        var end = maxChars - 1
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end) + "…"
    }

    fun unknownIds(ids: List<String>): String = ids.asSequence().take(MAX_VISIBLE_IDS)
        .map { limit(it, SettingsBackupCodec.MAX_BUILT_IN_ID_CHARS) }.joinToString(", ") +
        if (ids.size > MAX_VISIBLE_IDS) ", …" else ""
}
