package com.mybrowser.core

import android.util.AtomicFile

/** Commit UTF-8 text or restore the previous file; callers choose their IO dispatcher. */
internal fun AtomicFile.writeUtf8(text: String) {
    val stream = startWrite()
    try {
        stream.write(text.toByteArray(Charsets.UTF_8))
        finishWrite(stream)
    } catch (error: Exception) {
        failWrite(stream)
        throw error
    }
}
