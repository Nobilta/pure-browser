package com.mybrowser.download

import android.content.ClipData
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import com.mybrowser.tabs.MediaNavigation
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Hands an image on the page to another app through the share sheet.
 *
 * The bytes are fetched into the FileProvider's cache, not system Downloads: sharing a picture
 * must not leave a copy in the gallery, and the provider is already the one capture/upload uses.
 * The page's own headers are replayed because a hotlink-protected image is exactly the case where
 * a plain URL fetch returns an error page instead of the picture.
 */
internal object ImageSharing {
    suspend fun share(
        context: Context,
        url: String,
        userAgent: String?,
        navigation: MediaNavigation?,
        cookie: String?,
        chooserTitle: String,
    ): Boolean = withContext(Dispatchers.IO) {
        // Startup clears this directory, including images shared from a private session.
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        var file = File(directory, "share-${java.util.UUID.randomUUID()}.tmp")
        val mime: String
        try {
            val fetched = FileOutputStream(file).use { sink ->
                HttpDownloadEngine().fetchTo(url, DownloadRequestHeaders(userAgent.orEmpty(), cookie, null),
                    sink, maxBytes = MAX_IMAGE_BYTES, navigation = navigation)
            }
            val responseType = fetched.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            mime = when {
                responseType.startsWith("image/") -> responseType
                responseType.isEmpty() || responseType == "application/octet-stream" -> mimeFor(fetched.url)
                else -> throw IOException("The response is not an image")
            }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            val destination = File(directory, "${file.nameWithoutExtension}.$extension")
            if (!file.renameTo(destination)) throw IOException("Unable to name the shared image")
            file = destination
        } catch (cancelled: CancellationException) {
            file.delete()
            throw cancelled
        } catch (_: IOException) {
            file.delete()
            return@withContext false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + CAPTURE_AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser reads the URI from ClipData when it targets another app.
        send.clipData = ClipData.newRawUri("image", uri)
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(Intent.createChooser(send, chooserTitle))
                true
            } catch (_: ActivityNotFoundException) {
                file.delete()
                false
            }
        }
    }

    /** The directory the FileProvider grant and the startup cleanup both cover. */
    private const val SHARE_DIRECTORY = "web-capture"
    private const val CAPTURE_AUTHORITY = ".captures"

    private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

    private fun mimeFor(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url.substringBefore('?').substringBefore('#')).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith("image/") } ?: "image/*"
    }
}
