package com.mybrowser.cast

import java.net.URI

data class CastMediaSource(val url: String, val mime: String) {
    companion object {
        fun parse(url: String, kind: String): CastMediaSource? = runCatching {
            require(url.length <= 8192 && url.none { it.isISOControl() })
            val uri = URI(url)
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            val extension = uri.path.orEmpty().substringAfterLast('.').lowercase()
            val mime = when {
                kind == "HLS" -> "application/x-mpegURL"
                kind == "DASH" -> "application/dash+xml"
                extension in setOf("mp4", "m4v", "mov") -> "video/mp4"
                extension == "webm" -> "video/webm"
                extension == "mp3" -> "audio/mpeg"
                extension in setOf("m4a", "aac") -> "audio/mp4"
                else -> error("Unsupported receiver media format")
            }
            CastMediaSource(url, mime)
        }.getOrNull()
    }
}
