package com.mybrowser.core

import android.util.AtomicFile
import java.io.File

/**
 * Commit UTF-8 text or restore the previous file; callers choose their IO dispatcher.
 *
 * android.util.AtomicFile commits by renaming its "<name>.new" file over the target and only logs
 * the failure. That rename cannot replace an existing file on Windows, where the unit tests run
 * under Robolectric, so every write after the first would silently keep the previous contents. The
 * leftover ".new" file distinguishes the two cases; the rename always succeeds on Android, so this
 * costs one existence check there.
 */
internal fun AtomicFile.writeUtf8(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val stream = startWrite()
    try {
        stream.write(bytes)
        finishWrite(stream)
    } catch (error: Exception) {
        failWrite(stream)
        throw error
    }
    val pending = File(baseFile.path + ".new")
    if (pending.exists()) {
        pending.delete()
        baseFile.writeBytes(bytes)
    }
}
