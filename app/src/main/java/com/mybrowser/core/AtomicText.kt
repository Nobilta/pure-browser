package com.mybrowser.core

import android.util.AtomicFile
import java.io.File

/**
 * android.util.AtomicFile commits by renaming its "<name>.new" file over the target and only logs
 * the failure. That rename replaces an existing file on Android, but not on Windows, where the unit
 * tests run under Robolectric — there every write after the first would silently keep the previous
 * contents. The leftover ".new" file tells the two cases apart, and the repair runs only where the
 * platform rename cannot do the job, so the shipped code path is unchanged.
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
    if (RENAME_REPLACES_EXISTING_FILE) return
    val pending = File(baseFile.path + ".new")
    if (pending.exists()) {
        pending.delete()
        baseFile.writeBytes(bytes)
    }
}

/** File.renameTo replaces an existing target on POSIX hosts; Windows refuses and keeps the old file. */
private val RENAME_REPLACES_EXISTING_FILE =
    !System.getProperty("os.name").orEmpty().startsWith("Windows")
