package com.mybrowser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * An HLS task's bytes: every segment of the playlist, appended in order into one file.
 *
 * A transport rather than a second downloader: the playlist is resolved to one variant, each
 * segment goes through the HTTP engine's connection rules and byte budget, and the result is the
 * same "one part plus its total" every other transport leaves behind. Concatenated MPEG-TS plays as
 * one file; a playlist with `#EXT-X-MAP` is an fMP4 stream whose initialisation segment simply
 * comes first in the same file.
 *
 * The part is resumable: after each segment the number already appended is written to
 * `hls.state`, and a later attempt truncates back to it before continuing. The state carries a
 * digest of the media playlist, so a playlist that changed underneath starts over instead of
 * appending segments of a different stream.
 */
internal class HlsDownloadEngine(private val http: HttpDownloadEngine) : DownloadTransport {

    override suspend fun download(
        url: String,
        headers: DownloadRequestHeaders,
        requestedThreads: Int,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
    ): DownloadPayload = withContext(Dispatchers.IO) {
        if (!tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
            throw IOException("Unable to create download temporary directory")
        }
        val resolved = resolve(url, headers)
        if (!resolved.media.complete) throw IOException("HLS playlist is still live and has no end")
        val items = resolved.media.items
        val part = File(tempDirectory, PART_NAME)
        val stateFile = File(tempDirectory, STATE_NAME)
        val digest = DownloadCheckpoint.digest(resolved.text)
        val state = readState(stateFile)
        var index = 0
        var written = 0L
        if (state != null && state.digest == digest && state.index <= items.size &&
            part.isFile && part.length() >= state.bytes
        ) {
            index = state.index
            written = state.bytes
            RandomAccessFile(part, "rw").use { it.setLength(written) }
        } else {
            part.delete()
        }
        val progress = TransferProgress(onProgress)
        progress.update(written, 0L, force = true)
        val keys = HashMap<String, ByteArray>()
        val segmentFile = File(tempDirectory, "hls.segment")
        FileOutputStream(part, index > 0).use { sink ->
            while (index < items.size) {
                currentCoroutineContext().ensureActive()
                append(items[index], sink, headers, keys, segmentFile)
                written = part.length()
                writeState(stateFile, State(digest, index + 1, written))
                progress.update(written, 0L)
                index++
            }
        }
        segmentFile.delete()
        progress.update(written, 0L, force = true)
        // A playlist has no declared total: the bytes it produced are the total.
        return@withContext DownloadPayload(listOf(part), written, 1)
    }

    private suspend fun append(
        item: HlsPlaylist.Item,
        sink: FileOutputStream,
        headers: DownloadRequestHeaders,
        keys: MutableMap<String, ByteArray>,
        segmentFile: File,
    ) {
        val key = item.key
        if (key == null) {
            http.fetchTo(item.uri, headers, sink)
            return
        }
        if (!key.method.equals("AES-128", ignoreCase = true)) {
            throw IOException("Unsupported HLS encryption: ${key.method}")
        }
        val keyUri = key.uri ?: throw IOException("HLS key tag has no URI")
        val keyBytes = keys[keyUri] ?: fetchKey(keyUri, headers).also { keys[keyUri] = it }
        val iv = key.iv ?: HlsPlaylist.defaultIv(item.sequence)
        // The cipher needs the whole segment: CBC's final block is the padding. One segment is
        // bounded by the stream's own bitrate and segment length, so it is staged on disk rather
        // than held in memory.
        FileOutputStream(segmentFile).use { http.fetchTo(item.uri, headers, it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        }
        segmentFile.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val plain = cipher.update(buffer, 0, count)
                if (plain != null && plain.isNotEmpty()) sink.write(plain)
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) sink.write(tail)
        segmentFile.delete()
    }

    private suspend fun fetchKey(url: String, headers: DownloadRequestHeaders): ByteArray {
        val buffer = ByteArrayOutputStream()
        http.fetchTo(url, headers, buffer)
        return buffer.toByteArray().takeIf { it.size == 16 }
            ?: throw IOException("HLS AES-128 key is not 16 bytes")
    }

    private suspend fun fetchText(url: String, headers: DownloadRequestHeaders): String {
        val buffer = ByteArrayOutputStream()
        http.fetchTo(url, headers, buffer)
        val bytes = buffer.toByteArray()
        if (bytes.size > MAX_PLAYLIST_BYTES) throw IOException("HLS playlist is too large")
        return bytes.toString(Charsets.UTF_8)
    }

    /** Follows master playlists to the media playlist that actually lists segments. */
    private suspend fun resolve(url: String, headers: DownloadRequestHeaders): Resolved {
        var current = url
        var text = fetchText(current, headers)
        var hops = 0
        while (true) {
            val next = HlsPlaylist.variant(text, current) ?: break
            if (++hops > MAX_PLAYLIST_HOPS) throw IOException("HLS master playlist chain is too long")
            current = next
            text = fetchText(current, headers)
        }
        return Resolved(current, text, HlsPlaylist.media(text, current))
    }

    private data class Resolved(val url: String, val text: String, val media: HlsPlaylist.Media)

    private data class State(val digest: String, val index: Int, val bytes: Long)

    /** A half-written or corrupt state is the same as none: start the playlist again. */
    private fun readState(file: File): State? = runCatching {
        val lines = file.readLines()
        State(lines[0], lines[1].toInt(), lines[2].toLong())
    }.getOrNull()?.takeIf { it.index >= 0 && it.bytes >= 0 }

    private fun writeState(file: File, state: State) {
        file.writeText("${state.digest}\n${state.index}\n${state.bytes}\n")
    }

    companion object {
        /** The container a concatenated HLS stream is saved as. */
        const val MIME = "video/mp2t"

        private const val PART_NAME = "part-0"
        private const val STATE_NAME = "hls.state"
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
        private const val MAX_PLAYLIST_HOPS = 3

        /** Whether this address is an HLS merge rather than a file transfer. */
        fun handles(url: String, mimeType: String): Boolean = HlsPlaylist.isPlaylist(url, mimeType)

        /**
         * The name the merged file is saved as.
         *
         * A playlist URL names the playlist, not the video, so the extension has to describe what
         * the bytes actually are — otherwise the file opens in a text viewer and every player
         * refuses it.
         */
        fun outputName(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".m3u8") -> name.dropLast(5) + ".ts"
                '.' !in name -> "$name.ts"
                else -> name
            }
        }
    }
}
