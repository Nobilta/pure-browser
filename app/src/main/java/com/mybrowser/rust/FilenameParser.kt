package com.mybrowser.rust

import android.util.Log

object FilenameParser {
    init {
        try {
            System.loadLibrary("mybrowser_filename_parser")
        } catch (e: UnsatisfiedLinkError) {
            // This is an optional legacy integration; the canonical DownloadManager path
            // uses download.FilenameParser and does not need this .so. Avoid dumping a full
            // stack trace on every class load when the opt-in library is absent.
            Log.i("LegacyFilenameParser", "native parser unavailable; using fallback")
        }
    }

    /**
     * Parse filename from Content-Disposition header and URL
     * Falls back to Kotlin implementation if native library is unavailable
     */
    fun parseFilename(contentDisposition: String, url: String): String {
        return try {
            nativeParseFilename(contentDisposition, url).takeIf { it.isNotBlank() }
                ?: parseFilenameFallback(contentDisposition, url)
        } catch (_: UnsatisfiedLinkError) {
            parseFilenameFallback(contentDisposition, url)
        } catch (e: Exception) {
            // Fallback to simple parsing if Rust module fails
            parseFilenameFallback(contentDisposition, url)
        }
    }

    private external fun nativeParseFilename(contentDisposition: String, url: String): String

    private fun parseFilenameFallback(contentDisposition: String, url: String): String {
        // Simple fallback implementation
        if (contentDisposition.contains("filename=")) {
            val start = contentDisposition.indexOf("filename=") + 9
            val end = contentDisposition.indexOf(";", start).takeIf { it > 0 } ?: contentDisposition.length
            return contentDisposition.substring(start, end).trim('"', ' ')
        }

        // Extract from URL
        return url.substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotEmpty() && it.contains('.') }
            ?: "download"
    }
}
