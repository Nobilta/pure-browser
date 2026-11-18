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
 * same "one part plus its total" every other transport leaves behind. An initialization segment,
 * when present, comes first in the merged file.
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
    ): DownloadPayload = merge(url, headers, tempDirectory, onProgress, playlistText = null)

    /**
     * A playlist whose text arrived with the request that created the task.
     *
     * A handed-over response can only be read once, so the text it carried is the only copy; the
     * segments it names are ordinary requests and still retry normally.
     */
    internal suspend fun downloadHandedOver(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
        playlistText: String,
    ): DownloadPayload = merge(url, headers, tempDirectory, onProgress, playlistText)

    private suspend fun merge(
        url: String,
        headers: DownloadRequestHeaders,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
        playlistText: String?,
    ): DownloadPayload = withContext(Dispatchers.IO) {
        if (!tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
            throw IOException("Unable to create download temporary directory")
        }
        val resolved = resolve(url, headers, playlistText)
        if (!resolved.media.complete) throw UnsupportedHlsException("Live HLS playlists are not supported")
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
        FileOutputStream(part, index > 0).use { sink ->
            while (index < items.size) {
                currentCoroutineContext().ensureActive()
                append(items[index], sink, headers, keys, url)
                written = part.length()
                writeState(stateFile, State(digest, index + 1, written))
                progress.update(written, 0L)
                index++
            }
        }
        progress.update(written, 0L, force = true)
        // A playlist has no declared total: the bytes it produced are the total.
        return@withContext DownloadPayload(listOf(part), written, 1, HlsContainer.detect(part))
    }

    private suspend fun append(
        item: HlsPlaylist.Item,
        sink: FileOutputStream,
        headers: DownloadRequestHeaders,
        keys: MutableMap<String, ByteArray>,
        cookieOrigin: String,
    ) {
        val key = item.key
        if (key == null) {
            http.fetchTo(item.uri, headers, sink, cookieOrigin = cookieOrigin)
            return
        }
        if (!key.method.equals("AES-128", ignoreCase = true)) {
            throw UnsupportedHlsException("Unsupported HLS encryption: ${key.method}")
        }
        val keyUri = key.uri
        val keyBytes = keys[keyUri] ?: fetchKey(keyUri, headers, cookieOrigin).also { keys[keyUri] = it }
        val iv = key.iv ?: HlsPlaylist.defaultIv(item.sequence)
        // CBC only needs the final block to strip its padding, which arrives with the end of the
        // stream: the plaintext is written as the ciphertext is read, so the segment is neither
        // buffered whole in memory nor staged on disk first.
        val decrypting = DecryptingSink(sink, keyBytes, iv)
        http.fetchTo(item.uri, headers, decrypting, cookieOrigin = cookieOrigin)
        decrypting.finish()
    }

    private suspend fun fetchKey(url: String, headers: DownloadRequestHeaders, cookieOrigin: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        http.fetchTo(url, headers, buffer, cookieOrigin = cookieOrigin, maxBytes = 16)
        return buffer.toByteArray().takeIf { it.size == 16 }
            ?: throw IOException("HLS AES-128 key is not 16 bytes")
    }

    private suspend fun fetchText(
        url: String,
        headers: DownloadRequestHeaders,
        cookieOrigin: String,
    ): FetchedText {
        val buffer = ByteArrayOutputStream()
        val fetched = http.fetchTo(url, headers, buffer, cookieOrigin = cookieOrigin, maxBytes = MAX_PLAYLIST_BYTES)
        return FetchedText(fetched.url, buffer.toString("UTF-8"))
    }

    private data class FetchedText(val url: String, val text: String)

    /**
     * Follows master playlists to the media playlist that actually lists segments.
     *
     * Every relative reference is resolved against the address the playlist was finally served
     * from, not the one that was requested: a playlist that redirects to another directory or
     * host has its segments next to where it ended up.
     */
    private suspend fun resolve(
        url: String,
        headers: DownloadRequestHeaders,
        initial: String?,
    ): Resolved {
        var current = url
        var text = initial ?: fetchText(current, headers, url).also { current = it.url }.text
        var hops = 0
        while (true) {
            val next = HlsPlaylist.variant(text, current) ?: break
            if (++hops > MAX_PLAYLIST_HOPS) throw IOException("HLS master playlist chain is too long")
            val variant = fetchText(next, headers, url)
            current = variant.url
            text = variant.text
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
        private const val PART_NAME = "part-0"

        /**
         * Where a playlist that arrived with the request is kept, so a paused task continues from
         * the same text (and the same segment list) instead of asking for a consumed response.
         */
        const val PLAYLIST_NAME = "hls.playlist"
        private const val STATE_NAME = "hls.state"
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
        private const val MAX_PLAYLIST_HOPS = 3

        /** Whether this address is an HLS merge rather than a file transfer. */
        fun handles(url: String, mimeType: String): Boolean = HlsPlaylist.isPlaylist(url, mimeType)

        /** The playlist a response body carries, bounded like every other playlist read. */
        internal fun playlistText(body: java.io.InputStream): String {
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = body.read(buffer)
                if (count < 0) break
                if (collected.size() + count > MAX_PLAYLIST_BYTES) throw IOException("HLS playlist is too large")
                collected.write(buffer, 0, count)
            }
            return collected.toString("UTF-8")
        }

        /** Suggested name before the media container is known. */
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

internal enum class HlsContainer(val mimeType: String, private val extension: String) {
    MPEG_TS("video/mp2t", "ts"), MP4("video/mp4", "mp4");

    fun filename(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase()
        val stem = when (suffix) {
            "" -> name
            "ts", "mp4", "m3u8" -> name.substringBeforeLast('.')
            else -> return name
        }
        return FilenameParser.sanitize("$stem.$extension")
    }

    companion object {
        fun detect(part: File): HlsContainer = RandomAccessFile(part, "r").use { file ->
            if (file.length() < 8) return MPEG_TS
            file.seek(4)
            // The first box of an HLS fragmented MP4 initialization section is 'ftyp'.
            if (file.readInt() == 0x66747970) MP4 else MPEG_TS
        }
    }
}

/**
 * Decrypts what is written to it and writes the plaintext on.
 *
 * It wraps the merged file for one segment at a time, so it must not close the sink; [finish]
 * is what lets CBC strip the final block's padding.
 */
private class DecryptingSink(
    private val sink: java.io.OutputStream,
    key: ByteArray,
    iv: ByteArray,
) : java.io.OutputStream() {
    private val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    override fun write(b: ByteArray, off: Int, count: Int) {
        val plain = cipher.update(b, off, count)
        if (plain != null && plain.isNotEmpty()) sink.write(plain)
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    fun finish() {
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) sink.write(tail)
    }
}
