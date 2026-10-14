package com.mybrowser.download

import android.content.SharedPreferences
import androidx.core.content.edit
import com.mybrowser.core.boundedJsonArray
import com.mybrowser.download.DownloadValidation.MAX_URL_LENGTH
import com.mybrowser.download.DownloadValidation.MAX_USER_AGENT_LENGTH
import com.mybrowser.download.DownloadValidation.MAX_CONTENT_DISPOSITION_LENGTH
import com.mybrowser.download.DownloadValidation.MAX_DESTINATION_LABEL_LENGTH
import com.mybrowser.download.DownloadValidation.PAGE_FILE_PREFIX
import com.mybrowser.download.DownloadValidation.validHttpUrl
import com.mybrowser.download.DownloadValidation.sanitizeFilename
import com.mybrowser.download.DownloadValidation.sanitizeHeader
import com.mybrowser.download.DownloadValidation.sanitizeMime
import org.json.JSONArray
import org.json.JSONObject

/** A startup snapshot; only ordinary interrupted transfers may resume after a restart. */
internal data class RestoredDownloads(
    val entries: List<DownloadMetadata>,
    val interruptedIds: Set<Long>,
)

/**
 * Owns download record migration, bounds and privacy-safe serialization.
 * The handler calls [save] synchronously under its metadata lock: dispatching snapshots to another
 * coroutine could let an older snapshot overwrite a later pause, completion or deletion.
 * SharedPreferences.apply queues only the disk write, after the snapshot has been accepted.
 */
