package com.mybrowser.text

import android.content.Context
import android.util.Log
import android.net.Uri
import android.provider.OpenableColumns
import com.mybrowser.R
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What the activity needs after reading a local text file. */
internal sealed interface TextContent {
    data class Ready(val title: String, val body: String) : TextContent
    data class Failed(val message: Int) : TextContent
}

/**
 * Reads a content URI as text. The file is read once, bounded, and never copied to disk;
 * `allowContentAccess` stays off for the WebView.
 */
internal object PlainTextLoader {
    /** A page of text has no useful upper bound, but a phone must not layout a whole disk image. */
    const val MAX_BYTES = 5 * 1024 * 1024

    fun load(context: Context, uri: Uri): TextContent {
        if (uri.scheme != "content") {
            return TextContent.Failed(R.string.plain_text_open_failed)
        }
        val title = displayName(context, uri)
        val bytes = try {
            read(context, uri, MAX_BYTES + 1)
        } catch (error: Exception) {
            Log.w("PlainText", "Could not read $uri", error)
            return TextContent.Failed(R.string.plain_text_open_failed)
        }
        if (bytes.size > MAX_BYTES) return TextContent.Failed(R.string.plain_text_too_large)
        if (TextDecoder.looksBinary(bytes)) {
            return TextContent.Failed(R.string.plain_text_not_text)
        }
        return TextContent.Ready(title, TextDecoder.decode(bytes))
    }

    private fun read(context: Context, uri: Uri, limit: Int): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (output.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IOException("No stream")

    private fun displayName(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
        return (name ?: "Text").substringAfterLast('/').take(120).ifBlank { "Text" }
    }
}
