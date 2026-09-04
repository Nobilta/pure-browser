package com.mybrowser.media

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.core.net.toUri
import java.util.Locale

/**
 * Recognises playable media from the requests a page makes.
 *
 * A page's <video> tag rarely holds a usable URL: the stream is assembled by a player
 * script, so the only reliable signal is the network traffic itself. Every subresource
 * request passes through [inspect]; anything that looks like media becomes a cast
 * candidate.
 *
 * Classification is by URL shape and, when present, the Accept header. The response
 * Content-Type would be more authoritative but is not available at this point in the
 * WebView API — shouldInterceptRequest is called before the response exists.
 */
object MediaSniffer {

    /** Ranked so a manifest always outranks the segments it points at. */
    enum class Kind(val rank: Int) {
        HLS(3),
        DASH(3),
        PROGRESSIVE(2),
        AUDIO(1),
    }

    data class Candidate(
        val url: String,
        val kind: Kind,
        val label: String,
        /** Page the request originated from, for the Referer a DLNA renderer may need. */
        val pageUrl: String?,
    ) {
        val isStream: Boolean get() = kind == Kind.HLS || kind == Kind.DASH
    }

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "ts", "3gp", "mpg", "mpeg", "wmv",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

    /**
     * Segment files are individually useless — casting one plays a few seconds. They are
     * recognised only to be discarded, and to infer the manifest when it was missed.
     */
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s")

    private val BLOCKED_HOST_HINTS = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "adservice", "/ads/", "/ad/",
    )

    fun inspect(request: WebResourceRequest, pageUrl: String?): Candidate? {
        // Only GET can be replayed by a renderer; a POSTed media URL is not castable.
        if (!request.method.equals("GET", ignoreCase = true)) return null
        return inspect(request.url.toString(), pageUrl, request.requestHeaders)
    }

    fun inspect(
        url: String,
        pageUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Candidate? {
        val cleanUrl = url.trim()
        if (cleanUrl.length !in 1..MAX_URL_LENGTH ||
            cleanUrl.any { it.isWhitespace() || it.isISOControl() } ||
            (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                !cleanUrl.startsWith("https://", ignoreCase = true))
        ) return null
        if (BLOCKED_HOST_HINTS.any { cleanUrl.contains(it, ignoreCase = true) }) return null

        val uri = runCatching { cleanUrl.toUri() }.getOrNull() ?: return null
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        val extension = path.substringAfterLast('.', "").takeIf { it.length in 1..5 }

        val kind = when {
            path.endsWith(".m3u8") -> Kind.HLS
            path.endsWith(".mpd") -> Kind.DASH
            extension in SEGMENT_EXTENSIONS && extension != null -> return null
            extension in VIDEO_EXTENSIONS -> Kind.PROGRESSIVE
            extension in AUDIO_EXTENSIONS -> Kind.AUDIO
            else -> fromHeaders(headers) ?: return null
        }

        return Candidate(
            url = cleanUrl,
            kind = kind,
            label = labelFor(uri, kind),
            pageUrl = pageUrl?.trim()?.take(MAX_URL_LENGTH),
        )
    }

    /**
     * Extensionless media URLs are common on CDNs that route by query parameter. The
     * Accept header a media element sends is the remaining hint.
     */
    private fun fromHeaders(headers: Map<String, String>): Kind? {
        val accept = headers.entries
            .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase(Locale.ROOT)
            ?: return null
        return when {
            accept.contains("application/vnd.apple.mpegurl") -> Kind.HLS
            accept.contains("application/dash+xml") -> Kind.DASH
            accept.startsWith("video/") -> Kind.PROGRESSIVE
            accept.startsWith("audio/") -> Kind.AUDIO
            else -> null
        }
    }

    private fun labelFor(uri: Uri, kind: Kind): String {
        val name = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?: uri.host
            ?: "media"
        val suffix = when (kind) {
            Kind.HLS -> "HLS"
            Kind.DASH -> "DASH"
            Kind.PROGRESSIVE -> "视频"
            Kind.AUDIO -> "音频"
        }
        return "$suffix · ${name.take(MAX_LABEL_LENGTH)}"
    }

    private const val MAX_URL_LENGTH = 8_192
    private const val MAX_LABEL_LENGTH = 160
}
