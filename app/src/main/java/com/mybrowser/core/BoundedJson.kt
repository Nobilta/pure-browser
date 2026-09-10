package com.mybrowser.core

/** Serialize each record once, including delimiters in the persisted character budget. */
internal fun <T> boundedJsonArray(items: Sequence<T>, maxChars: Int, encode: (T) -> String): String {
    require(maxChars >= 2)
    return buildString {
        append('[')
        for (item in items) {
            val encoded = encode(item)
            val delimiter = if (length > 1) 1 else 0
            if (encoded.length > maxChars - length - delimiter - 1) continue
            if (delimiter > 0) append(',')
            append(encoded)
        }
        append(']')
    }
}
