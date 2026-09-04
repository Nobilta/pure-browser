package com.mybrowser.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mybrowser.ui.DownloadItem
import com.mybrowser.ui.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class DownloadDeleteResult(
    val removedCount: Int,
    val failedFileCount: Int,
)

/**
 * Process-scoped owner of downloads started by the browser.
 *
 * New tasks use [HttpDownloadEngine], which is the only way to honour both a user-selected
 * Storage Access Framework directory and a segmented thread count. Legacy entries created by
 * earlier versions remain readable through Android [DownloadManager] and migrate naturally as
 * the user clears or retries them.
 */
class DownloadHandler(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val legacyManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val settingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DownloadSettingsRepository(appContext)
    }
    private val engine = HttpDownloadEngine()
    private val destinationWriter = DownloadDestinationWriter(appContext)
    private val metadata = ConcurrentHashMap<Long, DownloadMetadata>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    /** Serialises JSON snapshots so completion and deletion cannot overwrite one another. */
    private val metadataLock = Any()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()
    private val _activeTransfers = MutableStateFlow<List<DownloadItem>>(emptyList())
    val activeTransfers: StateFlow<List<DownloadItem>> = _activeTransfers.asStateFlow()

    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var legacySnapshot: List<DownloadItem> = emptyList()
    private var refreshJob: Job? = null
    private var monitorJob: Job? = null
    private val nextLocalId = AtomicLong(-System.currentTimeMillis().coerceAtLeast(1L))
    @Volatile private var closed = false

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) refresh()
        }
    }

    init {
        // This bounded preference snapshot is needed before the first composition so the
        // downloads sheet never flashes empty. Mark the intentional startup I/O explicitly;
        // all network, file-copy and subsequent persistence work stays on Dispatchers.IO.
        val oldPolicy = StrictMode.allowThreadDiskWrites()
        try {
            restoreMetadata()
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
        transferScope.launch { cleanupOrphanedTemporaryFiles() }
        appContext.registerReceiver(
            completionReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )
        publishSnapshots()
        refresh()
        // Only legacy system tasks need polling. Local transfers push progress directly.
        monitorJob = transferScope.launch {
            while (isActive && !closed) {
                refresh()
                val hasRunningLegacy = legacySnapshot.any {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
                }
                delay(if (hasRunningLegacy) ACTIVE_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Enqueues a validated HTTP(S) download and returns its app-local id. */
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String? = null,
    ): Long? {
        if (closed) return null
        val cleanUrl = validHttpUrl(url) ?: return null
        val safeMime = mimeType?.substringBefore(';')?.let(::sanitizeMime).orEmpty()
            .ifEmpty { "application/octet-stream" }
        val safeUserAgent = sanitizeHeader(userAgent, MAX_USER_AGENT_LENGTH).orEmpty()
        val safeDisposition = sanitizeHeader(contentDisposition, MAX_CONTENT_DISPOSITION_LENGTH)
        val safeReferer = referer?.let(::validHttpUrl)
        val safeCookie = sanitizeHeader(
            runCatching { CookieManager.getInstance().getCookie(cleanUrl) }.getOrNull(),
            MAX_COOKIE_LENGTH,
        )
        val filename = uniqueFilename(
            sanitizeFilename(FilenameParser.resolve(safeDisposition, cleanUrl, safeMime)),
        )
        val settings = settingsRepository.load()
        val id = createLocalId()
        val entry = DownloadMetadata(
            id = id,
            backend = DownloadBackend.LOCAL,
            url = cleanUrl,
            userAgent = safeUserAgent,
            cookie = safeCookie,
            contentDisposition = safeDisposition,
            mimeType = safeMime,
            filename = filename,
            referer = safeReferer,
            timestamp = System.currentTimeMillis(),
            status = DownloadStatus.DOWNLOADING,
            configuredThreadCount = settings.threadCount,
            actualThreadCount = settings.threadCount,
            destinationMode = settings.destinationMode,
            customTreeUri = settings.customTreeUri,
            destinationLabel = settings.destinationLabel,
        )
        metadata[id] = entry
        trimMetadata()
        persistMetadata()
        publishSnapshots()

        // LAZY closes the race where a tiny local response finishes before its Job is placed
        // in the cancellation map.
        val job = transferScope.launch(start = CoroutineStart.LAZY) { performDownload(id) }
        jobs[id] = job
        startTransferService()
        job.start()
        return id
    }

    /** Cancels an active task and removes its incomplete data and browser record. */
    fun cancel(id: Long) {
        if (closed) return
        val entry = metadata.remove(id) ?: return
        jobs.remove(id)?.cancel()
        if (entry.backend == DownloadBackend.LEGACY_SYSTEM) {
            transferScope.launch { legacyManager.remove(id) }
        } else {
            transferScope.launch {
                cleanupTemporaryFiles(id)
                entry.destinationUri?.let(destinationWriter::delete)
            }
        }
        persistMetadata()
        publishSnapshots()
        refresh()
    }

    /**
     * Removes one terminal record. If [deleteFile] is true, the record is retained when its
     * corresponding local file cannot be deleted so the user can retry instead of losing it.
     */
    fun delete(
        id: Long,
        deleteFile: Boolean,
        onComplete: (DownloadDeleteResult) -> Unit = {},
    ) {
        val entry = metadata[id]
        if (closed || entry == null) {
            onComplete(DownloadDeleteResult(0, 0))
            return
        }
        transferScope.launch {
            val fileDeleted = !deleteFile || deleteStoredFile(entry)
            val removed = if (fileDeleted && metadata.remove(id, entry)) 1 else 0
            if (removed > 0) {
                persistMetadata()
                publishSnapshots()
                refresh()
            }
            withContext(Dispatchers.Main.immediate) {
                onComplete(
                    DownloadDeleteResult(
                        removedCount = removed,
                        failedFileCount = if (deleteFile && !fileDeleted) 1 else 0,
                    ),
                )
            }
        }
    }

    /** Re-enqueues a failed or interrupted task using its original request metadata. */
    fun retry(id: Long): Long? {
        val old = metadata[id] ?: return null
        if (old.status != DownloadStatus.FAILED && old.status != DownloadStatus.PAUSED) return null
        jobs.remove(id)?.cancel()
        if (old.backend == DownloadBackend.LEGACY_SYSTEM) {
            runCatching { legacyManager.remove(id) }
        } else {
            old.destinationUri?.let(destinationWriter::delete)
        }
        metadata.remove(id)
        persistMetadata()
        publishSnapshots()
        return enqueue(
            url = old.url,
            userAgent = old.userAgent,
            contentDisposition = old.contentDisposition,
            mimeType = old.mimeType,
            referer = old.referer,
        )
    }

    fun openFile(id: Long): Boolean {
        if (closed) return false
        val entry = metadata[id] ?: return false
        val uri = when (entry.backend) {
            DownloadBackend.LEGACY_SYSTEM -> runCatching {
                legacyManager.getUriForDownloadedFile(id)
            }.getOrNull()
            DownloadBackend.LOCAL -> entry.destinationUri
                ?.let { runCatching { it.toUri() }.getOrNull() }
        } ?: return false
        val mime = entry.mimeType.ifBlank { "application/octet-stream" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(entry.filename, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    /** Clears terminal records in one transaction, optionally deleting every local file. */
    fun clearCompleted(
        deleteFiles: Boolean,
        onComplete: (DownloadDeleteResult) -> Unit = {},
    ) {
        val terminalIds = _downloads.value
            .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
            .mapTo(HashSet()) { it.id }
        val entries = terminalIds.mapNotNull(metadata::get)
        if (entries.isEmpty()) {
            onComplete(DownloadDeleteResult(0, 0))
            return
        }
        transferScope.launch {
            var removed = 0
            var failed = 0
            entries.forEach { entry ->
                val fileDeleted = !deleteFiles || deleteStoredFile(entry)
                if (fileDeleted) {
                    if (metadata.remove(entry.id, entry)) removed++
                } else {
                    failed++
                }
            }
            if (removed > 0) {
                persistMetadata()
                publishSnapshots()
                refresh()
            }
            withContext(Dispatchers.Main.immediate) {
                onComplete(DownloadDeleteResult(removed, failed))
            }
        }
    }

    /** Called when Android's foreground-service time budget expires. */
    fun pauseActiveTransfers() {
        val activeIds = metadata.values
            .filter {
                it.backend == DownloadBackend.LOCAL && it.status == DownloadStatus.DOWNLOADING
            }
            .map { it.id }
        if (activeIds.isEmpty()) return
        activeIds.forEach { id ->
            metadata.computeIfPresent(id) { _, entry -> entry.copy(status = DownloadStatus.PAUSED) }
            jobs.remove(id)?.cancel()
        }
        persistMetadata()
        publishSnapshots()
    }

    /** Refreshes only legacy DownloadManager records; local tasks publish their own state. */
    @Synchronized
    fun refresh() {
        if (closed || refreshJob?.isActive == true) return
        refreshJob = transferScope.launch {
            val legacyEntries = metadata.values
                .filter { it.backend == DownloadBackend.LEGACY_SYSTEM }
            val next = legacyEntries.mapNotNull(::queryLegacy)
                .sortedByDescending { it.timestamp }
            val present = next.mapTo(HashSet()) { it.id }
            if (metadata.keys.removeIf { id ->
                    metadata[id]?.backend == DownloadBackend.LEGACY_SYSTEM && id !in present
                }
            ) {
                persistMetadata()
            }
            legacySnapshot = next
            publishSnapshots()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        metadata.replaceAll { _, entry ->
            if (entry.backend == DownloadBackend.LOCAL &&
                entry.status == DownloadStatus.DOWNLOADING
            ) {
                entry.copy(status = DownloadStatus.PAUSED)
            } else {
                entry
            }
        }
        persistMetadata()
        monitorJob?.cancel()
        refreshJob?.cancel()
        transferScope.cancel()
        runCatching { appContext.unregisterReceiver(completionReceiver) }
    }

    private suspend fun performDownload(id: Long) {
        val entry = metadata[id] ?: return
        val tempDirectory = temporaryDirectory(id)
        try {
            val payload = engine.download(
                url = entry.url,
                headers = DownloadRequestHeaders(entry.userAgent, entry.cookie, entry.referer),
                requestedThreads = entry.configuredThreadCount,
                tempDirectory = tempDirectory,
                onProgress = { downloaded, total, actualThreads ->
                    metadata.computeIfPresent(id) { _, current ->
                        current.copy(
                            bytesDownloaded = downloaded.coerceAtLeast(0L),
                            totalBytes = total.coerceAtLeast(0L),
                            actualThreadCount = actualThreads.coerceAtLeast(1),
                        )
                    }
                    publishSnapshots()
                },
            )
            val current = metadata[id] ?: return
            val published = destinationWriter.publish(
                settings = DownloadSettings(
                    destinationMode = current.destinationMode,
                    customTreeUri = current.customTreeUri,
                    customDirectoryLabel = current.destinationLabel,
                    threadCount = current.configuredThreadCount,
                ),
                preferredName = current.filename,
                mimeType = current.mimeType,
                parts = payload.parts,
            )
            val completed = metadata.computeIfPresent(id) { _, latest ->
                latest.copy(
                    filename = published.displayName,
                    destinationUri = published.uri.toString(),
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = payload.totalBytes,
                    totalBytes = payload.totalBytes,
                    actualThreadCount = payload.actualThreadCount,
                )
            }
            // Cancellation can remove metadata between publishing and the atomic update.
            // Never leave an untracked file behind in that race.
            if (completed == null) destinationWriter.delete(published.uri.toString())
        } catch (cancelled: CancellationException) {
            // An explicit cancel removes metadata first. close() changes surviving entries to
            // PAUSED, so there is no state to publish from this coroutine.
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Download failed: ${entry.url}", error)
            metadata.computeIfPresent(id) { _, current ->
                current.copy(status = DownloadStatus.FAILED)
            }
        } finally {
            cleanupTemporaryFiles(id)
            jobs.remove(id)
            persistMetadata()
            publishSnapshots()
        }
    }

    private fun queryLegacy(entry: DownloadMetadata): DownloadItem? {
        val cursor = runCatching {
            legacyManager.query(DownloadManager.Query().setFilterById(entry.id))
        }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            ).coerceAtLeast(0L)
            val total = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            ).coerceAtLeast(0L)
            val mapped = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                else -> DownloadStatus.DOWNLOADING
            }
            return DownloadItem(
                id = entry.id,
                filename = entry.filename,
                url = entry.url,
                status = mapped,
                progress = progress(downloaded, total),
                bytesDownloaded = downloaded,
                totalBytes = total,
                timestamp = entry.timestamp,
                threadCount = 1,
                destinationLabel = "系统下载目录",
            )
        }
    }

    private fun localItem(entry: DownloadMetadata): DownloadItem = DownloadItem(
        id = entry.id,
        filename = entry.filename,
        url = entry.url,
        status = entry.status,
        progress = progress(entry.bytesDownloaded, entry.totalBytes),
        bytesDownloaded = entry.bytesDownloaded,
        totalBytes = entry.totalBytes,
        timestamp = entry.timestamp,
        threadCount = entry.actualThreadCount,
        destinationLabel = entry.destinationLabel,
    )

    private fun publishSnapshots() {
        if (closed) return
        val local = metadata.values
            .filter { it.backend == DownloadBackend.LOCAL }
            .map(::localItem)
        val validLegacyIds = metadata.values
            .filter { it.backend == DownloadBackend.LEGACY_SYSTEM }
            .mapTo(HashSet()) { it.id }
        val all = (local + legacySnapshot.filter { it.id in validLegacyIds })
            .sortedByDescending { it.timestamp }
        _downloads.value = all
        _activeTransfers.value = local.filter { it.status == DownloadStatus.DOWNLOADING }
    }

    private fun deleteStoredFile(entry: DownloadMetadata): Boolean = when (entry.backend) {
        DownloadBackend.LEGACY_SYSTEM -> runCatching {
            legacyManager.remove(entry.id) > 0
        }.getOrDefault(false)
        DownloadBackend.LOCAL -> destinationWriter.delete(entry.destinationUri)
    }

    private fun uniqueFilename(candidate: String): String {
        val names = metadata.values.mapTo(HashSet()) { it.filename }
        if (candidate !in names) return candidate
        val dot = candidate.lastIndexOf('.').takeIf { it > 0 } ?: candidate.length
        val stem = candidate.substring(0, dot)
        val extension = candidate.substring(dot)
        var index = 1
        while (true) {
            val suffix = " ($index)"
            val boundedStem = stem.take(
                (MAX_FILENAME_LENGTH - extension.length - suffix.length).coerceAtLeast(1),
            )
            val result = "$boundedStem$suffix$extension"
            if (result !in names) return result
            index++
        }
    }

    private fun restoreMetadata() {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return
        if (raw.length > MAX_PERSISTED_JSON_LENGTH) {
            prefs.edit { remove(KEY_ENTRIES) }
            return
        }
        var changed = false
        runCatching {
            val array = JSONArray(raw)
            val limit = minOf(array.length(), MAX_METADATA_ENTRIES)
            for (i in 0 until limit) {
                val obj = array.optJSONObject(i) ?: continue
                val backend = runCatching {
                    DownloadBackend.valueOf(obj.optString("backend"))
                }.getOrDefault(DownloadBackend.LEGACY_SYSTEM)
                val id = obj.optLong("id", 0L)
                if (id == 0L || (backend == DownloadBackend.LEGACY_SYSTEM && id < 0L)) continue
                val url = validHttpUrl(obj.optString("url")) ?: continue
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
                }.getOrDefault(DownloadStatus.FAILED)
                val status = if (
                    backend == DownloadBackend.LOCAL && restoredStatus == DownloadStatus.DOWNLOADING
                ) {
                    changed = true
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
                        .ifBlank { "系统下载目录" },
                    destinationUri = obj.optString("destinationUri").takeIf { it.isNotBlank() },
                )
            }
            trimMetadata()
            if (changed) persistMetadata()
        }.onFailure { prefs.edit { remove(KEY_ENTRIES) } }
    }

    private fun persistMetadata() {
        synchronized(metadataLock) {
            trimMetadata()
            val array = JSONArray()
            metadata.values.sortedByDescending { it.timestamp }
                .take(MAX_METADATA_ENTRIES)
                .forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("backend", entry.backend.name)
                            .put("url", entry.url)
                            .put("userAgent", entry.userAgent)
                            .put("contentDisposition", entry.contentDisposition)
                            .put("mimeType", entry.mimeType)
                            .put("filename", entry.filename)
                            .put("referer", entry.referer)
                            .put("timestamp", entry.timestamp)
                            .put("status", entry.status.name)
                            .put("bytesDownloaded", entry.bytesDownloaded)
                            .put("totalBytes", entry.totalBytes)
                            .put("configuredThreadCount", entry.configuredThreadCount)
                            .put("actualThreadCount", entry.actualThreadCount)
                            .put("destinationMode", entry.destinationMode.name)
                            .put("customTreeUri", entry.customTreeUri)
                            .put("destinationLabel", entry.destinationLabel)
                            .put("destinationUri", entry.destinationUri),
                    )
                    if (array.toString().length > MAX_PERSISTED_JSON_LENGTH) {
                        array.remove(array.length() - 1)
                        return@forEach
                    }
                }
            prefs.edit { putString(KEY_ENTRIES, array.toString()) }
        }
    }

    /** Keeps both memory and the persisted representation bounded over long browser runs. */
    private fun trimMetadata() {
        synchronized(metadataLock) {
            val stale = metadata.values
                .filter { it.status != DownloadStatus.DOWNLOADING }
                .sortedByDescending { it.timestamp }
                .drop(MAX_METADATA_ENTRIES)
                .map { it.id }
            stale.forEach(metadata::remove)
        }
    }

    private fun startTransferService() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, DownloadTransferService::class.java),
            )
        }.onFailure { Log.w(TAG, "Unable to start download foreground service", it) }
    }

    private fun createLocalId(): Long {
        while (true) {
            val id = nextLocalId.getAndDecrement()
            if (id < 0L && !metadata.containsKey(id)) return id
        }
    }

    private fun temporaryRoot(): File = File(
        appContext.externalCacheDir ?: appContext.cacheDir,
        TEMP_DIRECTORY_NAME,
    )

    private fun temporaryDirectory(id: Long): File = File(temporaryRoot(), id.toString())

    private fun cleanupTemporaryFiles(id: Long) {
        temporaryDirectory(id).deleteRecursively()
    }

    private fun cleanupOrphanedTemporaryFiles() {
        temporaryRoot().listFiles().orEmpty().forEach { directory ->
            val id = directory.name.toLongOrNull()
            val isNewTransfer = id != null &&
                metadata[id]?.status == DownloadStatus.DOWNLOADING
            if (!isNewTransfer) directory.deleteRecursively()
        }
    }

    private fun validHttpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_URL_LENGTH ||
            value.any { it.isWhitespace() || it.isISOControl() }
        ) return null
        val uri = runCatching { value.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return value.takeIf {
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        }
    }

    private fun sanitizeFilename(raw: String): String {
        val value = raw
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]"), "_")
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')
            .take(MAX_FILENAME_LENGTH)
        return value.ifBlank { "download" }
    }

    private fun sanitizeHeader(raw: String?, maxLength: Int): String? = raw
        ?.filterNot { it == '\r' || it == '\n' || it.isISOControl() }
        ?.trim()
        ?.take(maxLength)
        ?.ifEmpty { null }

    private fun sanitizeMime(raw: String?): String {
        val value = sanitizeHeader(raw, MAX_MIME_LENGTH).orEmpty()
        return value.takeIf { MIME_PATTERN.matches(it) }.orEmpty()
    }

    private fun progress(downloaded: Long, total: Long): Int = if (total > 0L) {
        (downloaded * 100L / total).toInt().coerceIn(0, 100)
    } else {
        0
    }

    private enum class DownloadBackend { LEGACY_SYSTEM, LOCAL }

    private data class DownloadMetadata(
        val id: Long,
        val backend: DownloadBackend,
        val url: String,
        val userAgent: String,
        val cookie: String?,
        val contentDisposition: String?,
        val mimeType: String,
        val filename: String,
        val referer: String?,
        val timestamp: Long,
        val status: DownloadStatus,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val configuredThreadCount: Int = 1,
        val actualThreadCount: Int = 1,
        val destinationMode: DownloadDestinationMode = DownloadDestinationMode.SYSTEM_DOWNLOADS,
        val customTreeUri: String? = null,
        val destinationLabel: String = "系统下载目录",
        val destinationUri: String? = null,
    )

    private companion object {
        const val TAG = "DownloadHandler"
        const val PREFS_NAME = "downloads"
        const val KEY_ENTRIES = "entries"
        const val TEMP_DIRECTORY_NAME = "browser-downloads"
        const val ACTIVE_POLL_INTERVAL_MS = 750L
        const val IDLE_POLL_INTERVAL_MS = 5_000L
        const val MAX_METADATA_ENTRIES = 256
        const val MAX_PERSISTED_JSON_LENGTH = 512 * 1024
        const val MAX_URL_LENGTH = 8_192
        const val MAX_USER_AGENT_LENGTH = 1_024
        const val MAX_COOKIE_LENGTH = 65_536
        const val MAX_CONTENT_DISPOSITION_LENGTH = 8_192
        const val MAX_MIME_LENGTH = 256
        const val MAX_FILENAME_LENGTH = 127
        const val MAX_DESTINATION_LABEL_LENGTH = 120
        val MIME_PATTERN = Regex("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+")
    }
}
