package com.mybrowser.download

import android.app.DownloadManager
import com.mybrowser.download.DownloadValidation.MAX_USER_AGENT_LENGTH
import com.mybrowser.download.DownloadValidation.MAX_CONTENT_DISPOSITION_LENGTH
import com.mybrowser.download.DownloadValidation.PAGE_FILE_PREFIX
import com.mybrowser.download.DownloadValidation.validDownloadUrl
import com.mybrowser.download.DownloadValidation.validHttpUrl
import com.mybrowser.download.DownloadValidation.sanitizeFilename
import com.mybrowser.download.DownloadValidation.sanitizeHeader
import com.mybrowser.download.DownloadValidation.sanitizeMime
import com.mybrowser.download.DownloadValidation.MAX_COOKIE_LENGTH
import com.mybrowser.download.DownloadMetadataStore.Companion.MAX_METADATA_ENTRIES
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val metadataStore: DownloadMetadataStore
    private val settingsRepository: DownloadSettingsRepository
    private val settingsObserver: Closeable
    private val rateLimiter = DownloadRateLimiter()
    internal val engine = HttpDownloadEngine(rateLimiter)
    private val ftp = FtpDownloadEngine(rateLimiter)
    private val hls = HlsDownloadEngine(engine)
    private val tasks = ConcurrencyBudget(total = 3, perHost = 2)
    private val destinationWriter = DownloadDestinationWriter(appContext)
    private val metadata = ConcurrentHashMap<Long, DownloadMetadata>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val privatePlaylists = ConcurrentHashMap<Long, String>()
    private val responses = ConcurrentHashMap<Long, DownloadResponse>()
    private val deleting = ConcurrentHashMap.newKeySet<Long>()
    private val taskLock = Any()
    private val pendingDirectories = mutableSetOf<DirectorySelection>()
    /** Survives replacement of a cancelled job that was itself waiting for an older job. */
    private val transferLocks = ConcurrentHashMap<Long, Mutex>()
    private val interrupted = ConcurrentHashMap.newKeySet<Long>()
    /**
     * Tasks whose bytes arrive from the page instead of from an address.
     *
     * The Blob behind such a task lives in the renderer that made it, so its placeholder URL must
     * never be fetched and its record can never be resumed. Membership is in memory only: after a
     * restart the record comes back without it and simply offers no resume, which is the same answer
     * for a file nobody can fetch again.
     */
    private val fromPageTasks = ConcurrentHashMap.newKeySet<Long>()
    private val pageFileFailures = ConcurrentHashMap<Long, String>()
    private val lastProgressPersist = AtomicLong(0L)
    private val lastPageProgressPublish = AtomicLong(0L)
    /** Serialises JSON snapshots so completion and deletion cannot overwrite one another. */
    private val metadataLock = Any()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()
    private val _activeTransfers = MutableStateFlow<List<DownloadItem>>(emptyList())
    val activeTransfers: StateFlow<List<DownloadItem>> = _activeTransfers.asStateFlow()

    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Written by the legacy-system poller on a coroutine and read by the monitor loop. */
    @Volatile private var legacySnapshot: List<DownloadItem> = emptyList()
    private var refreshJob: Job? = null
    private var monitorJob: Job? = null
    private val nextLocalId = AtomicLong(-System.currentTimeMillis().coerceAtLeast(1L))
    @Volatile private var closed = false
    /**
     * The identity tasks created now belong to: [DownloadIdentity.Normal] outside a private
     * session, and the running session's identity inside one; see [rotatePrivateScope].
     */
    @Volatile private var sessionIdentity: DownloadIdentity = DownloadIdentity.Normal

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) refresh()
        }
    }

    init {
        // This bounded preference snapshot is needed before the first composition so the
        // downloads sheet never flashes empty. Mark the intentional startup I/O explicitly.
        // Network and file-copy work stays on Dispatchers.IO; [persistMetadata] is the one
        // exception, and only because it has to run on the caller's thread in order: it
        // serialises the whole record set there (0.6 ms for 256 records) and then queues the
        // write, so pause and cancel are ordered ahead of anything that follows. Deferring the
        // write is what would let a later, stale snapshot win.
        val oldPolicy = StrictMode.allowThreadDiskWrites()
        try {
            metadataStore = DownloadMetadataStore(appContext.getSharedPreferences(DownloadMetadataStore.PREFS_NAME, Context.MODE_PRIVATE))
            settingsRepository = DownloadSettingsRepository(appContext)
            settingsObserver = settingsRepository.observeTransferPolicy {
                val limit = settingsRepository.maxConcurrentDownloads
                tasks.updateLimits(limit, minOf(2, limit))
                rateLimiter.setLimit(settingsRepository.speedLimitKib * 1024L)
            }
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
        // Only legacy system tasks need polling. Local transfers push progress directly, and a
        // record set without one never gains one (only a restored process has that backend), so
        // the loop ends instead of waking this process every five seconds for nothing.
        monitorJob = transferScope.launch {
            while (isActive && !closed) {
                if (metadata.values.none { it.backend == DownloadBackend.LEGACY_SYSTEM }) return@launch
                refresh()
                val hasRunningLegacy = legacySnapshot.any {
                    it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED
                }
                delay(if (hasRunningLegacy) ACTIVE_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Outcome of a deduplicating enqueue: a new task, an existing one, or a refusal. */
    sealed interface EnqueueOutcome {
        data class Started(val id: Long) : EnqueueOutcome
        data class Existing(val id: Long, val status: DownloadStatus) : EnqueueOutcome
        data object Rejected : EnqueueOutcome
    }

    /** A live socket cannot wait for a different network; confirmation has a bounded lifetime. */
    internal fun prepareResponse(response: DownloadResponse) {
        if (!networkAllowed(settingsRepository.unmeteredOnly, response.network)) {
            response.discard(DownloadResponse.Unavailable.NETWORK)
        } else response.hold(transferScope)
    }

    /**
     * Enqueues a validated HTTP(S) download, deduplicated against the tasks this engine
     * already owns. Task identity is the target URL without its fragment plus the
     * private/normal identity context — query, encoding and path case stay part of the
     * identity, and requests from a different context are never merged. An unfinished
     * (queued, transferring, saving, paused) or completed task is returned as
     * [EnqueueOutcome.Existing]; failed tasks may be superseded, and [newCopy] explicitly
     * starts another copy of a completed download without deleting its record or file.
     * The dedup check and the task creation run in one critical section so concurrent
     * WebView callbacks cannot both pass the check.
     */
    fun enqueueOrGetExisting(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String? = null,
        isPrivate: Boolean = false,
        cookieHeader: String? = if (isPrivate) null else runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull(),
        newCopy: Boolean = false,
        /**
         * The length the page reported for a file it built in memory; see
         * [PageFileDownload]. Presence is what makes this a page-file task: the address is the
         * page's own metadata URI, nothing about it is fetchable, and the transfer that follows is
         * driven by [startPageFile] rather than by the engine.
         */
        pageFile: PageFileOffer? = null,
        response: DownloadResponse? = null,
        retryResponse: Boolean = false,
        filename: String? = null,
        destination: DownloadDestination? = null,
        replacingLegacyId: Long? = null,
    ): EnqueueOutcome {
        if (closed) return EnqueueOutcome.Rejected
        val offer = pageFile
        // A page file is recorded under its placeholder address, never under the blob: address the
        // page used. The placeholder is what identifies the record as one after a restart, and what
        // [validDownloadUrl] refuses so nothing can ever try to fetch it.
        val cleanUrl = if (offer != null) PAGE_FILE_PREFIX + offer.url
        else validDownloadUrl(url) ?: return EnqueueOutcome.Rejected
        val dedupUrl = offer?.let { PAGE_FILE_PREFIX + it.url } ?: cleanUrl.substringBefore('#')
        val safeMime = mimeType?.substringBefore(';')?.let(::sanitizeMime).orEmpty()
            .ifEmpty { "application/octet-stream" }
        // A playlist URL names the playlist, so the saved file's extension describes the stream it
        // lists. The record keeps the *source* MIME: the transfer, a retry and a redownload all
        // decide what to fetch from it, and the MIME the finished file is published with is chosen
        // when the bytes are written.
        val playlist = offer == null && HlsDownloadEngine.handles(cleanUrl, safeMime)
        // FTP and HLS move bytes over one connection whatever the thread setting says, and the
        // detail sheet shows this count before the first progress callback can correct it.
        // Same predicate as transportFor: the FTP engine, not the thread setting, owns its stream.
        val singleConnection = playlist || cleanUrl.startsWith("ftp://", ignoreCase = true)
        val safeUserAgent = sanitizeHeader(userAgent, MAX_USER_AGENT_LENGTH).orEmpty()
        val safeDisposition = sanitizeHeader(contentDisposition, MAX_CONTENT_DISPOSITION_LENGTH)
        val safeReferer = referer?.let(::validHttpUrl)
        val safeCookie = sanitizeHeader(
            cookieHeader,
            MAX_COOKIE_LENGTH,
        )
        val settings = settingsRepository.load()
        val target = destination ?: settings.destination()
        response?.let(::prepareResponse)
        val id = createLocalId()
        synchronized(taskLock) {
            if (closed) return EnqueueOutcome.Rejected
            // Minted inside the critical section that the doc above already describes: the scope is
            // a read-decide-write on the session identity, and two requests arriving together would
            // otherwise mint two scopes and leave the loser's task in a session no later call can
            // name, so it could never be resumed. A private task belongs to the private session
            // that created it; a later session with the same URL must not be merged into its record.
            val identity = identityForNewTask(isPrivate)
            existingMetadataFor(dedupUrl, identity, includeCompleted = !newCopy, excludeId = replacingLegacyId)
                ?.let { return EnqueueOutcome.Existing(it.id, it.status) }
            if (target.mode == DownloadDestinationMode.CUSTOM_DIRECTORY) {
                val tree = target.treeUri?.toUri() ?: return EnqueueOutcome.Rejected
                try {
                    settingsRepository.keepDirectoryGrant(tree)
                } catch (_: SecurityException) {
                    return EnqueueOutcome.Rejected
                }
            }
            val created = DownloadMetadata(
                id = id,
                backend = DownloadBackend.LOCAL,
                url = cleanUrl,
                userAgent = safeUserAgent,
                cookie = safeCookie,
                contentDisposition = safeDisposition,
                mimeType = safeMime,
                filename = uniqueFilename(
                    sanitizeFilename(
                        (filename?.trim()?.takeIf { it.isNotEmpty() } ?: offer?.name?.takeIf { it.isNotBlank() }
                            ?: FilenameParser.resolve(safeDisposition, cleanUrl, safeMime))
                            .let { if (playlist) HlsDownloadEngine.outputName(it) else it },
                    ),
                ),
                referer = safeReferer,
                timestamp = System.currentTimeMillis(),
                status = DownloadStatus.QUEUED,
                // The length the page reported and the user confirmed. It is what the finished file
                // is checked against, so a page that stops early becomes a failure rather than a
                // silently truncated download.
                totalBytes = (offer?.size ?: response?.length)?.coerceAtLeast(0L) ?: 0L,
                // A page file is exempt: its bytes are already in the renderer, so there is no
                // network to wait for. Everything else keeps the user setting, which this line
                // used to overwrite for every download.
                unmeteredOnly = offer == null && settings.unmeteredOnly,
                responseBacked = response != null && !retryResponse,
                identity = identity,
                // A page file supplies its own bytes; FTP and HLS move them over one connection.
                // Neither has a segmented transfer to configure, and the page file has nothing
                // metered to defer.
                configuredThreadCount = if (offer != null || singleConnection) 1 else settings.threadCount,
                actualThreadCount = if (offer != null || response != null || singleConnection) 1
                    else settings.threadCount,
                destinationMode = target.mode,
                customTreeUri = target.treeUri,
                destinationLabel = target.label,
            )
            if (offer != null) fromPageTasks.add(id)
            if (response != null && !retryResponse) responses[id] = response
            metadata[id] = created
        }
        // persistMetadata trims under its own lock before serialising, and the trimmed map is
        // what publishSnapshots reads, so trimming again here only repeats the scan.
        persistMetadata()
        publishSnapshots()

        // LAZY closes the race where a tiny local response finishes before its Job is placed
        // in the cancellation map.
        if (offer == null) launchTransfer(id)
        return EnqueueOutcome.Started(id)
    }

    /**
     * Starts a page-built file moving into [id]'s task.
     *
     * The bytes are pulled one slice at a time: a slice arrives on the WebView's thread, is copied
     * into a queue there, and is only then written to disk — after which the next slice is asked for.
     * Nothing accumulates the file in memory, and the page is never ahead of the disk by more than the
     * one slice in flight. That is what makes a page free to build something far larger than this
     * process should ever hold.
     *
     * The whole transfer runs on one coroutine so the ordering is structural rather than defended: the
     * writer reads the queue in order, and only this coroutine ever touches the output stream.
     */
    fun startPageFile(id: Long, offer: PageFileOffer, source: PageFileSource): Boolean {
        val queue = java.util.ArrayDeque<ByteArray>()
        val lock = Object()
        var firstRequestAccepted = true

        synchronized(taskLock) {
            if (closed || id in deleting || metadata[id] == null || id !in fromPageTasks || jobs[id] != null) {
                return false
            }
            val writerLock = transferLocks.computeIfAbsent(id) { Mutex() }
            if (!writerLock.tryLock()) return false
            try {
                val directory = temporaryDirectory(id)
                val partFile = File(directory, "part-0")
                // Register before starting: even a synchronous page response belongs to this job.
                val job = transferScope.launch(start = CoroutineStart.LAZY) {
                    val pump = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
                    var failure: String? = null
                    var written = 0L
                    var lastActivity = android.os.SystemClock.elapsedRealtime()
                    writerLock.withLock {
                        try {
                            check(directory.mkdirs() || directory.isDirectory) { "Cannot create page download directory" }
                            java.io.BufferedOutputStream(java.io.FileOutputStream(partFile)).use { stream ->
                                while (true) {
                                    val next = synchronized(lock) { queue.poll() }
                                    if (next != null) {
                                        stream.write(next)
                                        stream.flush()
                                        written += next.size.toLong()
                                        lastActivity = android.os.SystemClock.elapsedRealtime()
                                        onPageFileProgress(id, written)
                                        if (!source.requestNext(id, written)) break
                                        continue
                                    }
                                    if (!source.isTransferring(id)) break
                                    if (android.os.SystemClock.elapsedRealtime() - lastActivity >= PAGE_FILE_IDLE_TIMEOUT_MS) {
                                        failure = "timeout"
                                        break
                                    }
                                    delay(PUMP_IDLE_DELAY_MS)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.e(TAG, "Page file write failed: $id", error)
                            failure = failure ?: "write"
                        } finally {
                            // Close, inspect and publish under the same lock as writing.
                            withContext(kotlinx.coroutines.NonCancellable) {
                                finishPageFileLocked(id, failure, listOf(partFile).filter { it.isFile }, pump)
                            }
                        }
                    }
                }

                val sink = PageFileSink { bytes ->
                    // Handed off, not written: this runs on the WebView's thread and must not touch the disk.
                    synchronized(lock) { queue.addLast(bytes) }
                }
                if (!source.start(id, offer.url, offer.size, sink)) {
                    job.cancel()
                    return false
                }
                pageFileSources[id] = source
                jobs[id] = job
                job.invokeOnCompletion {
                    jobs.remove(id, job)
                    removeTransferLockIfIdle(id, writerLock)
                }
                // Start the service and request the first slice before releasing the writer lock.
                // A refused service must never leave a pump writing without a foreground owner.
                if (startTransferService() && source.requestNext(id, 0L)) {
                    job.start()
                } else {
                    source.cancel()
                    job.cancel()
                    firstRequestAccepted = false
                }
            } finally {
                writerLock.unlock()
                removeTransferLockIfIdle(id, writerLock)
            }
        }
        if (!firstRequestAccepted) {
            failPageFile(id, "unavailable")
            return false
        }
        return true
    }

    /** Records an asynchronous page-side failure without performing disk IO on the caller's thread. */
    fun onPageFileFailed(id: Long, reason: String) {
        if (closed) return
        pageFileFailures.putIfAbsent(id, reason)
        pageFileSources[id]?.cancel()
    }

    /** Called by [PageFileDownload] for each slice so the sheet and notification keep up. */
    fun onPageFileProgress(id: Long, written: Long) {
        val transferJob = jobs[id]
        synchronized(taskLock) {
            metadata.computeIfPresent(id) { _, current ->
                if (transferJob != null && jobs[id] !== transferJob) current
                else if (!current.status.active) current
                else current.copy(
                    bytesDownloaded = written.coerceAtLeast(0L),
                    // The declared length is the only total a page file has; it was fixed when the
                    // user confirmed, so progress stays a fraction of what they agreed to.
                    totalBytes = if (current.totalBytes > 0) current.totalBytes else written,
                )
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastPageProgressPublish.get()
        if (now - previous >= PAGE_PROGRESS_PUBLISH_INTERVAL_MS &&
            lastPageProgressPublish.compareAndSet(previous, now)
        ) publishSnapshots()
    }

    /** Completes a page file while the pump owns its writer lock. Stale owners do nothing. */
    internal suspend fun finishPageFileLocked(id: Long, reason: String?, parts: List<File>, publishing: Job) {
        if (closed || jobs[id] !== publishing) return
        val entry = metadata[id] ?: return
        fromPageTasks.remove(id)
        // Taken out here rather than in failPageFile, because this method is the production path
        // and it used to remove the source *before* delegating — so the cancel inside failPageFile
        // found nothing, and a failed write left the page still reporting a transfer it would never
        // finish. The reference is dropped either way; only a failure also stops the source.
        val source = pageFileSources.remove(id)
        // Read the registry inside the lock: failPageFile may record while the pump is
        // finishing, and the pump's own finally must not consume the marker either.
        val failure = reason ?: pageFileFailures.remove(id) ?: source?.failureReason(id)
        val totalBytes = if (failure == null) parts.sumOf { it.length() } else 0L
        if (failure == null && totalBytes >= entry.totalBytes) {
            try {
                publishParts(id, parts, totalBytes, actualThreads = 1, clearSource = true, owner = publishing)
                // A failure may arrive while publishParts holds the lock. The marker is
                // checked once more here; the published copy is removed and the record ends
                // FAILED instead of flapping to COMPLETED after the page already gave up.
                val lateFailure = pageFileFailures.remove(id)
                if (lateFailure != null) {
                    val destination = metadata[id]?.destinationUri
                    if (destination != null) destinationWriter.delete(destination)
                    source?.cancel()
                    synchronized(taskLock) {
                        metadata.computeIfPresent(id) { _, latest ->
                            latest.copy(status = DownloadStatus.FAILED, destinationUri = null,
                                failure = DownloadFailure.SOURCE_UNAVAILABLE)
                        }
                    }
                    cleanupTemporaryFiles(id)
                    // The surrounding finally owns jobs removal, persistence, snapshots and
                    // the completion notification; doing them here as well duplicated all
                    // four and could publish a stale snapshot.
                    return
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The destination refused the file — a revoked tree, a full volume, a provider that
                // will not open. The task has to end in FAILED here: letting this escape the
                // writer’s finally would leave the record in SAVING with its parts on disk.
                Log.e(TAG, "Page file publish failed: $id", error)
                source?.cancel()
                failPageFileLocked(id, "publish", publishing)
                return
            } finally {
                // The published copy is a separate file; the temporary parts behind it are this
                // path's to remove, exactly as the engine's own finally does after publishing.
                val latest = metadata[id]
                if (latest == null || latest.status == DownloadStatus.COMPLETED) {
                    cleanupTemporaryFiles(id)
                }
                jobs.remove(id, publishing)
                persistMetadata()
                publishSnapshots()
                if (latest?.status == DownloadStatus.COMPLETED) {
                    DownloadNotifications.completed(appContext, localItem(latest))
                }
            }
            return
        }
        source?.cancel()
        failPageFileLocked(id, failure ?: "short", publishing)
    }

    /** Marks failure immediately when idle; file cleanup waits for the writer on IO. */
    fun failPageFile(id: Long, reason: String) {
        if (closed) return
        // Record first, so a writer that is already past its success check still has a chance
        // to see the marker when it takes the lock. The lock paths below remove it again.
        pageFileFailures.putIfAbsent(id, reason)
        pageFileSources[id]?.cancel()
        jobs[id]?.cancel()
        // Source setup and failure can race; both must use the same writer lock.
        val writerLock = transferLocks.computeIfAbsent(id) { Mutex() }
        if (writerLock.tryLock()) {
            try {
                if (closed) return
                failPageFileLocked(id, reason, jobs[id], cleanFiles = false)
                transferScope.launch {
                    writerLock.withLock { cleanupTemporaryFiles(id) }
                    removeTransferLockIfIdle(id, writerLock)
                }
            } finally {
                writerLock.unlock()
                removeTransferLockIfIdle(id, writerLock)
            }
        } else {
            // A writer still owns the lock. It will see the marker in its own locked path. If it
            // has already passed that check, a short follow-up removes the record and fails the
            // task instead of leaving a permanent marker.
            transferScope.launch {
                try {
                    writerLock.withLock {
                        val late = pageFileFailures.remove(id) ?: return@withLock
                        if (!closed) {
                            val latest = metadata[id]
                            if (latest != null &&
                                latest.status.active
                            ) {
                                failPageFileLocked(id, late, jobs[id])
                            }
                        }
                    }
                } finally {
                    removeTransferLockIfIdle(id, writerLock)
                }
            }
        }
    }

    private fun failPageFileLocked(id: Long, reason: String, owner: Job? = null, cleanFiles: Boolean = true) {
        if (owner != null && jobs[id] !== owner) {
            pageFileFailures.remove(id)
            return
        }
        fromPageTasks.remove(id)
        pageFileFailures.remove(id)
        // Stopping the source matters as much as forgetting it: while it still reports a
        // transfer, the page next blob download is refused because this one looks running.
        pageFileSources.remove(id)?.cancel()
        if (metadata[id] == null) {
            owner?.let { jobs.remove(id, it) }
            return
        }
        Log.w(TAG, "Page file download failed: $id ($reason)")
        if (cleanFiles) cleanupTemporaryFiles(id)
        synchronized(taskLock) {
            metadata.computeIfPresent(id) { _, current ->
                if (current.status.active)
                    current.copy(status = DownloadStatus.FAILED,
                        failure = if (reason == "write" || reason == "publish") DownloadFailure.STORAGE else DownloadFailure.SOURCE_UNAVAILABLE) else current
            }
        }
        owner?.let { jobs.remove(id, it) }
        persistMetadata()
        publishSnapshots()
    }

    /**
     * Whether [entry] is a download whose bytes came from the page rather than from an address.
     *
     * The in-memory set is the live answer; the placeholder prefix is what survives a restart, and a
     * restored record has to be recognised too — otherwise it comes back offering a resume that
     * [retry] would refuse.
     */
    private fun isPageFile(entry: DownloadMetadata): Boolean =
        entry.id in fromPageTasks || entry.url.startsWith(PAGE_FILE_PREFIX)

    /** The [PageFileSource] filling each task, so cancel, delete and failure can stop it. */
    private val pageFileSources = ConcurrentHashMap<Long, PageFileSource>()

    /** The newest non-failed task already owning [identityUrl] in the given context, if any. */
    fun existingTaskFor(identityUrl: String, isPrivate: Boolean): Pair<Long, DownloadStatus>? =
        synchronized(taskLock) {
            existingMetadataFor(identityUrl, if (isPrivate) privateQueryIdentity() else DownloadIdentity.Normal)
        }?.let { it.id to it.status }

    /**
     * Starts a new private download scope. Private records only claim URLs inside the
     * session that created them, so the same link in a later private session starts a
     * fresh task instead of surfacing the previous session's record.
     */
    fun rotatePrivateScope() {
        synchronized(taskLock) {
            sessionIdentity = DownloadIdentity.PrivateSession(DownloadIdentity.nextScope())
        }
        // The identity decides which tasks the list may offer to resume, so the projection the
        // UI reads has to be rebuilt with it.
        publishSnapshots()
    }

    /** Returns to normal browsing: private records stop claiming new requests' URLs. */
    fun endPrivateScope() {
        val privateTasks = synchronized(taskLock) {
            sessionIdentity = DownloadIdentity.Normal
            metadata.values.filter { it.identity.isPrivate }.map { it.id to jobs[it.id] }
        }
        // Ending the session prevents resuming; it does not cancel an accepted download or
        // remove its record. Each writer keeps its working data until that transfer finishes.
        privateTasks.forEach { (id, transfer) ->
            transferScope.launch {
                transfer?.join()
                val writerLock = transferLocks[id]
                if (writerLock == null) clearEndedPrivateTask(id)
                else {
                    writerLock.withLock { clearEndedPrivateTask(id) }
                    removeTransferLockIfIdle(id, writerLock)
                }
                publishSnapshots()
            }
        }
        publishSnapshots()
    }

    /** Called only after the accepted writer has stopped; retries may still need its playlist. */
    private fun clearEndedPrivateTask(id: Long) {
        cleanupTemporaryFiles(id)
        synchronized(taskLock) {
            metadata.computeIfPresent(id) { _, entry ->
                entry.copy(url = if (isPageFile(entry)) DownloadValidation.PAGE_FILE_PREFIX else "",
                    cookie = null, referer = null)
            }
        }
    }

    /**
     * The identity a task created now belongs to.
     *
     * A private task belongs to the running session. One created without a session start — a
     * launch that opens straight into private mode — takes a fresh scope, which is what lets the
     * rest of that session's requests deduplicate against it instead of starting parallel copies.
     */
    private fun identityForNewTask(isPrivate: Boolean): DownloadIdentity = if (!isPrivate) {
        DownloadIdentity.Normal
    } else {
        sessionIdentity.takeIf { it.isPrivate }
            ?: DownloadIdentity.PrivateSession(DownloadIdentity.nextScope()).also { sessionIdentity = it }
    }

    /**
     * The identity a private *query* is about: the running session, or the placeholder that only
     * a migrated record can carry when no session is running.
     */
    private fun privateQueryIdentity(): DownloadIdentity =
        sessionIdentity.takeIf { it.isPrivate } ?: DownloadIdentity.UNRECORDED

    /**
     * The filename a confirmed request for [url] will most likely receive. The unique
     * suffix assigned at enqueue time may still differ when another task takes the name
     * first; the started download reports the final name.
     */
    fun previewFilename(url: String, contentDisposition: String?, mimeType: String?): String? {
        val cleanUrl = validDownloadUrl(url) ?: return null
        val safeMime = mimeType?.substringBefore(';')?.let(::sanitizeMime).orEmpty()
            .ifEmpty { "application/octet-stream" }
        val safeDisposition = sanitizeHeader(contentDisposition, MAX_CONTENT_DISPOSITION_LENGTH)
        val resolved = FilenameParser.resolve(safeDisposition, cleanUrl, safeMime)
        return sanitizeFilename(
            if (HlsDownloadEngine.handles(cleanUrl, safeMime)) HlsDownloadEngine.outputName(resolved) else resolved,
        )
    }

    /** The half of a task that fetches [url]; page files never reach it. */
    private fun transportFor(url: String, mimeType: String): DownloadTransport = when {
        HlsDownloadEngine.handles(url, mimeType) -> hls
        url.startsWith("ftp://", ignoreCase = true) -> ftp
        else -> engine
    }

    private fun existingMetadataFor(
        dedupUrl: String,
        identity: DownloadIdentity,
        includeCompleted: Boolean = true,
        excludeId: Long? = null,
    ): DownloadMetadata? =
        metadata.values
            // Identity is the whole dedup scope: tasks of one private session coalesce with each
            // other, and never with another session's, while ordinary tasks share one identity.
            .filter { it.id != excludeId && it.identity == identity && it.status != DownloadStatus.FAILED }
            .filter { includeCompleted || it.status != DownloadStatus.COMPLETED }
            .filter { it.url.substringBefore('#') == dedupUrl }
            .maxByOrNull { it.timestamp }

    /** Enqueues a validated HTTP(S) download and returns its app-local id. */
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String? = null,
        isPrivate: Boolean = false,
        cookieHeader: String? = if (isPrivate) null else runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull(),
    ): Long? = when (val outcome = enqueueOrGetExisting(url, userAgent, contentDisposition, mimeType, referer, isPrivate, cookieHeader)) {
        is EnqueueOutcome.Started -> outcome.id
        else -> null
    }

    /** Cancels an active task and removes its incomplete data and browser record. */
    fun cancel(id: Long) {
        if (closed) return
        val entry = synchronized(taskLock) {
            val current = metadata[id] ?: return
            // A completed download is terminal: its file and row belong to the library, not to a
            // notification action that raced the completion. Removing while holding the same lock
            // as progress and completion makes the status decision atomic.
            if (current.status == DownloadStatus.COMPLETED || id in deleting) return
            if (metadata.remove(id) == null) return
            current
        }
        interrupted.remove(id)
        responses.remove(id)?.close()
        // The record dies here, so the page must stop handing bytes over: the sink they reach would
        // otherwise keep writing into a task nothing is watching.
        pageFileSources.remove(id)?.cancel()
        fromPageTasks.remove(id)
        pageFileFailures.remove(id)
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
     * Removes one record, stopping it first when it is still transferring. If [deleteFile] is true,
     * the record is retained when its corresponding local file cannot be deleted so the user can
     * retry instead of losing it.
     */
    fun delete(
        id: Long,
        deleteFile: Boolean,
        onComplete: (DownloadDeleteResult) -> Unit = {},
    ) = deleteRecord(id, deleteFile, null, onComplete)

    private fun deleteRecord(
        id: Long, deleteFile: Boolean, expected: DownloadMetadata?, onComplete: (DownloadDeleteResult) -> Unit,
    ) {
        val writerLock = synchronized(taskLock) {
            val entry = metadata[id]
            if (closed || entry == null || (expected != null && expected != entry) || !deleting.add(id)) {
                return@synchronized null
            }
            interrupted.remove(id)
            responses.remove(id)?.close()
            pageFileSources.remove(id)?.cancel()
            fromPageTasks.remove(id)
            pageFileFailures.remove(id)
            jobs.remove(id)?.cancel()
            transferLocks.computeIfAbsent(id) { Mutex() }
        }
        if (writerLock == null) {
            onComplete(DownloadDeleteResult(0, 0))
            return
        }
        transferScope.launch {
            var removed = 0
            var fileDeleted = true
            try {
                // A cancelled job may still be unwinding an earlier writer. Read metadata only
                // after that writer releases the lock; progress/saving may have replaced it.
                writerLock.withLock {
                    val entry = metadata[id] ?: return@withLock
                    fileDeleted = !deleteFile || deleteStoredFile(entry)
                    if (fileDeleted) {
                        if (metadata.remove(id) != null) removed = 1
                        cleanupTemporaryFiles(id)
                    } else {
                        // Retain a usable record when file deletion fails, never an active row
                        // whose transfer has already been cancelled.
                        metadata.computeIfPresent(id) { _, current ->
                            if (current.status.active) current.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0)
                            else current
                        }
                    }
                }
                if (removed > 0) {
                    releaseUnusedDirectoryGrants()
                    DownloadNotifications.dismiss(appContext, id)
                }
                persistMetadata()
                publishSnapshots()
                refresh()
            } finally {
                deleting.remove(id)
                removeTransferLockIfIdle(id, writerLock)
            }
            withContext(Dispatchers.Main.immediate) {
                onComplete(DownloadDeleteResult(removed, if (fileDeleted) 0 else 1))
            }
        }
    }

    /** Pausing keeps validated partial bytes; cancellation removes the whole task. */
    fun pause(id: Long) {
        // A page file is not pausable: the document that held its bytes cannot hand them over again,
        // so pausing would leave a partial file behind for a transfer nothing can continue.
        if (metadata[id]?.let(::isPageFile) == true) return
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
            responses.remove(id)?.close()
            interrupted.remove(id)
            jobs[id]?.cancel()
        }
        persistMetadata()
        publishSnapshots()
    }

    /** Only tasks interrupted while running are resumed automatically, once foregrounded. */
    fun resumeInterrupted() {
        interrupted.toList().forEach { id -> if (interrupted.remove(id) && id !in fromPageTasks) retry(id) }
    }

    fun retry(id: Long): Long? = restart(id)

    /** Private cookies are obtained for the new URL from the requesting browser session. */
    suspend fun updateLink(id: Long, url: String, privateCookieFor: suspend (String) -> String?): Boolean {
        val clean = validHttpUrl(url) ?: return false
        if (!canUpdateLink(id)) return false
        val privateCookie = if (metadata[id]?.identity?.isPrivate == true) privateCookieFor(clean) else null
        return restart(id, clean, privateCookie) != null
    }

    /** Read the checkpoint on demand, never while publishing progress snapshots. */
    suspend fun canUpdateLink(id: Long): Boolean = withContext(Dispatchers.IO) {
        val entry = metadata[id] ?: return@withContext false
        canUpdateLink(entry) && DownloadCheckpoint.read(temporaryDirectory(id), entry.url) != null
    }

    private data class ReplacementLink(val url: String, val cookie: String?)

    private fun canUpdateLink(entry: DownloadMetadata): Boolean =
        entry.backend == DownloadBackend.LOCAL && entry.identity.canResumeIn(sessionIdentity) &&
            entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED) &&
            validHttpUrl(entry.url) != null && !HlsDownloadEngine.handles(entry.url, entry.mimeType)

    private fun restart(id: Long, replacementUrl: String? = null, privateCookie: String? = null): Long? = synchronized(taskLock) {
        if (closed || id in deleting) return null
        val old = metadata[id] ?: return null
        if (replacementUrl != null && (!canUpdateLink(old) || HlsDownloadEngine.handles(replacementUrl, old.mimeType))) return null
        // A page file can never be fetched again: its bytes were handed over by the document that
        // built them and that document is gone. Offering a resume would only produce a failure.
        if (isPageFile(old)) return null
        if (!old.identity.canResumeIn(sessionIdentity)) return null
        if (old.status !in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.PAUSED)) return null
        val destination = if (old.destinationMode == DownloadDestinationMode.CUSTOM_DIRECTORY &&
            !DownloadSettingsRepository.hasWritableGrant(appContext, old.customTreeUri)
        ) settingsRepository.load().destination()
        else DownloadDestination(old.destinationMode, old.customTreeUri, old.destinationLabel)
        if (old.status == DownloadStatus.COMPLETED || old.backend == DownloadBackend.LEGACY_SYSTEM) {
            // A new generation never owns/deletes the old file or record. Active copies
            // still coalesce so repeated taps cannot create parallel identical transfers.
            // A task of another session may be viewed, but cannot borrow this one's identity.
            val replacingLegacy = old.backend == DownloadBackend.LEGACY_SYSTEM && old.status != DownloadStatus.COMPLETED
            val cookie = cookieFor(old)
            return when (val outcome = enqueueOrGetExisting(
                old.url, old.userAgent, old.contentDisposition, old.mimeType, old.referer,
                isPrivate = old.identity.isPrivate, cookieHeader = cookie, newCopy = true,
                filename = old.filename,
                destination = destination,
                replacingLegacyId = if (replacingLegacy) id else null,
            )) {
                is EnqueueOutcome.Started -> outcome.id.also { if (replacingLegacy) cancel(id) }
                is EnqueueOutcome.Existing -> outcome.id
                EnqueueOutcome.Rejected -> null
            }
        }
        val replacement = replacementUrl?.let {
            ReplacementLink(it, if (old.identity.isPrivate) sanitizeHeader(privateCookie, MAX_COOKIE_LENGTH)
                else cookieFor(old.copy(url = it)))
        }
        val cookie = cookieFor(old)
        if (!metadata.replace(id, old, old.copy(
                status = DownloadStatus.QUEUED,
                destinationMode = destination.mode,
                customTreeUri = destination.treeUri,
                destinationLabel = destination.label,
                cookie = cookie,
                bytesPerSecond = 0,
                failure = null,
                // The handed-over response has been consumed or closed. Retry now owns a
                // normal HTTP transfer, so it can resume and recover across process death.
                responseBacked = false,
            ))) return null
        val previous = jobs[id]
        previous?.cancel()
        launchTransfer(id, replacement)
        persistMetadata()
        publishSnapshots()
        id
    }

    private fun launchTransfer(id: Long, replacement: ReplacementLink? = null) = synchronized(taskLock) {
        if (closed || id in deleting || metadata[id]?.status?.active != true) return@synchronized
        val writerLock = transferLocks.computeIfAbsent(id) { Mutex() }
        val job = transferScope.launch(start = CoroutineStart.LAZY) {
            writerLock.withLock {
                val entry = metadata[id]
                if (entry?.status?.active == true) {
                    val response = responses[id]
                    val host = (replacement?.url ?: entry.url).toUri().host.orEmpty()
                    if (response == null) {
                        awaitNetwork(id, entry.unmeteredOnly)
                        tasks.acquire(host).use {
                            if (metadata[id]?.status?.active == true) performDownload(id, replacement)
                        }
                    } else {
                        // Bound only the acquisition, never the transfer. A response already
                        // accepted by the user must not silently turn into another GET.
                        var lease: ConcurrencyBudget.Lease? = null
                        try {
                            val allowed = networkAllowed(entry.unmeteredOnly, response.network)
                            // Store the lease inside the timeout: cancellation after acquire
                            // returns must still release it in finally.
                            val acquired = allowed && response.unavailable.value == null && withTimeoutOrNull(5_000) {
                                lease = tasks.acquire(host)
                                true
                            } == true
                            if (!acquired) response.discard(if (allowed) DownloadResponse.Unavailable.EXPIRED else DownloadResponse.Unavailable.NETWORK)
                            if (metadata[id]?.status?.active == true) performDownload(id)
                        } finally { lease?.close() }
                    }
                }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            removeTransferLockIfIdle(id, writerLock)
        }
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
            DownloadBackend.LOCAL -> entry.destinationUri?.toUri()
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
        val entries = terminalIds.mapNotNull(metadata::get).filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
        if (entries.isEmpty()) {
            onComplete(DownloadDeleteResult(0, 0))
            return
        }
        var remaining = entries.size
        var removed = 0
        var failed = 0
        // Delete callbacks, including immediate refusals, are aggregated on the main thread.
        transferScope.launch(Dispatchers.Main.immediate) {
            for (entry in entries) {
                deleteRecord(entry.id, deleteFiles, entry) { result ->
                    removed += result.removedCount
                    failed += result.failedFileCount
                    if (--remaining == 0) onComplete(DownloadDeleteResult(removed, failed))
                }
            }
        }
    }

    /** Called when Android's foreground-service time budget expires. */
    fun pauseActiveTransfers() {
        metadata.values.filter { it.backend == DownloadBackend.LOCAL && it.status.active }.forEach { entry ->
            if (isPageFile(entry)) failPageFile(entry.id, "service_timeout") else pause(entry.id)
        }
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
            var statusChanged = false
            for (item in next) metadata.computeIfPresent(item.id) { _, entry ->
                if (entry.status == item.status) entry else {
                    statusChanged = true
                    entry.copy(status = item.status)
                }
            }
            val present = next.mapTo(HashSet()) { it.id }
            if (metadata.keys.removeIf { id ->
                    metadata[id]?.backend == DownloadBackend.LEGACY_SYSTEM && id !in present
                } || statusChanged
            ) {
                persistMetadata()
            }
            legacySnapshot = next
            publishSnapshots()
        }
    }

    override fun close() {
        // A finishing transfer takes taskLock to publish its terminal state, so closing under the
        // same lock keeps the record and the job from disagreeing about the last write.
        synchronized(taskLock) {
            if (closed) return
            closed = true
            settingsObserver.close()
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            responses.values.forEach { it.close() }
            responses.clear()
            metadata.replaceAll { _, entry ->
                if (entry.backend == DownloadBackend.LOCAL &&
                    entry.status.active
                ) {
                    entry.copy(status = DownloadStatus.PAUSED)
                } else {
                    entry
                }
            }
        }
        persistMetadata()
        monitorJob?.cancel()
        refreshJob?.cancel()
        transferScope.cancel()
        runCatching { appContext.unregisterReceiver(completionReceiver) }
    }

    private fun networkAllowed(unmeteredOnly: Boolean, responseNetwork: android.net.Network? = null): Boolean {
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val network = responseNetwork ?: connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!unmeteredOnly || capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    private fun updateTransfer(id: Long, owner: Job, update: (DownloadMetadata) -> DownloadMetadata) =
        synchronized(taskLock) {
            if (jobs[id] === owner) metadata.computeIfPresent(id) { _, value -> update(value) }
        }

    private suspend fun awaitNetwork(id: Long, unmeteredOnly: Boolean) {
        val owner = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (networkAllowed(unmeteredOnly)) break
            updateTransfer(id, owner) { value -> if (value.status.active) value.copy(status = DownloadStatus.WAITING_NETWORK) else value }
            publishSnapshots()
            delay(1500)
        }
        updateTransfer(id, owner) { value -> if (value.status.active) value.copy(status = DownloadStatus.QUEUED) else value }
    }

    private suspend fun <T> retryTransfer(id: Long, unmeteredOnly: Boolean, action: suspend () -> T): T {
        var retries = 0
        val owner = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
        while (true) {
            awaitNetwork(id, unmeteredOnly)
            updateTransfer(id, owner) { value -> if (value.status.active) value.copy(status = DownloadStatus.DOWNLOADING) else value }
            publishSnapshots()
            try { return action() } catch (error: java.io.IOException) {
                if (error is UnsupportedHlsException) throw error
                if (error is DownloadHttpException && !error.isTransient) throw error
                if (error is DownloadFtpException && !error.isTransient) throw error
                if (++retries > 2) throw error
                updateTransfer(id, owner) { value -> if (value.status.active) value.copy(status = DownloadStatus.QUEUED, bytesPerSecond = 0) else value }
                publishSnapshots()
                delay(1000L shl (retries - 1))
            }
        }
    }

    /**
     * Publishes verified temporary parts and marks [id] complete.
     *
     * This is the half of a download that has nothing to do with where the bytes came from: the parts
     * are already on disk and validated, and what is left is opening the destination, copying them in,
     * recording the result or deleting the file again if the record vanished underneath us. Both
     * transfer kinds share it — [performDownload] after the engine fetched segments, and
     * [finishPageFileLocked] after the page handed slices over.
     *
     * The caller owns the terminal state of its own coroutine ([jobs], temporary cleanup); this only
     * moves the task through SAVING to COMPLETED.
     */
    private suspend fun publishParts(
        id: Long,
        parts: List<File>,
        totalBytes: Long,
        actualThreads: Int,
        /** Cleared once published: a page file has no address to redownload from. */
        clearSource: Boolean,
        /**
         * The transfer publishing these bytes.
         *
         * A pause followed immediately by a retry replaces the job but keeps the task id, and the
         * retry reuses the same temporary directory. The previous transfer therefore has to prove
         * it is still the one that owns the record before it marks anything SAVING or COMPLETED:
         * without that, a transfer that had just been superseded published its stale parts under
         * the new task, set it COMPLETED, and then deleted the directory the new transfer was
         * about to write into.
         */
        owner: Job,
        hlsContainer: HlsContainer? = null,
    ) {
        synchronized(taskLock) {
            metadata.computeIfPresent(id) { _, current ->
                if (jobs[id] !== owner || !current.status.active) current
                else current.copy(status = DownloadStatus.SAVING)
            }
        }
        publishSnapshots()
        // Superseded while finishing: the retry owns the record now, and writing here would
        // overwrite its state and publish bytes that belong to the transfer it replaced.
        if (jobs[id] !== owner) return
        val current = metadata[id] ?: return
        val published = destinationWriter.publish(
            settings = DownloadSettings(
                destinationMode = current.destinationMode,
                customTreeUri = current.customTreeUri,
                customDirectoryLabel = current.destinationLabel,
                threadCount = current.configuredThreadCount,
            ),
            preferredName = hlsContainer?.filename(current.filename) ?: current.filename,
            mimeType = hlsContainer?.mimeType ?: current.mimeType,
            parts = parts,
            onProgress = { percent ->
                synchronized(taskLock) {
                    metadata.computeIfPresent(id) { _, latest ->
                        if (jobs[id] !== owner || latest.status != DownloadStatus.SAVING) latest
                        else latest.copy(savingProgress = percent)
                    }
                }
                publishSnapshots()
            },
        )
        var accepted = false
        synchronized(taskLock) {
            metadata.computeIfPresent(id) { _, latest ->
                if (jobs[id] !== owner || latest.status != DownloadStatus.SAVING) latest
                else {
                    accepted = true
                    latest.copy(
                        filename = published.displayName,
                        outputMimeType = hlsContainer?.mimeType,
                        destinationUri = published.uri.toString(),
                        status = DownloadStatus.COMPLETED,
                        cookie = latest.cookie.takeIf { latest.identity.isPrivate && latest.identity == sessionIdentity },
                        referer = if (clearSource) null else latest.referer,
                        bytesDownloaded = totalBytes,
                        totalBytes = totalBytes,
                        actualThreadCount = actualThreads,
                    )
                }
            }
        }
        // Cancellation can remove metadata between publishing and the atomic update. Never leave an
        // untracked file behind in that race.
        if (!accepted) destinationWriter.delete(published.uri.toString())
    }

    private suspend fun performDownload(id: Long, replacement: ReplacementLink? = null) {
        var entry = metadata[id] ?: return
        val response = responses[id]
        val tempDirectory = temporaryDirectory(id)
        val transferJob = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
        var sampleTime = android.os.SystemClock.elapsedRealtime()
        var sampleBytes = entry.bytesDownloaded
        try {
            val onProgress: (Long, Long, Int) -> Unit = { downloaded, total, actualThreads ->
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
                    if (now - previous > PROGRESS_PERSIST_INTERVAL_MS &&
                        lastProgressPersist.compareAndSet(previous, now)
                    ) persistMetadata()
                }
            val payload = if (replacement != null) {
                updateTransfer(id, transferJob) { it.copy(status = DownloadStatus.DOWNLOADING) }
                publishSnapshots()
                val referer = com.mybrowser.tabs.MediaNavigation.create(replacement.url, entry.referer)?.referer
                val headers = DownloadRequestHeaders(entry.userAgent, replacement.cookie, referer)
                val (checkpoint, entityUrl) = engine.replacementCheckpoint(entry.url, replacement.url, headers, tempDirectory)
                synchronized(taskLock) {
                    transferJob.ensureActive()
                    if (jobs[id] !== transferJob) throw CancellationException()
                    checkpoint.save(tempDirectory)
                    entry = requireNotNull(metadata[id]).copy(url = replacement.url, cookie = replacement.cookie, referer = referer)
                    metadata[id] = entry
                }
                persistMetadata()
                publishSnapshots()
                engine.resumeReplacement(entry.url, headers, tempDirectory, checkpoint, entityUrl, onProgress)
            } else if (response != null) {
                if (!networkAllowed(entry.unmeteredOnly, response.network)) response.discard(DownloadResponse.Unavailable.NETWORK)
                if (!response.start()) throw java.io.IOException("The original download response is no longer available")
                synchronized(taskLock) {
                    metadata.computeIfPresent(id) { _, current ->
                        if (jobs[id] === transferJob && current.status.active)
                            current.copy(status = DownloadStatus.DOWNLOADING) else current
                    }
                }
                if (HlsDownloadEngine.handles(entry.url, entry.mimeType)) {
                    // The handed-over body is the playlist itself. Only the playlist is one-shot:
                    // the segments behind it are ordinary requests. Keeping its text means a pause
                    // and continue does not need the consumed response or a new request.
                    val playlist = response.use { HlsDownloadEngine.playlistText(it.body) }
                    if (!entry.identity.isPrivate) {
                        tempDirectory.mkdirs()
                        HlsDownloadEngine.writeAtomically(File(tempDirectory, HlsDownloadEngine.PLAYLIST_NAME), playlist)
                    }
                    synchronized(taskLock) {
                        metadata.computeIfPresent(id) { _, current ->
                            if (jobs[id] !== transferJob || !entry.identity.canResumeIn(sessionIdentity)) current
                            else {
                                if (entry.identity.isPrivate) privatePlaylists[id] = playlist
                                current.copy(responseBacked = false)
                            }
                        }
                    }
                    persistMetadata()
                    retryTransfer(id, entry.unmeteredOnly) {
                        hls.downloadHandedOver(
                            url = entry.url,
                            headers = DownloadRequestHeaders(entry.userAgent, entry.cookie, entry.referer),
                            tempDirectory = tempDirectory,
                            onProgress = onProgress,
                            playlistText = playlist,
                        )
                    }
                } else engine.downloadResponse(response, tempDirectory, onProgress)
            } else retryTransfer(id, entry.unmeteredOnly) {
                val keptPlaylist = File(tempDirectory, HlsDownloadEngine.PLAYLIST_NAME)
                val retained = privatePlaylists[id]
                if (HlsDownloadEngine.handles(entry.url, entry.mimeType) && (retained != null || !entry.identity.isPrivate && keptPlaylist.isFile)) {
                    hls.downloadHandedOver(
                        url = entry.url,
                        headers = DownloadRequestHeaders(entry.userAgent, entry.cookie, entry.referer),
                        tempDirectory = tempDirectory,
                        onProgress = onProgress,
                        playlistText = retained ?: keptPlaylist.readText(),
                    )
                } else transportFor(entry.url, entry.mimeType).download(
                    url = entry.url,
                    headers = DownloadRequestHeaders(entry.userAgent, entry.cookie, entry.referer),
                    requestedThreads = entry.configuredThreadCount,
                    tempDirectory = tempDirectory,
                    onProgress = onProgress,
                )
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            publishParts(id, payload.parts, payload.totalBytes, payload.actualThreadCount, clearSource = false,
                owner = transferJob, hlsContainer = payload.hlsContainer)
        } catch (cancelled: CancellationException) {
            // An explicit cancel removes metadata first. close() changes surviving entries to
            // PAUSED, so there is no state to publish from this coroutine.
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Download failed: $id", error)
            synchronized(taskLock) {
                metadata.computeIfPresent(id) { _, current ->
                    if (jobs[id] === transferJob && current.status.active)
                        current.copy(status = DownloadStatus.FAILED, failure = when {
                            error is DownloadLinkMismatch -> DownloadFailure.CANNOT_RESUME
                            error is UnsupportedHlsException -> DownloadFailure.UNSUPPORTED
                            current.status == DownloadStatus.SAVING || error is SecurityException -> DownloadFailure.STORAGE
                            response?.unavailable?.value != null -> DownloadFailure.SOURCE_UNAVAILABLE
                            error is DownloadHttpException -> when (error.code) {
                                401, 403 -> DownloadFailure.AUTHORIZATION
                                404, 410 -> DownloadFailure.NOT_FOUND
                                else -> DownloadFailure.SERVER
                            }
                            error is DownloadFtpException -> if (error.code == 530 || error.code == 532)
                                DownloadFailure.AUTHORIZATION else DownloadFailure.SERVER
                            else -> DownloadFailure.NETWORK
                        }) else current
                }
            }
        } finally {
            if (response != null) {
                responses.remove(id, response)
                response.close()
            }
            val latest = metadata[id]
            if (latest == null || latest.status == DownloadStatus.COMPLETED) {
                cleanupTemporaryFiles(id)
            }
            persistMetadata()
            publishSnapshots()
            if (latest?.status == DownloadStatus.COMPLETED) DownloadNotifications.completed(appContext, localItem(latest))
            else if (latest?.status == DownloadStatus.FAILED && latest.failure in setOf(
                    DownloadFailure.AUTHORIZATION, DownloadFailure.NOT_FOUND, DownloadFailure.CANNOT_RESUME)) {
                DownloadNotifications.needsLink(appContext, localItem(latest))
            }
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
                mimeType = entry.mimeType,
                progress = progress(downloaded, total),
                bytesDownloaded = downloaded,
                totalBytes = total,
                timestamp = entry.timestamp,
                threadCount = 1,
                destinationLabel = SYSTEM_DIRECTORY_LABEL,
                // Identity decides these two here as well: a migrated private record must not be
                // offered a resume the manager refuses, and must not have its filename read out.
                showsFilenameInNotification = entry.identity.showsFilenameInNotification,
                canResume = entry.identity.canResumeIn(sessionIdentity),
                sourceUrl = entry.url.takeIf { entry.identity.canResumeIn(sessionIdentity) }?.let(DownloadValidation::redactCredentials),
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
        // A page file is never paused: its bytes are in the renderer and a pause would leave the
        // user's partial file for a transfer that cannot be continued from anywhere else.
        canPause = !isPageFile(entry),
        bytesPerSecond = entry.bytesPerSecond,
        savingProgress = entry.savingProgress,
        showsFilenameInNotification = entry.identity.showsFilenameInNotification,
        canResume = !isPageFile(entry) && entry.identity.canResumeIn(sessionIdentity),
        requiresNewRequest = entry.responseBacked,
        sourceUrl = entry.url.takeIf { !isPageFile(entry) && entry.identity.canResumeIn(sessionIdentity) }?.let(DownloadValidation::redactCredentials),
        failure = entry.failure,
        mimeType = entry.outputMimeType ?: entry.mimeType,
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
        return checkNotNull(FilenameParser.uniqueName(candidate, exists = names::contains))
    }

    private fun restoreMetadata() {
        val restored = metadataStore.load()
        restored.entries.forEach { metadata[it.id] = it }
        interrupted.addAll(restored.interruptedIds)
    }

    /**
     * The cookie a (re)started transfer for [entry] runs with.
     *
     * An ordinary task reads the jar as it starts, so a retry uses the current sign-in. A private
     * task keeps only the cookie its own session captured in memory: it must never acquire the
     * ordinary profile's credentials, and that captured value is what its WebView was using.
     */
    private fun cookieFor(entry: DownloadMetadata): String? = when (entry.identity.cookiePolicy) {
        CookiePolicy.READ_FROM_COOKIE_JAR ->
            sanitizeHeader(runCatching { CookieManager.getInstance().getCookie(entry.url) }.getOrNull(), MAX_COOKIE_LENGTH)
        CookiePolicy.SESSION_ONLY -> entry.cookie
    }

    private fun persistMetadata() {
        synchronized(metadataLock) {
            trimMetadata()
            metadataStore.save(metadata.values)
        }
    }

    /** Keeps both memory and the persisted representation bounded over long browser runs. */
    private fun trimMetadata() {
        synchronized(metadataLock) {
            val stale = metadata.values
                .filter { !it.status.active }
                .sortedByDescending { it.timestamp }
                .drop(MAX_METADATA_ENTRIES)
            stale.forEach { entry ->
                // Remove only the exact value examined: a task may have been replaced by a new
                // download with the same id while the snapshot was being sorted.
                if (metadata.remove(entry.id, entry)) transferScope.launch { cleanupRemovedTask(entry.id) }
            }
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
        privatePlaylists.remove(id)
        temporaryDirectory(id).deleteRecursively()
    }

    private fun removeTransferLockIfIdle(id: Long, writerLock: Mutex) = synchronized(taskLock) {
        if (jobs[id] == null && !writerLock.isLocked && id !in deleting) transferLocks.remove(id, writerLock)
    }

    private suspend fun cleanupRemovedTask(id: Long) {
        releaseUnusedDirectoryGrants()
        DownloadNotifications.dismiss(appContext, id)
        val writerLock = transferLocks[id]
        if (writerLock != null) writerLock.withLock { cleanupTemporaryFiles(id) }
        else cleanupTemporaryFiles(id)
        if (writerLock != null) removeTransferLockIfIdle(id, writerLock)
    }

    /** A confirmation owns its selected grant until enqueue or dismissal, including rotation. */
    inner class DirectorySelection internal constructor() : Closeable {
        @Volatile var destination: DownloadDestination? = null
            private set

        suspend fun select(uri: android.net.Uri): DownloadDestination? = withContext(Dispatchers.IO) {
            val caller = kotlinx.coroutines.currentCoroutineContext()
            // DocumentProvider queries can block; pause/cancel must remain independent of them.
            val label = settingsRepository.describeDirectory(uri)
            synchronized(taskLock) {
                // A rotated/dismissed dialog must not apply a selection queued before cancellation.
                caller.ensureActive()
                if (this@DirectorySelection !in pendingDirectories) return@synchronized null
                settingsRepository.keepDirectoryGrant(uri)
                DownloadDestination(DownloadDestinationMode.CUSTOM_DIRECTORY, uri.toString(),
                    label).also {
                        destination = it
                        releaseUnusedDirectoryGrants()
                    }
            }
        }

        override fun close() {
            synchronized(taskLock) { pendingDirectories.remove(this) }
            transferScope.launch { releaseUnusedDirectoryGrants() }
        }
    }

    internal fun directorySelection(): DirectorySelection = synchronized(taskLock) {
        DirectorySelection().also { pendingDirectories.add(it) }
    }

    internal fun releaseUnusedDirectoryGrants() = synchronized(taskLock) {
        synchronized(com.mybrowser.text.LocalHtmlResources) {
            val resolver = appContext.contentResolver
            val retained = metadata.values.mapNotNullTo(HashSet()) { it.customTreeUri }
            retained += com.mybrowser.text.LocalHtmlResources.retainedTrees(appContext)
            settingsRepository.customTreeUri?.let(retained::add)
            pendingDirectories.mapNotNullTo(retained) { it.destination?.treeUri }
            resolver.persistedUriPermissions.filter { android.provider.DocumentsContract.isTreeUri(it.uri) &&
                it.uri.toString() !in retained }.forEach { grant ->
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                try {
                    resolver.releasePersistableUriPermission(grant.uri, flags)
                } catch (_: SecurityException) {
                    Unit
                }
            }
        }
    }

    fun setDefaultDirectory(uri: android.net.Uri, label: String): DownloadSettings = synchronized(taskLock) {
        settingsRepository.keepDirectoryGrant(uri)
        settingsRepository.useCustomDirectory(uri, label).also { releaseUnusedDirectoryGrants() }
    }

    private fun cleanupOrphanedTemporaryFiles() {
        releaseUnusedDirectoryGrants()
        // Releases before 0.5.0 treated partial bytes as disposable external cache.
        appContext.externalCacheDir?.let { File(it, TEMP_DIRECTORY_NAME).deleteRecursively() }
        File(appContext.cacheDir, TEMP_DIRECTORY_NAME).deleteRecursively()
        temporaryRoot().listFiles().orEmpty().forEach { directory ->
            val id = directory.name.toLongOrNull()
            val entry = id?.let(metadata::get)
            // Partial bytes are worth keeping only for a task this session may resume. A private
            // task from another session can never be resumed, and its sidecar used to name the URL
            // it was started from, so the directory is not kept — which is also what removes the
            // plain-URL checkpoints written before the sidecar switched to digests.
            val keep = entry?.backend == DownloadBackend.LOCAL &&
                entry.status != DownloadStatus.COMPLETED &&
                // Restored page files are paused and have no document. A new page-file pump
                // can already be writing while this startup cleanup runs.
                (!isPageFile(entry) || entry.status.active) &&
                entry.identity.canResumeIn(sessionIdentity)
            if (!keep) directory.deleteRecursively()
        }
    }

    private fun progress(downloaded: Long, total: Long): Int = if (total > 0L) {
        (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
    } else {
        0
    }

    companion object {
        const val TAG = "DownloadHandler"
        const val TEMP_DIRECTORY_NAME = "browser-downloads"
        const val ACTIVE_POLL_INTERVAL_MS = 750L
        const val IDLE_POLL_INTERVAL_MS = 5_000L
        /**
         * How long the page-file writer waits when its queue is empty.
         *
         * The sink runs on the WebView's thread, so the writer cannot block on a signal it would have
         * to be handed from that thread. It polls instead; a slice arrives well inside this interval,
         * so the poll costs one wake-up per slice and nothing while idle.
         */
        const val PUMP_IDLE_DELAY_MS = 25L
        /** A page that stops delivering chunks must fail instead of pinning a task forever. */
        const val PAGE_FILE_IDLE_TIMEOUT_MS = 60_000L
        const val PAGE_PROGRESS_PUBLISH_INTERVAL_MS = 200L
        const val PROGRESS_PERSIST_INTERVAL_MS = 10_000L
    }
}
