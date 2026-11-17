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
 * Where one confirmed task saves: the part of [DownloadSettings] a record keeps.
 *
 * A settings snapshot has threads, a network policy and limits, none of which describe a place;
 * a redownload keeps the place its record came from.
 */
data class DownloadDestination(
    val mode: DownloadDestinationMode,
    val treeUri: String?,
    val label: String,
) {
    fun displayDestinationLabel(resources: Resources): String = localizeDownloadDirectory(resources, label)

    companion object {
        fun labelFor(mode: DownloadDestinationMode, customLabel: String?): String =
            if (mode == DownloadDestinationMode.CUSTOM_DIRECTORY) {
                customLabel?.takeIf { it.isNotBlank() } ?: CUSTOM_DIRECTORY_LABEL
            } else {
                SYSTEM_DIRECTORY_LABEL
            }
    }
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
    val maxConcurrentDownloads: Int = DEFAULT_CONCURRENT_DOWNLOADS,
    /** Zero means unlimited; shared across tasks, including their parallel connections. */
    val speedLimitKib: Int = 0,
) {
    val destinationLabel: String get() = DownloadDestination.labelFor(destinationMode, customDirectoryLabel)

    fun destination(): DownloadDestination =
        DownloadDestination(destinationMode, customTreeUri, destinationLabel)

    fun displayDestinationLabel(resources: Resources): String = localizeDownloadDirectory(resources, destinationLabel)
}

/** Small SharedPreferences boundary used by both the settings UI and download engine. */
class DownloadSettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val unmeteredOnly: Boolean get() = prefs.getBoolean("unmetered_only", false)
    val maxConcurrentDownloads: Int get() = prefs.getInt(KEY_CONCURRENT, DEFAULT_CONCURRENT_DOWNLOADS)
        .coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
    val speedLimitKib: Int get() = prefs.getInt(KEY_SPEED_LIMIT, 0).coerceIn(0, MAX_SPEED_LIMIT_KIB)

    /** Hold the listener strongly until close; imports and another repository instance also notify it. */
    internal fun observeTransferPolicy(onChanged: () -> Unit): java.io.Closeable {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_CONCURRENT || key == KEY_SPEED_LIMIT) onChanged()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onChanged()
        return java.io.Closeable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

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
            unmeteredOnly = unmeteredOnly,
            maxConcurrentDownloads = maxConcurrentDownloads,
            speedLimitKib = speedLimitKib,
            destinationMode = effectiveMode,
            customTreeUri = treeUri,
            customDirectoryLabel = prefs.getString(KEY_CUSTOM_DIRECTORY_LABEL, null),
            threadCount = normalizeThreadCount(
                prefs.getInt(KEY_THREAD_COUNT, DEFAULT_DOWNLOAD_THREADS),
            ),
        )
    }

    /** Imported directory hints never copy or grant access to another device's tree. */
    fun importSettings(
        threadCount: Int?, unmeteredOnly: Boolean?, useSystemDirectory: Boolean,
        maxConcurrentDownloads: Int? = null, speedLimitKib: Int? = null,
    ) {
        require(threadCount == null || threadCount in MIN_DOWNLOAD_THREADS..MAX_DOWNLOAD_THREADS)
        require(maxConcurrentDownloads == null || maxConcurrentDownloads in 1..MAX_CONCURRENT_DOWNLOADS)
        require(speedLimitKib == null || speedLimitKib in 0..MAX_SPEED_LIMIT_KIB)
        prefs.commitConfirmed(buildMap {
            threadCount?.let { put(KEY_THREAD_COUNT, it) }
            unmeteredOnly?.let { put("unmetered_only", it) }
            maxConcurrentDownloads?.let { put(KEY_CONCURRENT, it) }
            speedLimitKib?.let { put(KEY_SPEED_LIMIT, it) }
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

    fun setMaxConcurrentDownloads(value: Int): DownloadSettings {
        prefs.edit { putInt(KEY_CONCURRENT, value.coerceIn(1, MAX_CONCURRENT_DOWNLOADS)) }
        return load()
    }

    fun setSpeedLimitKib(value: Int): DownloadSettings {
        prefs.edit { putInt(KEY_SPEED_LIMIT, value.coerceIn(0, MAX_SPEED_LIMIT_KIB)) }
        return load()
    }

    /** Human-readable label for a tree picker result; never exposes an opaque URI to users. */
    fun describeDirectory(uri: Uri): String = describeDirectory(appContext, uri)

    companion object {
        /** Same label, callable where only a Context is at hand (no repository needed). */
        fun describeDirectory(context: Context, uri: Uri): String {
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
                    context.applicationContext.contentResolver.query(
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

        internal const val PREFS_NAME = "download_settings"
        private const val KEY_DESTINATION_MODE = "destination_mode"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
        private const val KEY_CUSTOM_DIRECTORY_LABEL = "custom_directory_label"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_CONCURRENT = "max_concurrent_downloads"
        private const val KEY_SPEED_LIMIT = "speed_limit_kib"
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
const val DEFAULT_CONCURRENT_DOWNLOADS = 3
const val MAX_CONCURRENT_DOWNLOADS = 5
const val MAX_SPEED_LIMIT_KIB = 10 * 1024

// Store stable identifiers, not translated fallback names, in download metadata.
const val SYSTEM_DIRECTORY_LABEL = "@system-downloads"
const val CUSTOM_DIRECTORY_LABEL = "@custom-directory"

fun localizeDownloadDirectory(resources: Resources, label: String): String = when (label) {
    // The literal aliases migrate labels persisted by versions before localization.
    "", SYSTEM_DIRECTORY_LABEL, "系统下载目录" -> resources.getString(R.string.ui_system_downloads_folder)
    CUSTOM_DIRECTORY_LABEL, "自定义目录" -> resources.getString(R.string.ui_custom_folder)
    else -> label
}