internal class DownloadMetadataStore(private val prefs: SharedPreferences) {
    fun load(): RestoredDownloads {
        val metadata = linkedMapOf<Long, DownloadMetadata>()
        val interrupted = mutableSetOf<Long>()
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return RestoredDownloads(emptyList(), emptySet())
        if (raw.length > MAX_PERSISTED_JSON_LENGTH) {
            prefs.edit { remove(KEY_ENTRIES) }
            return RestoredDownloads(emptyList(), emptySet())
        }
        var changed = false
        runCatching {
            val array = JSONArray(raw)
            val limit = minOf(array.length(), MAX_METADATA_ENTRIES)
            for (i in 0 until limit) {
                val obj = array.optJSONObject(i) ?: continue
                // A record from a newer build may use field meanings this one would misread.
                // Skipping it loses a row; reinterpreting it would turn it into a different
                // download, so the unknown version is dropped instead.
                val version = obj.optInt("version", 0)
                if (version > PERSIST_VERSION) {
                    changed = true
                    continue
                }
                // A record without the version field predates it, and rewriting the snapshot in
                // the current format is also what applies the current URL and referer rules to
                // it — otherwise a private URL recorded by an older build would sit in
                // preferences until something else happened to write. Terminal records reach no
                // other write, which is why the rewrite is forced here rather than left to chance.
                if (version != PERSIST_VERSION) changed = true
                // A record that predates the identity field carries the legacy pair instead, and is
                // rewritten once in the new format with that pair dropped — which is also what
                // applies the current URL and referer rules to a private URL an older build
                // recorded in full. A field that is present but unreadable is a different case and
                // is skipped below rather than migrated: falling back to the legacy pair would read
                // a private record as ordinary, and then resume it against the cookie jar.
                val identity = if (obj.has("identity")) {
                    DownloadIdentity.decode(obj.optString("identity")) ?: run {
                        changed = true
                        continue
                    }
                } else {
                    changed = true
                    legacyIdentity(obj)
                }
                // An unrecognised enum is equally unreadable. Defaulting `backend` to
                // LEGACY_SYSTEM made the record's status retryable as if the server had
                // failed and then dropped it during reconciliation, taking its
                // `destinationUri` with it; defaulting `status` to FAILED had the same shape.
                val backend = runCatching {
                    DownloadBackend.valueOf(obj.optString("backend"))
                }.getOrNull() ?: run {
                    changed = true
                    continue
                }
                val id = obj.optLong("id", 0L)
                if (id == 0L || (backend == DownloadBackend.LEGACY_SYSTEM && id < 0L)) continue
                // A page-file record is stored under its placeholder address on purpose: the URL
                // is the only surviving way to tell it apart from an ordinary task after a restart,
                // and it is what keeps that record from ever being fetched. The prefix is validated
                // rather than accepted blindly so a corrupted snapshot cannot smuggle in an address
                // the rest of the app would treat as http(s).
                val rawUrl = obj.optString("url")
                val url = if (rawUrl.startsWith(PAGE_FILE_PREFIX)) {
                    if (rawUrl.length <= MAX_URL_LENGTH + PAGE_FILE_PREFIX.length) rawUrl else continue
                } else {
                    validHttpUrl(rawUrl) ?: continue
                }
                val filename = sanitizeFilename(obj.optString("filename"))
                val userAgent = sanitizeHeader(
                    obj.optString("userAgent"),
                    MAX_USER_AGENT_LENGTH,
                ).orEmpty()
                val disposition = sanitizeHeader(
                    obj.optString("contentDisposition"),
                    MAX_CONTENT_DISPOSITION_LENGTH,
                )
                val mime = sanitizeMime(obj.optString("mimeType", "application/octet-stream"))
                    .ifEmpty { "application/octet-stream" }
                val referer = obj.optString("referer").let(::validHttpUrl)
                val timestamp = obj.optLong("timestamp", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val restoredStatus = runCatching {
                    DownloadStatus.valueOf(obj.optString("status"))
                }.getOrNull() ?: run {
                    changed = true
                    continue
                }
                val status = if (
                    backend == DownloadBackend.LOCAL && restoredStatus.active
                ) {
                    changed = true
                    if (identity.resumesAfterRestart) interrupted.add(id)
                    DownloadStatus.PAUSED
                } else {
                    restoredStatus
                }
                val destinationMode = runCatching {
                    DownloadDestinationMode.valueOf(obj.optString("destinationMode"))
                }.getOrDefault(DownloadDestinationMode.SYSTEM_DOWNLOADS)
                metadata[id] = DownloadMetadata(
                    id = id,
                    backend = backend,
                    url = url,
                    userAgent = userAgent,
                    // Session cookies are deliberately memory-only. A retry re-reads the
                    // WebView cookie jar instead of copying credentials into preferences.
                    cookie = null,
                    contentDisposition = disposition,
                    mimeType = mime,
                    filename = filename,
                    referer = referer,
                    timestamp = timestamp,
                    status = status,
                    unmeteredOnly = obj.optBoolean("unmeteredOnly", false),
                    identity = identity,
                    bytesDownloaded = obj.optLong("bytesDownloaded", 0L).coerceAtLeast(0L),
                    totalBytes = obj.optLong("totalBytes", 0L).coerceAtLeast(0L),
                    configuredThreadCount = DownloadSettingsRepository.normalizeThreadCount(
                        obj.optInt("configuredThreadCount", 1),
                    ),
                    actualThreadCount = DownloadSettingsRepository.normalizeThreadCount(
                        obj.optInt("actualThreadCount", 1),
                    ),
                    destinationMode = destinationMode,
                    customTreeUri = obj.optString("customTreeUri").takeIf { it.isNotBlank() },
                    destinationLabel = obj.optString("destinationLabel")
                        .take(MAX_DESTINATION_LABEL_LENGTH)
                        .ifBlank { SYSTEM_DIRECTORY_LABEL },
                    destinationUri = obj.optString("destinationUri").takeIf { it.isNotBlank() },
                )
            }
            if (changed) save(metadata.values)
        }.onFailure { prefs.edit { remove(KEY_ENTRIES) } }
        return RestoredDownloads(metadata.values.toList(), interrupted)
    }

    /** Missing legacy private scopes map to an identity that no live session can resume. */
    private fun legacyIdentity(obj: JSONObject): DownloadIdentity =
        if (obj.optBoolean("autoResumeAllowed", true)) {
            DownloadIdentity.Normal
        } else {
            DownloadIdentity.PrivateSession(
                obj.optString("privateScope")
                    .takeIf { it.isNotBlank() && it.length <= DownloadIdentity.MAX_SCOPE_LENGTH }
                    ?: DownloadIdentity.UNRECORDED_SCOPE,
            )
        }

    /** Private records retain the file, but never query tokens or the page's address. */
    private fun persistedUrl(entry: DownloadMetadata): String =
        if (entry.identity.persistSourceUrl) entry.url else entry.url.substringBefore('?').substringBefore('#')

    /** See [persistedUrl]: a private download does not keep the page it was started from. */
    private fun persistedReferer(entry: DownloadMetadata): String =
        if (entry.identity.persistSourceUrl) entry.referer.orEmpty() else ""

    fun save(entries: Collection<DownloadMetadata>) {
        val json = boundedJsonArray(
            entries.sortedByDescending { it.timestamp }.asSequence().take(MAX_METADATA_ENTRIES),
            MAX_PERSISTED_JSON_LENGTH,
        ) { entry ->
            JSONObject()
                .put("version", PERSIST_VERSION)
                .put("id", entry.id)
                .put("backend", entry.backend.name)
                .put("url", persistedUrl(entry))
                .put("userAgent", entry.userAgent)
                .put("contentDisposition", entry.contentDisposition)
                .put("mimeType", entry.mimeType)
                .put("filename", entry.filename)
                .put("referer", persistedReferer(entry))
                .put("timestamp", entry.timestamp)
                .put("status", entry.status.name)
                .put("identity", DownloadIdentity.encode(entry.identity))
                .put("unmeteredOnly", entry.unmeteredOnly)
                .put("bytesDownloaded", entry.bytesDownloaded)
                .put("totalBytes", entry.totalBytes)
                .put("configuredThreadCount", entry.configuredThreadCount)
                .put("actualThreadCount", entry.actualThreadCount)
                .put("destinationMode", entry.destinationMode.name)
                .put("customTreeUri", entry.customTreeUri)
                .put("destinationLabel", entry.destinationLabel)
                .put("destinationUri", entry.destinationUri).toString()
        }
        prefs.edit { putString(KEY_ENTRIES, json) }
    }

    companion object {
        const val PREFS_NAME = "downloads"
        private const val KEY_ENTRIES = "entries"
        const val MAX_METADATA_ENTRIES = 256
        private const val MAX_PERSISTED_JSON_LENGTH = 512 * 1024
        /** Bump when a field's meaning changes, not when adding one. */
        private const val PERSIST_VERSION = 1
    }
}
