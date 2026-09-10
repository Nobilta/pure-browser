package com.mybrowser.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class PublishedDownload(
    val uri: Uri,
    val displayName: String,
)

/** Publishes verified temporary parts to public Downloads or a persisted SAF tree. */
internal class DownloadDestinationWriter(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun publish(
        settings: DownloadSettings,
        preferredName: String,
        mimeType: String,
        parts: List<File>,
        onProgress: (Int) -> Unit = {},
    ): PublishedDownload = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "A completed download must contain at least one part" }
        val total = parts.sumOf { it.length() }
        if (settings.destinationMode == DownloadDestinationMode.SYSTEM_DOWNLOADS) {
            val available = Environment.getExternalStorageDirectory().usableSpace
            if (available > 0 && total > available) throw IOException("Not enough space to save the completed download")
        }
        val published = when (settings.destinationMode) {
            DownloadDestinationMode.SYSTEM_DOWNLOADS -> createInSystemDownloads(
                preferredName,
                mimeType,
            )
            DownloadDestinationMode.CUSTOM_DIRECTORY -> createInCustomDirectory(
                settings,
                preferredName,
                mimeType,
            )
        }

        try {
            val output = resolver.openOutputStream(published.uri, "w")
                ?: throw IOException("Selected destination cannot be opened for writing")
            output.buffered().use { stream ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copied = 0L
                var lastProgress = -1
                parts.forEach { part ->
                    part.inputStream().buffered().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            stream.write(buffer, 0, count)
                            copied += count
                            val percent = if (total > 0) (copied.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0
                            if (percent != lastProgress) { lastProgress = percent; onProgress(percent) }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            markCompleteIfMediaStore(published.uri)
            published
        } catch (error: Exception) {
            delete(published.uri.toString())
            throw error
        }
    }

    fun delete(uriString: String?): Boolean {
        val uri = uriString?.let { runCatching { it.toUri() }.getOrNull() } ?: return true
        return runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
    }

    private fun createInSystemDownloads(
        preferredName: String,
        mimeType: String,
    ): PublishedDownload {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
        val displayName = uniqueName(preferredName) { candidate ->
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(candidate, relativePath),
                    null,
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Unable to create a file in system Downloads")
        return PublishedDownload(uri, displayName)
    }

    private fun createInCustomDirectory(
        settings: DownloadSettings,
        preferredName: String,
        mimeType: String,
    ): PublishedDownload {
        val treeUri = settings.customTreeUri
            ?.let { runCatching { it.toUri() }.getOrNull() }
            ?: throw IOException("Custom download directory is no longer available")
        val hasWritePermission = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
        if (!hasWritePermission) throw IOException("Custom download directory permission expired")

        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("Invalid custom download directory", it) }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val existingNames = HashSet<String>()
        runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(existingNames::add)
            }
        }
        val displayName = uniqueName(preferredName, existingNames::contains)
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
            ?: throw IOException("Unable to create a file in the selected directory")
        val providerName = runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return PublishedDownload(uri, providerName ?: displayName)
    }

    private fun markCompleteIfMediaStore(uri: Uri) {
        if (uri.authority != MediaStore.AUTHORITY) return
        val updated = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        if (updated <= 0) throw IOException("Unable to publish completed download")
    }

    private fun uniqueName(preferredName: String, exists: (String) -> Boolean): String {
        val clean = preferredName.take(MAX_FILENAME_LENGTH).ifBlank { "download" }
        if (!exists(clean)) return clean
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        val rawStem = clean.substring(0, dot)
        val extension = clean.substring(dot)
        for (index in 1..MAX_DUPLICATE_ATTEMPTS) {
            val suffix = " ($index)"
            val stem = rawStem.take((MAX_FILENAME_LENGTH - extension.length - suffix.length).coerceAtLeast(1))
            val candidate = "$stem$suffix$extension"
            if (!exists(candidate)) return candidate
        }
        throw IOException("Too many files share this download name")
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_FILENAME_LENGTH = 127
        const val MAX_DUPLICATE_ATTEMPTS = 10_000
    }
}
