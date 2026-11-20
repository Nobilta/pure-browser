package com.mybrowser.download

import java.io.IOException
import java.net.URI

internal class UnsupportedHlsException(message: String) : IOException(message)

/**
 * The parts of an HLS playlist a download needs, and nothing else.
 *
 * Two shapes are recognised: a master playlist, which is reduced to one variant, and a media
 * playlist, which is a list of segments with an optional initialisation segment and an optional
 * AES-128 key that changes part-way through. A playlist without `#EXT-X-ENDLIST` describes a live
 * stream that never reaches an end, so it is refused instead of saved as a truncated file.
 */
internal object HlsPlaylist {

    internal data class Key(val method: String, val uri: String, val iv: ByteArray?)

    /** One unit to append to the output: an `#EXT-X-MAP` initialisation segment or a segment. */
    internal data class Item(val uri: String, val key: Key?, val sequence: Long)

    internal data class Media(val items: List<Item>, val complete: Boolean)

    private val PLAYLIST_MIME = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    /** Whether a download of [url] is an HLS merge rather than a file transfer. */
    fun isPlaylist(url: String, mimeType: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        if (path.endsWith(".m3u8")) return true
        return mimeType.substringBefore(';').trim().lowercase() in PLAYLIST_MIME
    }

    /**
     * The variant a master playlist names, or null when [text] is already a media playlist.
     *
     * The highest declared bandwidth wins: a browser download has no screen or decoder budget to
     * weigh against it, and taking the first would silently save the lowest-quality rendition.
     */
    fun variant(text: String, base: String): String? {
        data class Variant(val uri: String, val bandwidth: Long, val audio: String?)
        val variants = mutableListOf<Variant>()
        var pendingAudio: String? = null
        val externalAudio = mutableSetOf<String>()
        val embeddedAudio = mutableSetOf<String>()
        var pendingBandwidth = -1L
        var sawStreamInfo = false
        for (line in text.lineSequence().map(String::trim)) {
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) && attribute(line, "TYPE")?.equals("AUDIO", ignoreCase = true) == true) {
                val groups = if (attribute(line, "URI") == null) embeddedAudio else externalAudio
                attribute(line, "GROUP-ID")?.let(groups::add)
            }
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                sawStreamInfo = true
                pendingBandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: -1L
                pendingAudio = attribute(line, "AUDIO")
                continue
            }
            if (line.startsWith("#")) continue
            if (!sawStreamInfo) continue
            variants += Variant(line, pendingBandwidth, pendingAudio)
            // The URI of one variant ends its declaration; a later bare line is not one.
            sawStreamInfo = false
            pendingBandwidth = -1L
        }
        if (variants.isEmpty()) return null
        val best = variants.filter { it.audio !in externalAudio || it.audio in embeddedAudio }
            .maxByOrNull { it.bandwidth } ?: throw UnsupportedHlsException("Separate HLS audio is not supported")
        return resolve(base, best.uri)
    }

    fun media(text: String, base: String): Media {
        val items = mutableListOf<Item>()
        var sequence = 0L
        var key: Key? = null
        var unsupportedKey = false
        var complete = false
        var pendingInitUri: String? = null
        var pendingInitKey: Key? = null
        var expectingSegment = false
        for (line in text.lineSequence().map(String::trim)) {
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val format = attribute(line, "KEYFORMAT")
                    if (format == null || format.equals("identity", ignoreCase = true)) {
                        key = key(line.substringAfter(':'), base)
                        unsupportedKey = false
                    } else {
                        // Different KEYFORMAT tags offer alternative ways to obtain the same key.
                        // Only another identity tag replaces the identity key (RFC 8216 4.3.2.4).
                        unsupportedKey = true
                    }
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    if (attribute(line, "BYTERANGE") != null) {
                        throw UnsupportedHlsException("HLS byte-range maps are not supported")
                    }
                    if (unsupportedKey && key == null) throw UnsupportedHlsException("Unsupported HLS key format")
                    if (key != null && key.method.equals("AES-128", ignoreCase = true) && key.iv == null) throw UnsupportedHlsException("Encrypted HLS map requires an IV")
                    pendingInitUri = attribute(line, "URI")?.let { resolve(base, it) }
                        ?: throw UnsupportedHlsException("HLS playlist has an initialization segment without a URI")
                    pendingInitKey = key
                }
                // A byte range addresses part of a file; appending the whole file would claim
                // to have saved a segment it did not.
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) ->
                    throw UnsupportedHlsException("HLS byte-range playlists are not supported")
                line.startsWith("#EXTINF", ignoreCase = true) -> expectingSegment = true
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> complete = true
                line.startsWith("#") -> Unit
                else -> {
                    if (!expectingSegment) continue
                    if (unsupportedKey && key == null) throw UnsupportedHlsException("Unsupported HLS key format")
                    if (pendingInitUri != null) {
                        items += Item(pendingInitUri, pendingInitKey, sequence)
                        pendingInitUri = null
                        pendingInitKey = null
                    }
                    items += Item(resolve(base, line), key, sequence)
                    sequence++
                    expectingSegment = false
                }
            }
        }
        if (items.isEmpty()) throw UnsupportedHlsException("HLS playlist has no segments")
        return Media(items, complete)
    }

    /** The IV for [sequence] when the key tag does not name one: its number, big-endian. */
    fun defaultIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (index in 15 downTo 8) {
            iv[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    fun parseIv(value: String?): ByteArray? {
        val hex = value?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (hex.length !in 1..32 || hex.any { it.digitToIntOrNull(16) == null }) return null
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { index -> padded.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun key(attributes: String, base: String): Key? {
        val method = attributeValue(attributes, "METHOD")
            ?: throw UnsupportedHlsException("HLS key tag has no METHOD")
        if (method.equals("NONE", ignoreCase = true)) return null
        if (!method.equals("AES-128", ignoreCase = true)) throw UnsupportedHlsException("Unsupported HLS key method: $method")
        val uri = attributeValue(attributes, "URI")?.let { resolve(base, it) }
            ?: throw UnsupportedHlsException("HLS key tag has no URI")
        // An IV that is present but malformed is a broken playlist, not a request to derive one:
        // falling back to the segment number here would decrypt into bytes nobody could play.
        val iv = attributeValue(attributes, "IV")
            ?.let { parseIv(it) ?: throw UnsupportedHlsException("HLS key IV is not 16 bytes") }
        return Key(method, uri, iv)
    }

    /** One attribute of a tag such as `#EXT-X-KEY:METHOD=AES-128,URI="key.bin"`. */
    private fun attribute(line: String, name: String): String? =
        attributeValue(line.substringAfter(':'), name)

    private fun attributeValue(attributes: String, name: String): String? {
        var index = 0
        while (index < attributes.length) {
            val start = index
            while (index < attributes.length && attributes[index] != '=' && attributes[index] != ',') index++
            val key = attributes.substring(start, index).trim()
            if (index >= attributes.length || attributes[index] == ',') {
                index++
                continue
            }
            index++
            val value = if (index < attributes.length && attributes[index] == '"') {
                val close = attributes.indexOf('"', index + 1)
                if (close < 0) return null
                attributes.substring(index + 1, close).also { index = close + 1 }
            } else {
                val end = attributes.indexOf(',', index).takeIf { it >= 0 } ?: attributes.length
                attributes.substring(index, end).trim().also { index = end }
            }
            if (key.equals(name, ignoreCase = true)) return value
            if (index < attributes.length && attributes[index] == ',') index++
        }
        return null
    }

    private fun resolve(base: String, reference: String): String =
        runCatching { URI(base).resolve(reference.trim()).toString() }
            .getOrElse { throw UnsupportedHlsException("HLS playlist references an invalid URL") }
}
