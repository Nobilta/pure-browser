package com.mybrowser.download

import com.mybrowser.core.boundedJsonArray
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val tasks = ConcurrencyBudget(total = 3, perHost = 2)
    private val destinationWriter = DownloadDestinationWriter(appContext)
    private val metadata = ConcurrentHashMap<Long, DownloadMetadata>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val taskLock = Any()
    /** Survives replacement of a cancelled job that was itself waiting for an older job. */
    private val transferLocks = ConcurrentHashMap<Long, Mutex>()
    private val interrupted = ConcurrentHashMap.newKeySet<Long>()
    private val lastProgressPersist = AtomicLong(0L)
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
        ContextCompat.registerReceiver(
            appContext,
            completionReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
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
        isPrivate: Boolean = false,
        cookieHeader: String? = if (isPrivate) null else runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull(),
    ): Long? {
        if (closed) return null
        val cleanUrl = validHttpUrl(url) ?: return null
        val safeMime = mimeType?.substringBefore(';')?.let(::sanitizeMime).orEmpty()
            .ifEmpty { "application/octet-stream" }
        val safeUserAgent = sanitizeHeader(userAgent, MAX_USER_AGENT_LENGTH).orEmpty()
        val safeDisposition = sanitizeHeader(contentDisposition, MAX_CONTENT_DISPOSITION_LENGTH)
        val safeReferer = referer?.let(::validHttpUrl)
        val safeCookie = sanitizeHeader(
            cookieHeader,
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
            status = DownloadStatus.QUEUED,
            unmeteredOnly = settings.unmeteredOnly,
            autoResumeAllowed = !isPrivate,
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
        launchTransfer(id)
        return id
    }

    /** Cancels an active task and removes its incomplete data and browser record. */
    fun cancel(id: Long) {
        if (closed) return
        val entry = metadata.remove(id) ?: return
        interrupted.remove(id)
        synchronized(taskLock) { jobs.remove(id)?.cancel() }
        if (entry.backend == DownloadBackend.LEGACY_SYSTEM) {
            transferScope.launch { legacyManager.remove(id) }
        } else {
            transferScope.launch {
                cleanupRemovedTask(id)
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
                cleanupRemovedTask(id)
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

    /** Pausing keeps validated partial bytes; cancellation removes the whole task. */
    fun pause(id: Long) {
        synchronized(taskLock) {
            if (closed) return
            var paused = false
            metadata.computeIfPresent(id) { _, current ->
                if (current.backend == DownloadBackend.LOCAL && current.status.active) {
                    paused = true
                    current.copy(status = DownloadStatus.PAUSED)
                } else current
            }
            if (!paused) return
            interrupted.remove(id)
            jobs[id]?.cancel()
        }
        persistMetadata()
        publishSnapshots()
    }

    /** Only tasks interrupted while running are resumed automatically, once foregrounded. */
    fun resumeInterrupted() {
        interrupted.toList().forEach { id -> if (interrupted.remove(id)) retry(id) }
    }

    fun retry(id: Long): Long? {
        val old = metadata[id] ?: return null
        if (closed || old.status !in listOf(DownloadStatus.FAILED, DownloadStatus.PAUSED)) return null
        if (old.backend == DownloadBackend.LEGACY_SYSTEM) {
            val replacement = enqueue(old.url, old.userAgent, old.contentDisposition, old.mimeType, old.referer)
                ?: return null
            cancel(id)
            return replacement
        }
        // A private task keeps only the cookie captured by its own profile in this process.
        // It must never acquire credentials from the normal profile when manually resumed.
        val cookie = if (old.autoResumeAllowed) sanitizeHeader(
            runCatching { CookieManager.getInstance().getCookie(old.url) }.getOrNull(), MAX_COOKIE_LENGTH,
        ) else old.cookie
        synchronized(taskLock) {
            if (!metadata.replace(id, old, old.copy(status = DownloadStatus.QUEUED, cookie = cookie, bytesPerSecond = 0))) return null
            val previous = jobs[id]
            previous?.cancel()
            launchTransfer(id)
        }
        persistMetadata()
        publishSnapshots()
        return id
    }

    private fun launchTransfer(id: Long) = synchronized(taskLock) {
        val writerLock = transferLocks.computeIfAbsent(id) { Mutex() }
        val job = transferScope.launch(start = CoroutineStart.LAZY) {
            try {
                writerLock.withLock {
                    val entry = metadata[id]
                    if (entry?.status?.active == true) {
                        awaitNetwork(id, entry.unmeteredOnly)
                        tasks.acquire(entry.url.toUri().host.orEmpty()).use {
                            if (metadata[id]?.status?.active == true) performDownload(id)
                        }
                    }
                }
            } finally { jobs.remove(id, requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])) }
        }
        jobs[id] = job
        if (startTransferService()) job.start() else pause(id)
    }

    /** Called off the UI thread; only completed, readable content URIs may leave the app. */
    fun fileToOpen(id: Long): DownloadOpenResult {
        if (closed) return DownloadOpenResult.Unavailable
        val entry = metadata[id] ?: return DownloadOpenResult.Unavailable
        DownloadNotifications.dismiss(appContext, id)
        val completed = if (entry.backend == DownloadBackend.LEGACY_SYSTEM)
            queryLegacy(entry)?.status == DownloadStatus.COMPLETED else entry.status == DownloadStatus.COMPLETED
        if (!completed) return DownloadOpenResult.NotCompleted
        val uri = when (entry.backend) {
            DownloadBackend.LEGACY_SYSTEM -> runCatching { legacyManager.getUriForDownloadedFile(id) }.getOrNull()
            DownloadBackend.LOCAL -> entry.destinationUri?.let { runCatching { it.toUri() }.getOrNull() }
        } ?: return DownloadOpenResult.Unavailable
        return DownloadFiles.inspect(appContext, uri, entry.filename)
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
                    if (metadata.remove(entry.id, entry)) { removed++; cleanupRemovedTask(entry.id) }
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
        metadata.values.filter { it.backend == DownloadBackend.LOCAL && it.status.active }
            .map { it.id }.forEach(::pause)
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
                entry.status.active
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

    private suspend fun awaitNetwork(id: Long, unmeteredOnly: Boolean) {
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val network = connectivity.activeNetwork
            val available = network != null && connectivity.getNetworkCapabilities(network)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (available && (!unmeteredOnly || !connectivity.isActiveNetworkMetered)) break
            metadata.computeIfPresent(id) { _, value -> if (value.status.active) value.copy(status = DownloadStatus.WAITING_NETWORK) else value }
            publishSnapshots()
            delay(1500)
        }
        metadata.computeIfPresent(id) { _, value -> if (value.status.active) value.copy(status = DownloadStatus.QUEUED) else value }
    }

    private suspend fun <T> retryTransfer(id: Long, unmeteredOnly: Boolean, action: suspend () -> T): T {
        var retries = 0
        while (true) {
            awaitNetwork(id, unmeteredOnly)
            metadata.computeIfPresent(id) { _, value -> if (value.status.active) value.copy(status = DownloadStatus.DOWNLOADING) else value }
            publishSnapshots()
            try { return action() } catch (error: java.io.IOException) {
                if (error is DownloadHttpException && error.code !in listOf(408, 429, 500, 502, 503, 504)) throw error
                if (++retries > 2) throw error
                metadata.computeIfPresent(id) { _, value -> if (value.status.active) value.copy(status = DownloadStatus.QUEUED, bytesPerSecond = 0) else value }
                publishSnapshots()
                delay(1000L shl (retries - 1))
            }
        }
    }

    private suspend fun performDownload(id: Long) {
        val entry = metadata[id] ?: return
        val tempDirectory = temporaryDirectory(id)
        val transferJob = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
        var sampleTime = android.os.SystemClock.elapsedRealtime()
        var sampleBytes = entry.bytesDownloaded
        try {
            val payload = retryTransfer(id, entry.unmeteredOnly) { engine.download(
                url = entry.url,
                headers = DownloadRequestHeaders(entry.userAgent, entry.cookie, entry.referer),
                requestedThreads = entry.configuredThreadCount,
                tempDirectory = tempDirectory,
                onProgress = { downloaded, total, actualThreads ->
                    synchronized(taskLock) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val duration = now - sampleTime
                        val rate = if (duration >= 300 && downloaded >= sampleBytes) (downloaded - sampleBytes) * 1000 / duration else null
                        if (duration >= 300) { sampleTime = now; sampleBytes = downloaded }
                        metadata.computeIfPresent(id) { _, current ->
                            if (jobs[id] !== transferJob || current.status != DownloadStatus.DOWNLOADING) current
                            else current.copy(
                            bytesDownloaded = downloaded.coerceAtLeast(0L),
                            totalBytes = total.coerceAtLeast(0L),
                            actualThreadCount = actualThreads.coerceAtLeast(1),
                            bytesPerSecond = rate ?: current.bytesPerSecond,
                            )
                        }
                    }
                    publishSnapshots()
                    val now = android.os.SystemClock.elapsedRealtime()
                    val previous = lastProgressPersist.get()
                    if (now - previous > 2_000 && lastProgressPersist.compareAndSet(previous, now)) persistMetadata()
                },
            ) }
            metadata.computeIfPresent(id) { _, current ->
                if (jobs[id] === transferJob && current.status == DownloadStatus.DOWNLOADING) current.copy(status = DownloadStatus.SAVING) else current
            }
            publishSnapshots()
            val current = metadata[id] ?: return
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                onProgress = { percent ->
                    metadata.computeIfPresent(id) { _, latest ->
                        if (jobs[id] === transferJob && latest.status == DownloadStatus.SAVING) latest.copy(savingProgress = percent) else latest
                    }
                    publishSnapshots()
                },
            )
            var accepted = false
            synchronized(taskLock) {
                metadata.computeIfPresent(id) { _, latest ->
                    if (jobs[id] !== transferJob || latest.status != DownloadStatus.SAVING) latest
                    else {
                        accepted = true
                        latest.copy(
                    filename = published.displayName,
                    destinationUri = published.uri.toString(),
                    status = DownloadStatus.COMPLETED,
                    cookie = null,
                    bytesDownloaded = payload.totalBytes,
                    totalBytes = payload.totalBytes,
                    actualThreadCount = payload.actualThreadCount,
                        )
                    }
                }
            }
            // Cancellation can remove metadata between publishing and the atomic update.
            // Never leave an untracked file behind in that race.
            if (!accepted) destinationWriter.delete(published.uri.toString())
        } catch (cancelled: CancellationException) {
            // An explicit cancel removes metadata first. close() changes surviving entries to
            // PAUSED, so there is no state to publish from this coroutine.
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Download failed: $id", error)
            synchronized(taskLock) {
                metadata.computeIfPresent(id) { _, current ->
                    if (jobs[id] === transferJob && current.status.active)
                        current.copy(status = DownloadStatus.FAILED) else current
                }
            }
        } finally {
            val latest = metadata[id]
            if (latest == null || latest.status == DownloadStatus.COMPLETED) {
                cleanupTemporaryFiles(id)
                transferLocks.remove(id)
            }
            jobs.remove(id, transferJob)
            persistMetadata()
            publishSnapshots()
            if (latest?.status == DownloadStatus.COMPLETED && latest.autoResumeAllowed) DownloadNotifications.completed(appContext, localItem(latest))
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
                destinationLabel = SYSTEM_DIRECTORY_LABEL,
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
        canPause = true,
        bytesPerSecond = entry.bytesPerSecond,
        savingProgress = entry.savingProgress,
    )

    private fun publishSnapshots() = synchronized(metadataLock) {
        if (closed) return@synchronized
        val local = metadata.values
            .filter { it.backend == DownloadBackend.LOCAL }
            .map(::localItem)
        val validLegacyIds = metadata.values
            .filter { it.backend == DownloadBackend.LEGACY_SYSTEM }
            .mapTo(HashSet()) { it.id }
        val all = (local + legacySnapshot.filter { it.id in validLegacyIds })
            .sortedByDescending { it.timestamp }
        _downloads.value = all
        _activeTransfers.value = local.filter { it.status.active }
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
                    backend == DownloadBackend.LOCAL && restoredStatus.active
                ) {
                    changed = true
                    if (obj.optBoolean("autoResumeAllowed", true)) interrupted.add(id)
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
                    autoResumeAllowed = obj.optBoolean("autoResumeAllowed", true),
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
            trimMetadata()
            if (changed) persistMetadata()
        }.onFailure { prefs.edit { remove(KEY_ENTRIES) } }
    }

    private fun persistMetadata() {
        synchronized(metadataLock) {
            trimMetadata()
            val json = boundedJsonArray(
                metadata.values.sortedByDescending { it.timestamp }.asSequence().take(MAX_METADATA_ENTRIES),
                MAX_PERSISTED_JSON_LENGTH,
            ) { entry ->
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
                    .put("autoResumeAllowed", entry.autoResumeAllowed)
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
    }

    /** Keeps both memory and the persisted representation bounded over long browser runs. */
    private fun trimMetadata() {
        synchronized(metadataLock) {
            val stale = metadata.values
                .filter { !it.status.active }
                .sortedByDescending { it.timestamp }
                .drop(MAX_METADATA_ENTRIES)
                .map { it.id }
            stale.forEach { id -> metadata.remove(id); transferScope.launch { cleanupRemovedTask(id) } }
        }
    }

    private fun startTransferService(): Boolean = runCatching {
        ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadTransferService::class.java))
    }.onFailure { Log.w(TAG, "Unable to start download foreground service", it) }.isSuccess

    private fun createLocalId(): Long {
        while (true) {
            val id = nextLocalId.getAndDecrement()
            if (id < 0L && !metadata.containsKey(id)) return id
        }
    }

    private fun temporaryRoot(): File = File(
        appContext.noBackupFilesDir,
        TEMP_DIRECTORY_NAME,
    )

    private fun temporaryDirectory(id: Long): File = File(temporaryRoot(), id.toString())

    private fun cleanupTemporaryFiles(id: Long) {
        temporaryDirectory(id).deleteRecursively()
    }

    private suspend fun cleanupRemovedTask(id: Long) {
        DownloadNotifications.dismiss(appContext, id)
        val writerLock = transferLocks[id]
        if (writerLock != null) writerLock.withLock { cleanupTemporaryFiles(id) }
        else cleanupTemporaryFiles(id)
        if (writerLock != null) transferLocks.remove(id, writerLock)
    }

    private fun cleanupOrphanedTemporaryFiles() {
        // Releases before 0.5.0 treated partial bytes as disposable external cache.
        appContext.externalCacheDir?.let { File(it, TEMP_DIRECTORY_NAME).deleteRecursively() }
        File(appContext.cacheDir, TEMP_DIRECTORY_NAME).deleteRecursively()
        temporaryRoot().listFiles().orEmpty().forEach { directory ->
            val id = directory.name.toLongOrNull()
            val entry = id?.let(metadata::get)
            val keep = entry?.backend == DownloadBackend.LOCAL && entry.status != DownloadStatus.COMPLETED
            if (!keep) directory.deleteRecursively()
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
        (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
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
        val autoResumeAllowed: Boolean = true,
        val unmeteredOnly: Boolean = false,
        val bytesPerSecond: Long = 0,
        val savingProgress: Int = 0,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val configuredThreadCount: Int = 1,
        val actualThreadCount: Int = 1,
        val destinationMode: DownloadDestinationMode = DownloadDestinationMode.SYSTEM_DOWNLOADS,
        val customTreeUri: String? = null,
        val destinationLabel: String = SYSTEM_DIRECTORY_LABEL,
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
