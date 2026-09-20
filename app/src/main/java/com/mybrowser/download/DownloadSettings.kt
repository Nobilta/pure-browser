package com.mybrowser.download

import com.mybrowser.data.commitConfirmed
import android.content.res.Resources
import com.mybrowser.R
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri

/** Where newly-created downloads are published after the transfer completes. */
enum class DownloadDestinationMode {
    SYSTEM_DOWNLOADS,
    CUSTOM_DIRECTORY,
}

/**
 * Persisted download preferences.
 *
 * A Storage Access Framework tree URI is deliberately stored instead of a filesystem path.
 * Android 10+ does not grant applications arbitrary path access, while a persisted tree grant
 * remains writable across process restarts without broad storage permissions.
 */
data class DownloadSettings(
    val destinationMode: DownloadDestinationMode = DownloadDestinationMode.SYSTEM_DOWNLOADS,
    val customTreeUri: String? = null,
    val customDirectoryLabel: String? = null,
    val threadCount: Int = DEFAULT_DOWNLOAD_THREADS,
    val unmeteredOnly: Boolean = false,
) {
    val destinationLabel: String
        get() = if (destinationMode == DownloadDestinationMode.CUSTOM_DIRECTORY) {
            customDirectoryLabel?.takeIf { it.isNotBlank() } ?: CUSTOM_DIRECTORY_LABEL
        } else {
            SYSTEM_DIRECTORY_LABEL
        }

    fun displayDestinationLabel(resources: Resources): String = localizeDownloadDirectory(resources, destinationLabel)
}

/** Small SharedPreferences boundary used by both the settings UI and download engine. */
class DownloadSettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DownloadSettings {
        val treeUri = prefs.getString(KEY_CUSTOM_TREE_URI, null)
            ?.takeIf { value -> runCatching { value.toUri().scheme == "content" }.getOrDefault(false) }
        val requestedMode = runCatching {
            DownloadDestinationMode.valueOf(
                prefs.getString(KEY_DESTINATION_MODE, null).orEmpty(),
            )
        }.getOrDefault(DownloadDestinationMode.SYSTEM_DOWNLOADS)

        // A copied preference or revoked provider grant must not make every future download
        // fail. Keep the remembered directory so the user can reselect it, but fall back to
        // public Downloads until a writable persisted grant exists again.
        val hasWritableGrant = treeUri != null && appContext.contentResolver
            .persistedUriPermissions
            .any { permission ->
                permission.uri.toString() == treeUri && permission.isWritePermission
            }
        val effectiveMode = if (
            requestedMode == DownloadDestinationMode.CUSTOM_DIRECTORY && hasWritableGrant
        ) {
            requestedMode
        } else {
            DownloadDestinationMode.SYSTEM_DOWNLOADS
        }

        return DownloadSettings(
            unmeteredOnly = prefs.getBoolean("unmetered_only", false),
            destinationMode = effectiveMode,
            customTreeUri = treeUri,
            customDirectoryLabel = prefs.getString(KEY_CUSTOM_DIRECTORY_LABEL, null),
            threadCount = normalizeThreadCount(
                prefs.getInt(KEY_THREAD_COUNT, DEFAULT_DOWNLOAD_THREADS),
            ),
        )
    }

    /** Imported directory hints never copy or grant access to another device's tree. */
    fun importSettings(threadCount: Int?, unmeteredOnly: Boolean?, useSystemDirectory: Boolean) {
        require(threadCount == null || threadCount in MIN_DOWNLOAD_THREADS..MAX_DOWNLOAD_THREADS)
        prefs.commitConfirmed(buildMap {
            threadCount?.let { put(KEY_THREAD_COUNT, it) }
            unmeteredOnly?.let { put("unmetered_only", it) }
            if (useSystemDirectory) put(KEY_DESTINATION_MODE, DownloadDestinationMode.SYSTEM_DOWNLOADS.name)
        })
    }

    fun useSystemDownloads(): DownloadSettings {
        prefs.edit { putString(KEY_DESTINATION_MODE, DownloadDestinationMode.SYSTEM_DOWNLOADS.name) }
        return load()
    }

    /** The caller must take a persistable read/write grant before invoking this method. */
    fun useCustomDirectory(uri: Uri, label: String): DownloadSettings {
        require(uri.scheme == "content") { "Download directory must be a content tree URI" }
        val safeLabel = label.trim().take(MAX_DIRECTORY_LABEL_LENGTH).ifBlank { CUSTOM_DIRECTORY_LABEL }
        prefs.edit {
            putString(KEY_DESTINATION_MODE, DownloadDestinationMode.CUSTOM_DIRECTORY.name)
            putString(KEY_CUSTOM_TREE_URI, uri.toString())
            putString(KEY_CUSTOM_DIRECTORY_LABEL, safeLabel)
        }
        return load()
    }

    fun setThreadCount(value: Int): DownloadSettings {
        prefs.edit { putInt(KEY_THREAD_COUNT, normalizeThreadCount(value)) }
        return load()
    }

    fun setUnmeteredOnly(value: Boolean): DownloadSettings {
        prefs.edit { putBoolean("unmetered_only", value) }
        return load()
    }

    /** Human-readable label for a tree picker result; never exposes an opaque URI to users. */
    fun describeDirectory(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val path = documentId
            ?.takeIf { it.contains(':') }
            ?.substringAfter(':', missingDelimiterValue = documentId)
            ?.replace("/", " › ")
            ?.trim()
            .orEmpty()
        if (path.isNotEmpty()) return path.take(MAX_DIRECTORY_LABEL_LENGTH)

        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(uri, requireNotNull(documentId))
        }.getOrNull()
        if (documentUri != null) {
            runCatching {
                appContext.contentResolver.query(
                    documentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                return it.take(MAX_DIRECTORY_LABEL_LENGTH)
            }
        }
        return CUSTOM_DIRECTORY_LABEL
    }

    companion object {
        internal const val PREFS_NAME = "download_settings"
        private const val KEY_DESTINATION_MODE = "destination_mode"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
        private const val KEY_CUSTOM_DIRECTORY_LABEL = "custom_directory_label"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val MAX_DIRECTORY_LABEL_LENGTH = 120

        fun normalizeThreadCount(value: Int): Int = value.coerceIn(
            MIN_DOWNLOAD_THREADS,
            MAX_DOWNLOAD_THREADS,
        )
    }
}

const val MIN_DOWNLOAD_THREADS = 1
const val MAX_DOWNLOAD_THREADS = 16
const val DEFAULT_DOWNLOAD_THREADS = 4

// Store stable identifiers, not translated fallback names, in download metadata.
const val SYSTEM_DIRECTORY_LABEL = "@system-downloads"
const val CUSTOM_DIRECTORY_LABEL = "@custom-directory"

fun localizeDownloadDirectory(resources: Resources, label: String): String = when (label) {
    // The literal aliases migrate labels persisted by versions before localization.
    "", SYSTEM_DIRECTORY_LABEL, "系统下载目录" -> resources.getString(R.string.ui_system_downloads_folder)
    CUSTOM_DIRECTORY_LABEL, "自定义目录" -> resources.getString(R.string.ui_custom_folder)
    else -> label
}
