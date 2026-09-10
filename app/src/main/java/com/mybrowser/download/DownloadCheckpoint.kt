package com.mybrowser.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Non-replayable HTTP entity metadata; cookies and authorization headers never enter it. */
internal data class DownloadCheckpoint(val url: String, val total: Long, val validator: String, val threads: Int,
    val entityUrl: String = url) {
    fun save(directory: File) {
        val next = File(directory, "$NAME.new")
        FileOutputStream(next).use { output ->
            Properties().apply {
                setProperty("version", "1"); setProperty("url", url); setProperty("total", total.toString())
                setProperty("validator", validator); setProperty("threads", threads.toString())
                setProperty("entityUrl", entityUrl)
            }.store(output, null)
            output.fd.sync()
        }
        try {
            Files.move(next.toPath(), File(directory, NAME).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(next.toPath(), File(directory, NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val NAME = "resume.properties"
        fun read(directory: File, expectedUrl: String): DownloadCheckpoint? = runCatching {
            val file = File(directory, NAME)
            if (!file.isFile || file.length() > 128 * 1024) return null
            val props = Properties().apply { file.inputStream().use { load(it) } }
            if (props.getProperty("version") != "1" || props.getProperty("url") != expectedUrl) return null
            val total = props.getProperty("total")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val validator = props.getProperty("validator")?.takeIf { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) } ?: return null
            val threads = props.getProperty("threads")?.toIntOrNull()?.takeIf { it in 1..16 } ?: return null
            val ranges = DownloadRanges.split(total, threads)
            if (ranges.indices.any { index -> File(directory, "part-$index").length() > ranges[index].last - ranges[index].first + 1 }) return null
            val entityUrl = props.getProperty("entityUrl")?.takeIf { it.length <= 8192 } ?: return null
            DownloadCheckpoint(expectedUrl, total, validator, threads, entityUrl)
        }.getOrNull()
    }
}
