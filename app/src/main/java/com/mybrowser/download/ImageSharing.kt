package com.mybrowser.download

import android.content.ClipData
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
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
        referer: String?,
        cookie: String?,
        chooserTitle: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val name = "share-${System.currentTimeMillis()}${extensionFor(url)}"
        // Directly in the capture directory: startup deletes its files, so a share from a private
        // session does not survive a restart either.
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, name)
        // A one-shot fetch outside the download budget: the user is waiting for a share sheet, and
        // the transfer policy (threads, speed limit) describes downloads that keep running.
        try {
            FileOutputStream(file).use { sink ->
                HttpDownloadEngine().fetchTo(url, DownloadRequestHeaders(userAgent.orEmpty(), cookie, referer), sink)
            }
        } catch (cancelled: CancellationException) {
            file.delete()
            throw cancelled
        } catch (_: IOException) {
            file.delete()
            return@withContext false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + CAPTURE_AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeFor(url))
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

    private fun extensionFor(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url.substringBefore('?').substringBefore('#'))
        return if (extension.isNullOrBlank()) ".jpg" else ".$extension"
    }

    private fun mimeFor(url: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionFor(url).removePrefix("."))
            ?: "image/*"
}
