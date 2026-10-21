package com.mybrowser.core

import java.util.Locale

/** One binary-size spelling for downloads and developer tools. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 10 || value == value.toLong().toDouble()) {
        "%.0f %s".format(Locale.US, value, units[index])
    } else {
        "%.1f %s".format(Locale.US, value, units[index])
    }
}
