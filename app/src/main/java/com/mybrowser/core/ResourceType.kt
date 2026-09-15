package com.mybrowser.core

import android.webkit.WebResourceRequest

/**
 * Resource kinds the filter engine understands.
 *
 * Ordinals cross the JNI boundary as ints, so the declaration order here is load-bearing — it
 * must match `resource_from_ordinal` in `rust/adblock/src/lib.rs`. Append new kinds at the end.
 *
 * This lives in `core` rather than nested in `NativeFilter` because it is shared vocabulary:
 * the developer-tools network log stores the ordinal and maps it back to a display category,
 * so a `filter`-owned enum made `core` depend on `filter` while `filter` already depends on
 * `core`. The JNI symbol names are unaffected — they encode the `NativeFilter` class, not this
 * enum.
 */
enum class ResourceType {
    DOCUMENT,
    SUBDOCUMENT,
    SCRIPT,
    STYLESHEET,
    IMAGE,
    FONT,
    MEDIA,
    XML_HTTP_REQUEST,
    PING,
    WEB_SOCKET,
    OTHER,
}

/**
 * Classifies a request from Chromium's Fetch Metadata, then the `Accept` header, then the file
 * extension. Each fallback is load-bearing: CDN and API URLs often carry no extension, and
 * older WebViews send no Fetch Metadata at all.
 */
internal fun classifyResourceType(request: WebResourceRequest): ResourceType {
    if (request.isForMainFrame) return ResourceType.DOCUMENT
    val headers = request.requestHeaders
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }
        ?.value.orEmpty().lowercase(java.util.Locale.ROOT)
    // Chromium's destination is more reliable than file extensions (CDNs and
    // APIs often have none). Missing Fetch Metadata on older WebViews falls back.
    when (header("Sec-Fetch-Dest")) {
        "document", "iframe", "frame" -> return ResourceType.SUBDOCUMENT
        "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.SCRIPT
        "style" -> return ResourceType.STYLESHEET
        "image" -> return ResourceType.IMAGE
        "font" -> return ResourceType.FONT
        "audio", "video", "track" -> return ResourceType.MEDIA
        "empty" -> when (header("Sec-Fetch-Mode")) {
            "cors", "same-origin" -> return ResourceType.XML_HTTP_REQUEST
        }
    }
    if (header("X-Requested-With") == "xmlhttprequest") return ResourceType.XML_HTTP_REQUEST
    val accept = header("Accept")
    when {
        accept.startsWith("text/css") -> return ResourceType.STYLESHEET
        accept.startsWith("image/") -> return ResourceType.IMAGE
        accept.startsWith("video/") || accept.startsWith("audio/") ->
            return ResourceType.MEDIA
        accept.startsWith("font/") || accept.contains("font/woff") ->
            return ResourceType.FONT
        accept.contains("text/html") -> return ResourceType.SUBDOCUMENT
        accept.contains("application/json") || accept.contains("+json") || accept.contains("application/graphql") ->
            return ResourceType.XML_HTTP_REQUEST
    }

    val ext = request.url.path.orEmpty().substringAfterLast('.', "").lowercase()
    return when (ext) {
        "js", "mjs" -> ResourceType.SCRIPT
        "css" -> ResourceType.STYLESHEET
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif" ->
            ResourceType.IMAGE
        "woff", "woff2", "ttf", "otf", "eot" -> ResourceType.FONT
        "mp4", "webm", "m4v", "mov", "m3u8", "mpd", "mp3", "m4a", "aac", "ogg" ->
            ResourceType.MEDIA
        "json" -> ResourceType.XML_HTTP_REQUEST
        "html", "htm" -> ResourceType.SUBDOCUMENT
        else -> ResourceType.OTHER
    }
}
