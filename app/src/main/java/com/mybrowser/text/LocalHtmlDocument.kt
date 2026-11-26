package com.mybrowser.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import android.webkit.MimeTypeMap
import androidx.webkit.WebViewAssetLoader
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

/** One granted HTML file, retained by its tab for this browser session only. */
class LocalHtmlDocument private constructor(val uri: Uri, val name: String, val url: String) {
    @Volatile internal var resourceTree: Uri? = null
    fun matches(candidate: String): Boolean = candidate.substringBefore('#').substringBefore('?') == url

    fun loader(context: Context): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(url.toUri().host!!)
        .addPathHandler("/", WebViewAssetLoader.PathHandler { path ->
            try {
                val resource = if (path == name) uri else resourceTree?.let { LocalHtmlResources.resolve(context, it, path) }
                    ?: return@PathHandler missing()
                val type = if (path == name) "text/html" else
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('/').substringAfterLast('.', "").lowercase())
                        ?: context.contentResolver.getType(resource) ?: "application/octet-stream"
                val input = context.contentResolver.openInputStream(resource) ?: return@PathHandler missing()
                // Let Chromium read the original BOM/meta charset. Never escape HTML as plain text.
                WebResourceResponse(type, null, input).apply {
                    responseHeaders = mapOf(
                        "Cache-Control" to "no-store",
                        "Referrer-Policy" to "same-origin",
                    )
                }
            } catch (_: IOException) {
                missing()
            } catch (_: SecurityException) {
                missing()
            }
        }).build()

    companion object {
        private const val HOST_SUFFIX = ".local.pure-browser.invalid"

        fun isLocalUrl(url: String?): Boolean = url != null &&
            url.toUri().host?.endsWith(HOST_SUFFIX) == true

        fun displayName(url: String): String = url.toUri().lastPathSegment.orEmpty()

        fun displayTitle(url: String, title: String?): String =
            if (isLocalUrl(url) && (title.isNullOrBlank() || title.contains(HOST_SUFFIX, ignoreCase = true)))
                displayName(url)
            else title ?: url

        fun accepts(type: String?): Boolean = type?.substringBefore(';')?.trim()
            ?.equals("text/html", ignoreCase = true) == true

        /** Runs on IO; opening once also checks the sender's actual URI grant. */
        fun open(context: Context, uri: Uri): LocalHtmlDocument {
            require(uri.scheme == "content")
            context.contentResolver.openInputStream(uri)?.use { } ?: throw IOException("No stream")
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "index.html"
            return LocalHtmlDocument(uri, name, "https://${UUID.randomUUID()}$HOST_SUFFIX/${Uri.encode(name)}").apply {
                resourceTree = LocalHtmlResources.find(context, uri)
            }
        }

        fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }
}
