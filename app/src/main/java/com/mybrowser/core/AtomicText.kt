package com.mybrowser.core

import android.util.AtomicFile
import java.io.File

/** Propagate a failed rename and keep the preceding complete file until its replacement is ready. */
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
        java.nio.file.Files.move(pending.toPath(), baseFile.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
