package com.mybrowser.download

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Non-replayable HTTP entity metadata; cookies and authorization headers never enter it.
 *
 * The URLs this is validated against are stored as digests, not as text. This sidecar lives
 * beside the partial bytes for as long as a task is paused, so a plain URL here would put a
 * private session's query string — the part that carries tokens — on disk outside the record
 * that is deliberately redacted, and it would outlive the session that produced it. Digests
 * validate the same thing: that the resumed entity is the one the bytes came from.
 */
internal data class DownloadCheckpoint(
    val urlDigest: String,
    val total: Long,
    val validator: String,
    val threads: Int,
    val entityUrlDigest: String = urlDigest,
) {
    fun save(directory: File) {
        val next = File(directory, "$NAME.new")
        FileOutputStream(next).use { output ->
            Properties().apply {
                setProperty("version", VERSION)
                setProperty("urlDigest", urlDigest)
                setProperty("total", total.toString())
                setProperty("validator", validator)
                setProperty("threads", threads.toString())
                setProperty("entityUrlDigest", entityUrlDigest)
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
        private const val VERSION = "2"

        /** Base name of the sidecar, so its location can be asserted from outside. */
        internal const val FILE_NAME = NAME

        internal fun digest(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

        fun read(directory: File, expectedUrl: String): DownloadCheckpoint? = runCatching {
            val file = File(directory, NAME)
            if (!file.isFile || file.length() > 128 * 1024) return null
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val total = props.getProperty("total")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val validator = props.getProperty("validator")?.takeIf { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) } ?: return null
            val threads = props.getProperty("threads")?.toIntOrNull()?.takeIf { it in 1..16 } ?: return null
            val ranges = DownloadRanges.split(total, threads)
            if (ranges.indices.any { index -> File(directory, "part-$index").length() > ranges[index].last - ranges[index].first + 1 }) return null
            when (props.getProperty("version")) {
                VERSION -> {
                    val urlDigest = props.getProperty("urlDigest")?.takeIf { it == digest(expectedUrl) } ?: return null
                    val entityUrlDigest = props.getProperty("entityUrlDigest")?.takeIf { it.length == urlDigest.length } ?: return null
                    DownloadCheckpoint(urlDigest, total, validator, threads, entityUrlDigest)
                }
                // Written by the build that persisted the URLs themselves. Read once so an upgrade
                // does not throw away partial bytes someone paused; the next save writes digests.
                "1" -> {
                    if (props.getProperty("url") != expectedUrl) return null
                    val entityUrl = props.getProperty("entityUrl")?.takeIf { it.length <= 8192 } ?: return null
                    DownloadCheckpoint(digest(expectedUrl), total, validator, threads, digest(entityUrl))
                }
                else -> null
            }
        }.getOrNull()
    }
}
